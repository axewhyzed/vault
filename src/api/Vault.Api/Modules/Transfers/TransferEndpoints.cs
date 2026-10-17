using System.Globalization;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.AspNetCore.Mvc;
using Vault.Api.Modules.Identity;
using Vault.Api.Modules.Shared.Persistence;
using Vault.Api.Modules.Storage;

namespace Vault.Api.Modules.Transfers;

internal static class TransferEndpoints
{
    private const long MaxActiveBytes = 2_000_000_000;
    private const int MaxActiveItems = 10_000;
    private const int MaxTextBytes = 262_144;
    private const int MaxUrlBytes = 8_192;

    public static IEndpointRouteBuilder MapTransferEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/v1/items").WithTags("Items").RequireAuthorization();
        group.MapPost("/text", async (TextCreateRequest? request, HttpContext context, VaultDbContext db, TimeProvider clock, CancellationToken ct) =>
        {
            if (request is null) return Error(context, 400, "invalid_request", "A JSON request body is required.");
            return await CreateAsync("text", request.Text, null, request.DisplayName, request.RetentionDays, request.RetentionHours, context, db, clock, ct);
        })
            .WithMetadata(new RequestSizeLimitAttribute(2_000_000)).WithName("CreateTextItem").WithSummary("Create a text item.").Produces<CreateItemResponse>(201).Produces<ErrorResponse>(400).Produces<ErrorResponse>(401).Produces<ErrorResponse>(403).Produces<ErrorResponse>(409).Produces<ErrorResponse>(413).Produces<ErrorResponse>(422);
        group.MapPost("/url", async (UrlCreateRequest? request, HttpContext context, VaultDbContext db, TimeProvider clock, CancellationToken ct) =>
        {
            if (request is null) return Error(context, 400, "invalid_request", "A JSON request body is required.");
            return await CreateAsync("url", null, request.Url, request.DisplayName, request.RetentionDays, request.RetentionHours, context, db, clock, ct);
        })
            .WithMetadata(new RequestSizeLimitAttribute(2_000_000)).WithName("CreateUrlItem").WithSummary("Create a URL item without fetching it.").Produces<CreateItemResponse>(201).Produces<ErrorResponse>(400).Produces<ErrorResponse>(401).Produces<ErrorResponse>(403).Produces<ErrorResponse>(409).Produces<ErrorResponse>(413).Produces<ErrorResponse>(422);
        group.MapGet("", ListAsync).WithName("ListItems").WithSummary("List active items newest first.").Produces<ListItemsResponse>().Produces<ErrorResponse>(400).Produces<ErrorResponse>(401);
        group.MapGet("/{id:guid}", GetAsync).WithName("GetItem").WithSummary("Read one owned item.").Produces<ItemDetailResponse>().Produces<ErrorResponse>(401).Produces<ErrorResponse>(403).Produces<ErrorResponse>(404).Produces<ErrorResponse>(410);
        group.MapDelete("/{id:guid}", async (Guid id, HttpContext context, VaultDbContext db, TimeProvider clock, LocalFileStore files,
            StorageMaintenanceGate gate, CancellationToken ct) => await DeleteAsync(id, context, db, clock, files, gate, ct))
            .WithName("DeleteItem").WithSummary("Delete an owned item.").Produces(204).Produces<ErrorResponse>(401).Produces<ErrorResponse>(403);
        return endpoints;
    }

    private static async Task<IResult> CreateAsync(string type, string? suppliedText, string? suppliedUrl, string? suppliedName,
        int? suppliedRetentionDays, int? suppliedRetentionHours, HttpContext context, VaultDbContext db, TimeProvider clock, CancellationToken ct)
    {
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        var keyText = context.Request.Headers["Idempotency-Key"].ToString();
        if (!Guid.TryParseExact(keyText, "D", out var key) || key == Guid.Empty)
            return Error(context, 400, "invalid_idempotency_key", "Idempotency-Key must be a UUID.");

        TimeSpan retentionDuration;
        int retentionFingerprintHours;
        if (suppliedRetentionHours is not null)
        {
            if (suppliedRetentionHours < 1 || suppliedRetentionHours > 168)
                return Error(context, 422, "invalid_retention", "Retention must be between 1 and 168 hours.");
            retentionDuration = TimeSpan.FromHours(suppliedRetentionHours.Value);
            retentionFingerprintHours = suppliedRetentionHours.Value;
        }
        else
        {
            if (suppliedRetentionDays is < 1 or > 7)
                return Error(context, 422, "invalid_retention", "Retention must be between 1 and 7 days.");
            var days = suppliedRetentionDays ?? 7;
            retentionDuration = TimeSpan.FromDays(days);
            retentionFingerprintHours = days * 24;
        }

        string content;
        string displayName;
        if (type == "text")
        {
            content = suppliedText ?? "";
            if (content.Length == 0 || string.IsNullOrWhiteSpace(content) || content.Contains('\0'))
                return Error(context, 422, "invalid_text", "Text must be non-empty, contain visible content, and not include NUL.");
            if (Encoding.UTF8.GetByteCount(content) > MaxTextBytes) return Error(context, 413, "item_too_large", "Text exceeds 262144 UTF-8 bytes.");
            displayName = ResolveDisplayName(suppliedName, "Text snippet", context, out var nameError);
            if (nameError is not null) return nameError;
        }
        else
        {
            content = (suppliedUrl ?? "").Trim();
            var bytes = Encoding.UTF8.GetByteCount(content);
            if (bytes > MaxUrlBytes) return Error(context, 413, "item_too_large", "URL exceeds 8192 UTF-8 bytes.");
            if (!IsValidUrl(content, out var hostname)) return Error(context, 422, "invalid_url", "URL must be an absolute HTTP or HTTPS address without credentials or whitespace.");
            displayName = ResolveDisplayName(suppliedName, hostname, context, out var nameError);
            if (nameError is not null) return nameError;
        }

        var payloadBytes = Encoding.UTF8.GetByteCount(content);
        var fingerprintInput = JsonSerializer.Serialize(new { type, content, resolvedDisplayName = displayName, retentionHours = retentionFingerprintHours });
        var fingerprint = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(fingerprintInput))).ToLowerInvariant();
        var route = type == "text" ? "/api/v1/items/text" : "/api/v1/items/url";

        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, userId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        var previous = await db.ItemIdempotencyRecords.AsNoTracking().SingleOrDefaultAsync(x => x.UserId == userId && x.Route == route && x.Key == key, ct);
        if (previous is not null)
        {
            if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(previous.RequestFingerprint), Encoding.ASCII.GetBytes(fingerprint)))
                return Error(context, 409, "idempotency_conflict", "This idempotency key was already used for a different request.");
            context.Response.Headers.Location = $"/api/v1/items/{previous.ItemId:D}";
            return Results.Content(previous.ResponseJson, "application/json", Encoding.UTF8, previous.StatusCode);
        }

        var now = clock.GetUtcNow().UtcDateTime;
        var active = db.Items.Where(x => x.OwnerUserId == userId && x.State == "available" && x.ExpiresAt > now);
        var activeCount = await active.LongCountAsync(ct);
        var activeBytes = await active.Select(x => (long?)x.SizeBytes).SumAsync(ct) ?? 0;
        if (activeCount + 1 > MaxActiveItems || activeBytes > MaxActiveBytes - payloadBytes)
            return Error(context, 409, "quota_exceeded", "The active item or byte quota would be exceeded.");

        var item = new RelayItem
        {
            OwnerUserId = userId, CreatedByDeviceId = deviceId, Type = type, State = "available", DisplayName = displayName,
            TextContent = content, ContentType = "text/plain", SizeBytes = payloadBytes, CreatedAt = now,
            AvailableAt = now, ExpiresAt = now.Add(retentionDuration), UpdatedAt = now, Version = 1
        };
        var response = new CreateItemResponse(item.Id, item.Type, item.State, item.AvailableAt, item.ExpiresAt, item.Version);
        var responseJson = JsonSerializer.Serialize(response, JsonSerializerOptions.Web);
        db.Items.Add(item);
        db.ItemIdempotencyRecords.Add(new ItemIdempotencyRecord
        {
            UserId = userId, Route = route, Key = key, RequestFingerprint = fingerprint, ItemId = item.Id,
            ResponseJson = responseJson, StatusCode = 201, CreatedAt = now
        });
        db.AuditEvents.Add(Audit("item_created", userId, deviceId, item.Id, context, now));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        context.Response.Headers.Location = $"/api/v1/items/{item.Id:D}";
        return Results.Json(response, statusCode: 201);
    }

    private static async Task<IResult> ListAsync(HttpContext context, VaultDbContext db, TimeProvider clock, int? limit, string? cursor, CancellationToken ct)
    {
        if (limit is < 1 or > 100) return Error(context, 422, "invalid_limit", "Limit must be between 1 and 100.");
        CursorPosition? position = null;
        if (cursor is not null && !TryDecodeCursor(cursor, out position)) return Error(context, 400, "invalid_cursor", "Cursor is malformed or unsupported.");
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var now = clock.GetUtcNow().UtcDateTime;
        var query = from item in db.Items.AsNoTracking()
                    join device in db.Devices.AsNoTracking() on item.CreatedByDeviceId equals device.Id
                    where item.OwnerUserId == userId && item.State == "available" && item.ExpiresAt > now
                    select new { item, device };
        if (position is not null)
            query = query.Where(x => x.item.CreatedAt < position.CreatedAt || (x.item.CreatedAt == position.CreatedAt
                && x.item.Id.ToString().CompareTo(position.Id.ToString()) < 0));
        var take = (limit ?? 30) + 1;
        var rows = await query.OrderByDescending(x => x.item.CreatedAt).ThenByDescending(x => x.item.Id).Take(take).ToListAsync(ct);
        var hasMore = rows.Count > (limit ?? 30);
        if (hasMore) rows.RemoveAt(rows.Count - 1);
        var items = rows.Select(x => ToSummary(x.item, x.device)).ToArray();
        var next = hasMore && rows.Count > 0 ? EncodeCursor(rows[^1].item.CreatedAt, rows[^1].item.Id) : null;
        var allActive = db.Items.AsNoTracking().Where(x => x.OwnerUserId == userId && x.State == "available" && x.ExpiresAt > now);
        var activeCount = await allActive.LongCountAsync(ct);
        var activeBytes = await allActive.Select(x => (long?)x.SizeBytes).SumAsync(ct) ?? 0;
        return Results.Ok(new ListItemsResponse(items, next, new QuotaResponse(activeBytes, MaxActiveBytes, activeCount, MaxActiveItems)));
    }

    private static async Task<IResult> GetAsync(Guid id, HttpContext context, VaultDbContext db, TimeProvider clock, CancellationToken ct)
    {
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, userId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        var row = await (from item in db.Items
                         join device in db.Devices on item.CreatedByDeviceId equals device.Id
                         where item.Id == id && item.OwnerUserId == userId
                         select new { item, device }).SingleOrDefaultAsync(ct);
        if (row is null) return Error(context, 404, "item_not_found", "The item was not found.");
        var now = clock.GetUtcNow().UtcDateTime;
        if (row.item.State == "expired" || (row.item.State == "available" && row.item.ExpiresAt <= now)) return Error(context, 410, "item_expired", "The item has expired.");
        if (row.item.State != "available") return Error(context, 404, "item_not_found", "The item was not found.");
        var summary = ToSummary(row.item, row.device);
        var snapshot = new ItemDetailResponse(summary.Id, summary.Type, summary.State, summary.DisplayName, summary.SizeBytes, summary.CreatedAt,
            summary.AvailableAt, summary.ExpiresAt, summary.Version, summary.SourceDevice,
            row.item.Type == "file" ? null : row.item.TextContent,
            row.item.ContentType,
            row.item.Type == "file" ? $"/api/v1/items/{row.item.Id:D}/download" : null,
            row.item.Type == "file" ? await db.UploadSessions.Where(x => x.ItemId == row.item.Id).Select(x => x.Sha256).SingleOrDefaultAsync(ct) : null);
        db.AuditEvents.Add(Audit("item_read", userId, deviceId, id, context, now));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return Results.Ok(snapshot);
    }

    private static async Task<IResult> DeleteAsync(Guid id, HttpContext context, VaultDbContext db, TimeProvider clock,
        LocalFileStore files, StorageMaintenanceGate gate, CancellationToken ct)
    {
        using var maintenanceLock = gate.EnterShared();
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, userId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        var item = await db.Items.SingleOrDefaultAsync(x => x.Id == id && x.OwnerUserId == userId, ct);
        if (item is not null && item.State == "available")
        {
            var now = clock.GetUtcNow().UtcDateTime;
            item.State = "deleted";
            item.TextContent = null;
            item.UpdatedAt = now;
            item.Version++;
            db.AuditEvents.Add(Audit("item_deleted", userId, deviceId, item.Id, context, now));
            await db.SaveChangesAsync(ct);
        }
        var session = item is null ? null : await db.UploadSessions.SingleOrDefaultAsync(x => x.ItemId == item.Id, ct);
        await transaction.CommitAsync(ct);
        if (session is not null) files.DeleteObject(session.StorageKey);
        return Results.NoContent();
    }

    private static async Task<bool> LockOwnerAsync(VaultDbContext db, Guid userId, CancellationToken ct) =>
        await db.Users.Where(x => x.Id == userId && x.Status == "active").ExecuteUpdateAsync(setters => setters.SetProperty(x => x.Status, x => x.Status), ct) == 1;

    private static VaultAuditEvent Audit(string type, Guid userId, Guid deviceId, Guid itemId, HttpContext context, DateTime at) => new()
    {
        EventType = type, ActorUserId = userId, ActorDeviceId = deviceId, ItemId = itemId,
        CorrelationId = context.TraceIdentifier, OccurredAt = at
    };

    private static ItemSummary ToSummary(RelayItem item, VaultDevice device) => new(item.Id, item.Type, item.State, item.DisplayName, item.SizeBytes,
        item.CreatedAt, item.AvailableAt, item.ExpiresAt, item.Version, new SourceDevice(device.Id, device.Name, device.Platform));

    private static string ResolveDisplayName(string? supplied, string fallback, HttpContext context, out IResult? error)
    {
        error = null;
        if (supplied is null)
        {
            if (fallback.Length > 200)
            {
                error = Error(context, 422, "invalid_display_name", "The default display name exceeds 200 characters.");
                return "";
            }
            return fallback;
        }
        var value = supplied.Trim();
        if (value.Length > 200 || value.Any(char.IsControl))
        {
            error = Error(context, 422, "invalid_display_name", "Display name must be at most 200 characters and contain no control characters.");
            return "";
        }
        return value.Length == 0 ? fallback : value;
    }

    private static bool IsValidUrl(string value, out string hostname)
    {
        hostname = "";
        if (value.Length == 0 || value.Any(char.IsControl) || value.Any(char.IsWhiteSpace) || !Uri.TryCreate(value, UriKind.Absolute, out var uri)) return false;
        if ((uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps) || string.IsNullOrWhiteSpace(uri.Host) || uri.UserInfo.Length > 0) return false;
        hostname = uri.Host;
        return true;
    }

    private static string EncodeCursor(DateTime createdAt, Guid id)
    {
        var json = JsonSerializer.Serialize(new CursorPayload(1, createdAt.ToUniversalTime().ToString("O", CultureInfo.InvariantCulture), id));
        return Convert.ToBase64String(Encoding.UTF8.GetBytes(json)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    }

    private static bool TryDecodeCursor(string value, out CursorPosition? position)
    {
        position = null;
        if (value.Length is 0 or > 256 || value.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))) return false;
        try
        {
            var padded = value.Replace('-', '+').Replace('_', '/');
            padded += new string('=', (4 - padded.Length % 4) % 4);
            var payload = JsonSerializer.Deserialize<CursorPayload>(Convert.FromBase64String(padded));
            if (payload is null || payload.Version != 1 || payload.Id == Guid.Empty ||
                !DateTime.TryParseExact(payload.CreatedAt, "O", CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var timestamp) || timestamp.Kind != DateTimeKind.Utc) return false;
            position = new CursorPosition(timestamp, payload.Id);
            return true;
        }
        catch { return false; }
    }

    private static IResult Error(HttpContext context, int status, string code, string message) => IdentityErrors.Create(context, status, code, message);

    internal sealed record TextCreateRequest(string? Text, string? DisplayName, int? RetentionDays = null, int? RetentionHours = null);
    internal sealed record UrlCreateRequest(string? Url, string? DisplayName, int? RetentionDays = null, int? RetentionHours = null);
    internal sealed record CreateItemResponse(Guid Id, string Type, string State, DateTime AvailableAt, DateTime ExpiresAt, int Version);
    internal sealed record SourceDevice(Guid Id, string Name, string Platform);
    internal sealed record ItemSummary(Guid Id, string Type, string State, string DisplayName, long SizeBytes, DateTime CreatedAt,
        DateTime AvailableAt, DateTime ExpiresAt, int Version, SourceDevice SourceDevice);
    internal sealed record ItemDetailResponse(Guid Id, string Type, string State, string DisplayName, long SizeBytes, DateTime CreatedAt,
        DateTime AvailableAt, DateTime ExpiresAt, int Version, SourceDevice SourceDevice, string? Text, string ContentType, string? DownloadUrl, string? Sha256);
    internal sealed record QuotaResponse(long ActiveBytes, long MaxActiveBytes, long ActiveItems, int MaxActiveItems);
    internal sealed record ListItemsResponse(ItemSummary[] Items, string? NextCursor, QuotaResponse Quota);
    private sealed record CursorPayload(int Version, string CreatedAt, Guid Id);
    private sealed record CursorPosition(DateTime CreatedAt, Guid Id);
    internal sealed record ErrorResponse(string Code, string Message, string CorrelationId);
}
