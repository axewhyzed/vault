using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Security.Claims;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Mvc;
using Vault.Api.Modules.Identity;
using Vault.Api.Modules.Shared.Persistence;
using Vault.Api.Modules.Transfers;

namespace Vault.Api.Modules.Storage;

internal static class FileEndpoints
{
    private const long MaxActiveBytes = 2_000_000_000;
    private const int MaxActiveItems = 10_000;
    private const string InitiateRoute = "/api/v1/uploads/initiate";

    public static IEndpointRouteBuilder MapFileEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var uploads = endpoints.MapGroup("/api/v1/uploads").WithTags("Uploads").RequireAuthorization();
        uploads.MapPost("/initiate", InitiateAsync).WithName("InitiateUpload").WithSummary("Start a streamed file upload.")
            .WithMetadata(new RequestSizeLimitAttribute(64 * 1024)).Produces<InitiateResponse>(201).Produces<ErrorResponse>(400).Produces<ErrorResponse>(401).Produces<ErrorResponse>(409).Produces<ErrorResponse>(413).Produces<ErrorResponse>(422).Produces<ErrorResponse>(429).Produces<ErrorResponse>(507);
        uploads.MapPut("/{id:guid}/content", PutContentAsync).WithName("UploadContent").WithSummary("Stream upload bytes into private staging.")
            .WithDescription("A size mismatch returns 422 with code upload_size_mismatch and leaves the session retryable. A SHA-256 mismatch returns 422 with code upload_checksum_mismatch and fails the session.")
            .WithMetadata(new RequestSizeLimitAttribute(LocalFileStore.MaxUploadBytes)).Produces<StagedResponse>(200).Produces<ErrorResponse>(400).Produces<ErrorResponse>(401).Produces<ErrorResponse>(404).Produces<ErrorResponse>(409).Produces<ErrorResponse>(410).Produces<ErrorResponse>(413).Produces<ErrorResponse>(415).Produces<ErrorResponse>(422).Produces<ErrorResponse>(507);
        uploads.MapPost("/{id:guid}/complete", CompleteAsync).WithName("CompleteUpload").WithSummary("Promote staged bytes and create an item.")
            .WithMetadata(new RequestSizeLimitAttribute(1024)).Produces<TransferEndpoints.CreateItemResponse>(201).Produces<ErrorResponse>(401).Produces<ErrorResponse>(404).Produces<ErrorResponse>(409).Produces<ErrorResponse>(410).Produces<ErrorResponse>(422);

        endpoints.MapGet("/api/v1/items/{id:guid}/download", DownloadAsync).WithTags("Items").WithName("DownloadItem")
            .WithSummary("Download an owned file as an attachment.").RequireAuthorization()
            .Produces(200).Produces<ErrorResponse>(401).Produces<ErrorResponse>(404).Produces<ErrorResponse>(410).Produces<ErrorResponse>(503);
        return endpoints;
    }

    private static async Task<IResult> InitiateAsync(InitiateRequest? request, HttpContext context, VaultDbContext db,
        LocalFileStore files, UploadInitiationLimiter limiter, TimeProvider clock, CancellationToken ct)
    {
        var ownerId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        if (!TryIntentKey(context, out var key)) return Error(context, 400, "invalid_idempotency_key", "Idempotency-Key must be a UUID.");
        if (request is null) return Error(context, 400, "invalid_request", "A JSON request body is required.");
        if (request.SizeBytes is < 0 or > LocalFileStore.MaxUploadBytes) return Error(context, 413, "item_too_large", "Files may not exceed 100000000 bytes.");
        if (request.RetentionDays is < 1 or > 7) return Error(context, 422, "invalid_retention", "Retention must be between 1 and 7 days.");
        var retention = request.RetentionDays ?? 7;
        var displayName = (request.DisplayName ?? "file").Trim();
        if (displayName.Length is < 1 or > 200 || displayName.Any(char.IsControl) || displayName.Contains('/') || displayName.Contains('\\') || displayName is "." or "..")
            return Error(context, 422, "invalid_display_name", "Display name must be at most 200 characters and be a safe file name.");
        var mediaType = NormalizeContentType(request.ContentType);
        var digest = request.Sha256?.Trim().ToLowerInvariant();
        if (digest is not null && (digest.Length != 64 || digest.Any(c => !Uri.IsHexDigit(c)))) return Error(context, 422, "invalid_sha256", "sha256 must contain 64 hexadecimal characters.");
        var fingerprintText = JsonSerializer.Serialize(new { displayName, mediaType, request.SizeBytes, sha256 = digest, retentionDays = retention });
        var fingerprint = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(fingerprintText))).ToLowerInvariant();

        await using var tx = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, ownerId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        var prior = await db.UploadSessions.SingleOrDefaultAsync(x => x.OwnerUserId == ownerId && x.IntentKey == key, ct);
        if (prior is not null)
        {
            if (!FixedEquals(prior.RequestFingerprint, fingerprint)) return Error(context, 409, "idempotency_conflict", "This idempotency key was already used for a different upload.");
            await tx.CommitAsync(ct);
            context.Response.Headers["Cache-Control"] = "no-store";
            return Results.Json(new InitiateResponse(prior.Id, $"/api/v1/uploads/{prior.Id:D}/content", prior.ExpiresAt, LocalFileStore.MaxUploadBytes), statusCode: 201);
        }

        if (!limiter.TryConsume(ownerId, clock.GetUtcNow())) return Error(context, 429, "upload_rate_limited", "Too many upload sessions were started. Try again shortly.");
        if (!files.HasReserve(request.SizeBytes))
        {
            await AddFailureAuditAsync(db, context, ownerId, deviceId, clock, ct);
            await tx.CommitAsync(ct);
            return Error(context, 507, "storage_unavailable", "The file volume cannot safely accept this upload.");
        }

        var now = clock.GetUtcNow().UtcDateTime;
        var active = db.Items.Where(x => x.OwnerUserId == ownerId && x.State == "available" && x.ExpiresAt > now);
        var count = await active.LongCountAsync(ct);
        var bytes = await active.Select(x => (long?)x.SizeBytes).SumAsync(ct) ?? 0;
        if (count + 1 > MaxActiveItems || bytes > MaxActiveBytes - request.SizeBytes)
        {
            await AddFailureAuditAsync(db, context, ownerId, deviceId, clock, ct);
            await tx.CommitAsync(ct);
            return Error(context, 409, "quota_exceeded", "The active item or byte quota would be exceeded.");
        }

        var session = new UploadSession
        {
            OwnerUserId = ownerId, CreatedByDeviceId = deviceId, IntentKey = key, RequestFingerprint = fingerprint,
            DisplayName = displayName, ContentType = mediaType, ExpectedSizeBytes = request.SizeBytes,
            ExpectedSha256 = digest, RetentionDays = retention, State = "initiated",
            StagingKey = $"{Guid.NewGuid():N}.part", StorageKey = Guid.NewGuid().ToString("N"),
            CreatedAt = now, ExpiresAt = now.AddHours(24), UpdatedAt = now
        };
        db.UploadSessions.Add(session);
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        context.Response.Headers["Cache-Control"] = "no-store";
        return Results.Json(new InitiateResponse(session.Id, $"/api/v1/uploads/{session.Id:D}/content", session.ExpiresAt, LocalFileStore.MaxUploadBytes), statusCode: 201);
    }

    private static async Task<IResult> PutContentAsync(Guid id, HttpContext context, VaultDbContext db, LocalFileStore files,
        StorageMaintenanceGate gate, TimeProvider clock, CancellationToken ct)
    {
        var ownerId = IdentityEndpoints.GetGuid(context.User, "userId");
        if (!TryIntentKey(context, out var key)) return Error(context, 400, "invalid_idempotency_key", "Idempotency-Key must be a UUID.");
        if (!string.Equals(context.Request.ContentType?.Split(';', 2)[0].Trim(), "application/octet-stream", StringComparison.OrdinalIgnoreCase))
            return Error(context, 415, "invalid_content_type", "Upload bytes must use application/octet-stream.");
        if (context.Request.ContentLength is > LocalFileStore.MaxUploadBytes) return Error(context, 413, "item_too_large", "Files may not exceed 100000000 bytes.");
        using var maintenanceLock = gate.EnterShared();

        // PUT deliberately locks only the session row. It never waits for an owner lock.
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        var locked = await db.UploadSessions.Where(x => x.Id == id && x.OwnerUserId == ownerId)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, x => x.State), ct);
        if (locked == 0) return Error(context, 404, "upload_not_found", "The upload was not found.");
        var session = await db.UploadSessions.SingleAsync(x => x.Id == id && x.OwnerUserId == ownerId, ct);
        if (session.IntentKey != key) return Error(context, 409, "idempotency_conflict", "The upload intent key does not match.");
        if (session.ExpiresAt <= clock.GetUtcNow().UtcDateTime || session.State is "expired" or "failed") return Error(context, 410, "upload_expired", "The upload session has expired.");
        if (session.State is "staged" or "complete")
        {
            await tx.CommitAsync(ct);
            context.Response.Headers["Cache-Control"] = "no-store";
            return Results.Ok(new StagedResponse(id, "staged", session.SizeBytes ?? session.ExpectedSizeBytes, session.Sha256 ?? session.ExpectedSha256 ?? ""));
        }
        if (session.State == "receiving") return Error(context, 409, "upload_in_progress", "An upload stream is already active for this session.");

        session.State = "receiving";
        session.UpdatedAt = clock.GetUtcNow().UtcDateTime;
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        // Stream outside the database transaction so slow clients do not hold database locks.
        (long Size, string Sha256) result;
        try { result = await files.WriteAndHashAsync(session.StagingKey, context.Request.Body, ct); }
        catch (UploadTooLargeException)
        {
            files.DeleteStaging(session.StagingKey);
            await ResetReceivingAsync(db, id, ownerId, clock, CancellationToken.None);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 413, "item_too_large", "Files may not exceed 100000000 bytes.");
        }
        catch (UploadReserveBreachedException)
        {
            files.DeleteStaging(session.StagingKey);
            await ResetReceivingAsync(db, id, ownerId, clock, CancellationToken.None);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 507, "storage_unavailable", "The file volume fell below its required free-space reserve.");
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // An interrupted request is restartable; the next PUT replaces a partial staging file.
            files.DeleteStaging(session.StagingKey);
            await ResetReceivingAsync(db, id, ownerId, clock, CancellationToken.None);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 400, "upload_interrupted", "The upload stream was interrupted. Retry the same upload intent.");
        }
        catch (IOException) when (ct.IsCancellationRequested)
        {
            files.DeleteStaging(session.StagingKey);
            await ResetReceivingAsync(db, id, ownerId, clock, CancellationToken.None);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 400, "upload_interrupted", "The upload stream was interrupted. Retry the same upload intent.");
        }
        catch (IOException)
        {
            files.DeleteStaging(session.StagingKey);
            await ResetReceivingAsync(db, id, ownerId, clock, CancellationToken.None);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 507, "storage_unavailable", "The file could not be staged.");
        }

        if (result.Size != session.ExpectedSizeBytes)
        {
            files.DeleteStaging(session.StagingKey);
            await ResetReceivingAsync(db, id, ownerId, clock, CancellationToken.None);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 422, "upload_size_mismatch", "The uploaded byte count does not match the declared size. Retry the same upload intent.");
        }
        if (session.ExpectedSha256 is not null && !FixedEquals(result.Sha256, session.ExpectedSha256))
        {
            files.DeleteStaging(session.StagingKey);
            await MarkFailedAsync(db, id, ownerId, clock, ct);
            await RecordFailureAsync(db, context, ownerId, IdentityEndpoints.GetGuid(context.User, "deviceId"), clock);
            return Error(context, 422, "upload_checksum_mismatch", "The uploaded size or SHA-256 does not match the declared intent.");
        }
        // The database state may be uncertain after a failed commit. Keep the staged bytes for reconciliation.
        await using var stagedTx = await db.Database.BeginTransactionAsync(ct);
        await db.UploadSessions.Where(x => x.Id == id && x.OwnerUserId == ownerId)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "staged").SetProperty(x => x.SizeBytes, result.Size)
                .SetProperty(x => x.Sha256, result.Sha256).SetProperty(x => x.UpdatedAt, clock.GetUtcNow().UtcDateTime), ct);
        await stagedTx.CommitAsync(ct);
        context.Response.Headers["Cache-Control"] = "no-store";
        return Results.Ok(new StagedResponse(id, "staged", result.Size, result.Sha256));
    }

    private static async Task<IResult> CompleteAsync(Guid id, HttpContext context, VaultDbContext db, LocalFileStore files,
        StorageMaintenanceGate gate, TimeProvider clock, CancellationToken ct)
    {
        var ownerId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        if (!TryIntentKey(context, out var key)) return Error(context, 400, "invalid_idempotency_key", "Idempotency-Key must be a UUID.");
        using var maintenanceLock = gate.EnterShared();
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, ownerId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        await db.UploadSessions.Where(x => x.Id == id && x.OwnerUserId == ownerId)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, x => x.State), ct);
        var session = await db.UploadSessions.SingleOrDefaultAsync(x => x.Id == id && x.OwnerUserId == ownerId, ct);
        if (session is null) return Error(context, 404, "upload_not_found", "The upload was not found.");
        if (session.IntentKey != key) return Error(context, 409, "idempotency_conflict", "The upload intent key does not match.");
        if (session.State == "complete" && session.CompleteResponseJson is not null)
        {
            await tx.CommitAsync(ct);
            context.Response.Headers["Cache-Control"] = "no-store";
            return Results.Content(session.CompleteResponseJson, "application/json", Encoding.UTF8, 201);
        }
        var now = clock.GetUtcNow().UtcDateTime;
        if (session.ExpiresAt <= now || session.State == "expired") return Error(context, 410, "upload_expired", "The upload session has expired.");
        if (session.State != "staged" || session.SizeBytes is null || session.Sha256 is null) return Error(context, 409, "upload_not_staged", "The upload bytes have not been staged.");
        if (!files.HasReserve(0))
        {
            await AddFailureAuditAsync(db, context, ownerId, deviceId, clock, ct);
            await tx.CommitAsync(ct);
            return Error(context, 507, "storage_unavailable", "The file volume is below its required free-space reserve.");
        }
        var active = db.Items.Where(x => x.OwnerUserId == ownerId && x.State == "available" && x.ExpiresAt > now);
        var count = await active.LongCountAsync(ct);
        var bytes = await active.Select(x => (long?)x.SizeBytes).SumAsync(ct) ?? 0;
        if (count + 1 > MaxActiveItems || bytes > MaxActiveBytes - session.SizeBytes.Value)
        {
            await AddFailureAuditAsync(db, context, ownerId, deviceId, clock, ct);
            await tx.CommitAsync(ct);
            return Error(context, 409, "quota_exceeded", "The active item or byte quota would be exceeded.");
        }

        try { files.Promote(session.StagingKey, session.StorageKey); }
        catch (FileNotFoundException)
        {
            await AddFailureAuditAsync(db, context, ownerId, deviceId, clock, ct);
            await tx.CommitAsync(ct);
            return Error(context, 409, "staged_file_missing", "The staged file is unavailable; reconciliation is required.");
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            await AddFailureAuditAsync(db, context, ownerId, deviceId, clock, ct);
            await tx.CommitAsync(ct);
            return Error(context, 507, "storage_unavailable", "The staged file could not be promoted.");
        }
        var item = new RelayItem
        {
            Id = Guid.NewGuid(), OwnerUserId = ownerId, CreatedByDeviceId = deviceId, Type = "file", State = "available",
            DisplayName = session.DisplayName, ContentType = session.ContentType, SizeBytes = session.SizeBytes.Value,
            CreatedAt = now, AvailableAt = now, ExpiresAt = now.AddDays(session.RetentionDays), UpdatedAt = now, Version = 1
        };
        var response = new TransferEndpoints.CreateItemResponse(item.Id, item.Type, item.State, item.AvailableAt, item.ExpiresAt, item.Version);
        var json = JsonSerializer.Serialize(response, JsonSerializerOptions.Web);
        db.Items.Add(item);
        session.ItemId = item.Id;
        session.State = "complete";
        session.CompleteResponseJson = json;
        session.UpdatedAt = now;
        db.AuditEvents.Add(new VaultAuditEvent { EventType = "upload_completed", ActorUserId = ownerId, ActorDeviceId = deviceId, ItemId = item.Id, CorrelationId = context.TraceIdentifier, OccurredAt = now });
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        context.Response.Headers.Location = $"/api/v1/items/{item.Id:D}";
        context.Response.Headers["Cache-Control"] = "no-store";
        return Results.Json(response, statusCode: 201);
    }

    private static async Task<IResult> DownloadAsync(Guid id, HttpContext context, VaultDbContext db, LocalFileStore files, TimeProvider clock, CancellationToken ct)
    {
        var ownerId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, ownerId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        var item = await db.Items.SingleOrDefaultAsync(x => x.Id == id && x.OwnerUserId == ownerId, ct);
        if (item is null || item.Type != "file") return Error(context, 404, "item_not_found", "The item was not found.");
        if (item.ExpiresAt <= clock.GetUtcNow().UtcDateTime || item.State == "expired") return Error(context, 410, "item_expired", "The item has expired.");
        if (item.State != "available") return Error(context, 404, "item_not_found", "The item was not found.");
        var session = await db.UploadSessions.SingleOrDefaultAsync(x => x.ItemId == id && x.OwnerUserId == ownerId, ct);
        if (session is null) return Error(context, 503, "file_unavailable", "The file is unavailable.");
        var stream = files.OpenRead(session.StorageKey);
        if (stream is null)
        {
            item.State = "unavailable";
            item.UpdatedAt = clock.GetUtcNow().UtcDateTime;
            item.Version++;
            await db.SaveChangesAsync(ct);
            await tx.CommitAsync(ct);
            return Error(context, 503, "file_unavailable", "The file is unavailable.");
        }
        db.AuditEvents.Add(new VaultAuditEvent { EventType = "file_downloaded", ActorUserId = ownerId, ActorDeviceId = deviceId, ItemId = id, CorrelationId = context.TraceIdentifier, OccurredAt = clock.GetUtcNow().UtcDateTime });
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
        context.Response.Headers["X-Content-Type-Options"] = "nosniff";
        context.Response.Headers["Cache-Control"] = "no-store, max-age=0";
        context.Response.Headers["Pragma"] = "no-cache";
        return Results.File(stream, item.ContentType, fileDownloadName: item.DisplayName, enableRangeProcessing: false);
    }

    private static async Task MarkFailedAsync(VaultDbContext db, Guid id, Guid ownerId, TimeProvider clock, CancellationToken ct) =>
        await db.UploadSessions.Where(x => x.Id == id && x.OwnerUserId == ownerId && x.State != "complete")
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "failed").SetProperty(x => x.UpdatedAt, clock.GetUtcNow().UtcDateTime), ct);

    private static async Task ResetReceivingAsync(VaultDbContext db, Guid id, Guid ownerId, TimeProvider clock, CancellationToken ct) =>
        await db.UploadSessions.Where(x => x.Id == id && x.OwnerUserId == ownerId && x.State == "receiving")
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "initiated").SetProperty(x => x.UpdatedAt, clock.GetUtcNow().UtcDateTime), ct);

    private static async Task<bool> LockOwnerAsync(VaultDbContext db, Guid ownerId, CancellationToken ct) =>
        await db.Users.Where(x => x.Id == ownerId && x.Status == "active").ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct) == 1;

    private static string NormalizeContentType(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 100 || value.Any(c => char.IsControl(c) || c is '\r' or '\n' or ';')) return "application/octet-stream";
        var type = value.Trim();
        return MediaTypeHeaderValue.TryParse(type, out var parsed) && parsed.Parameters.Count == 0
            ? parsed.MediaType ?? "application/octet-stream" : "application/octet-stream";
    }

    private static bool TryIntentKey(HttpContext context, out Guid key) => Guid.TryParseExact(context.Request.Headers["Idempotency-Key"].ToString(), "D", out key) && key != Guid.Empty;
    private static async Task RecordFailureAsync(VaultDbContext db, HttpContext context, Guid ownerId, Guid deviceId, TimeProvider clock)
    {
        try
        {
            db.AuditEvents.Add(new VaultAuditEvent { EventType = "upload_failed", ActorUserId = ownerId, ActorDeviceId = deviceId, CorrelationId = context.TraceIdentifier, OccurredAt = clock.GetUtcNow().UtcDateTime });
            await db.SaveChangesAsync(CancellationToken.None);
        }
        catch { db.ChangeTracker.Clear(); }
    }
    private static async Task AddFailureAuditAsync(VaultDbContext db, HttpContext context, Guid ownerId,
        Guid deviceId, TimeProvider clock, CancellationToken ct)
    {
        db.AuditEvents.Add(new VaultAuditEvent
        {
            EventType = "upload_failed", ActorUserId = ownerId, ActorDeviceId = deviceId,
            CorrelationId = context.TraceIdentifier, OccurredAt = clock.GetUtcNow().UtcDateTime
        });
        await db.SaveChangesAsync(ct);
    }
    private static bool FixedEquals(string a, string b) => a.Length == b.Length && CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(a), Encoding.ASCII.GetBytes(b));
    private static IResult Error(HttpContext context, int status, string code, string message) => IdentityErrors.Create(context, status, code, message);
    internal sealed record InitiateRequest(string? DisplayName, string? ContentType, long SizeBytes, string? Sha256, int? RetentionDays);
    internal sealed record InitiateResponse(Guid UploadId, string UploadUrl, DateTime ExpiresAt, long MaxAcceptedBytes);
    internal sealed record StagedResponse(Guid UploadId, string State, long SizeBytes, string Sha256);
    internal sealed record ErrorResponse(string Code, string Message, string CorrelationId);
}

internal sealed class UploadInitiationLimiter
{
    private readonly ConcurrentDictionary<Guid, (DateTimeOffset Window, int Count)> _windows = new();
    public bool TryConsume(Guid ownerId, DateTimeOffset now)
    {
        while (true)
        {
            var current = _windows.GetOrAdd(ownerId, (now, 0));
            var next = current.Window.AddMinutes(1) <= now ? (now, 1) : (current.Window, current.Count + 1);
            if (next.Item2 > 10) return false;
            if (_windows.TryUpdate(ownerId, next, current)) return true;
        }
    }
}
