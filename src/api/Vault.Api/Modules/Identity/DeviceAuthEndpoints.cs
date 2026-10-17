using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Identity;

internal static class DeviceAuthEndpoints
{
    private static readonly TimeSpan PairingLifetime = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan AccessLifetime = TimeSpan.FromMinutes(15);
    private static readonly TimeSpan RefreshLifetime = TimeSpan.FromDays(14);

    public static IEndpointRouteBuilder MapDeviceAuthEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var devices = endpoints.MapGroup("/api/v1/devices").WithTags("Devices");
        devices.MapPost("/pairing-sessions", CreatePairingSessionAsync).RequireAuthorization()
            .WithName("CreateDevicePairingSession").WithSummary("Create a short lived session for pairing an Android device.")
            .Accepts<JsonElement>("application/json").Produces<PairingSessionResponse>(201).Produces(400).Produces(401).Produces(403).Produces(415).Produces(429).Produces(503);
        devices.MapPost("/pair", PairAsync).AllowAnonymous()
            .WithName("PairAndroidDevice").WithSummary("Pair an Android device using a one time pairing secret.")
            .Accepts<PairRequest>("application/json").Produces<TokenResponse>(201).Produces(400).Produces(401).Produces(409).Produces(415).Produces(429);
        devices.MapPost("/login", DeviceLoginAsync).AllowAnonymous()
            .WithName("DeviceLogin").WithSummary("Sign in an Android device using username/email and password.")
            .Accepts<DeviceLoginRequest>("application/json").Produces<TokenResponse>(201).Produces(400).Produces(401).Produces(415).Produces(429);
        devices.MapGet("/", GetActiveDevicesAsync).RequireAuthorization()
            .WithName("GetActiveDevices").WithSummary("List all active connected devices for the authenticated user.")
            .Produces<DeviceListResponse>(200).Produces(401);
        devices.MapPost("/revoke-current", RevokeCurrentDeviceAsync).RequireAuthorization()
            .WithName("RevokeCurrentDevice").WithSummary("Revoke the calling device session.")
            .Produces(204).Produces(401).Produces(403);

        var auth = endpoints.MapGroup("/api/v1/auth").WithTags("Authentication");
        auth.MapPost("/refresh", RefreshAsync).AllowAnonymous()
            .WithName("RefreshAndroidSession").WithSummary("Rotate an Android refresh token.")
            .Accepts<RefreshRequest>("application/json").Produces<TokenResponse>(200).Produces(400).Produces(401).Produces(415);
        auth.MapPost("/sessions/revoke-all", RevokeAllAsync).RequireAuthorization()
            .WithName("RevokeAllSessions").WithSummary("Revoke every web and Android session for the account.")
            .Produces(204).Produces(401).Produces(403);
        return endpoints;
    }

    private static async Task<IResult> CreatePairingSessionAsync(JsonElement body, HttpContext context, VaultDbContext db, IConfiguration config,
        DevicePairingRateLimiter limiter, TimeProvider clock, CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers.Vary = "Cookie";
        if (context.Request.Headers.Authorization.Count > 0 || context.User.Identity?.AuthenticationType != IdentityEndpoints.Scheme)
            return Error(context, 401, "cookie_session_required", "Create a pairing session from an authenticated browser session.");
        if (!JsonOnly(context)) return Error(context, 415, "json_required", "Content-Type must be application/json.");
        if (body.ValueKind != JsonValueKind.Object || body.EnumerateObject().Any()) return Error(context, 400, "invalid_request", "The request body must be an empty JSON object.");
        var origin = config["Security:ServerOrigin"];
        if (!IsOrigin(origin)) return Error(context, 503, "server_origin_unconfigured", "A trusted server origin is not configured.");
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var ip = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        if (!limiter.TryCreate(ip, userId, out var retry))
        {
            context.Response.Headers.RetryAfter = retry.ToString(System.Globalization.CultureInfo.InvariantCulture);
            db.AuditEvents.Add(Audit("device_pairing_rate_limited", userId, null, context));
            await db.SaveChangesAsync(ct);
            return Error(context, 429, "rate_limited", "Too many pairing attempts. Try again later.");
        }
        var secret = Base64Url(RandomNumberGenerator.GetBytes(32));
        var now = clock.GetUtcNow().UtcDateTime;
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, userId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        var row = new DevicePairingSession { UserId = userId, SecretHash = Hash(secret), CreatedAt = now, ExpiresAt = now.Add(PairingLifetime) };
        db.DevicePairingSessions.Add(row);
        db.AuditEvents.Add(Audit("device_pairing_started", userId, null, context));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return Results.Json(new PairingSessionResponse(row.Id, secret, origin!, row.ExpiresAt), statusCode: 201);
    }

    private static async Task<IResult> PairAsync(PairRequest? request, HttpContext context, VaultDbContext db,
        DevicePairingRateLimiter limiter, TimeProvider clock, CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers.Vary = "Cookie, Authorization";
        if (context.Request.Headers.Cookie.Count > 0 || context.Request.Headers.Authorization.Count > 0)
            return Error(context, 400, "ambient_credentials_not_allowed", "Pairing accepts only the supplied JSON credentials.");
        if (!JsonOnly(context)) return Error(context, 415, "json_required", "Content-Type must be application/json.");
        if (!ValidPairRequest(request)) return Error(context, 400, "invalid_request", "Pairing session, secret, and device name are required.");
        var pairingRequest = request!;
        if (!Guid.TryParseExact(context.Request.Headers["Idempotency-Key"].ToString(), "D", out var idempotencyKey) || idempotencyKey == Guid.Empty)
            return Error(context, 400, "invalid_idempotency_key", "Idempotency-Key must be a UUID.");
        var requestFingerprint = Hash(JsonSerializer.Serialize(new { deviceName = pairingRequest.DeviceName.Trim() }, JsonSerializerOptions.Web));
        var ip = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        if (!limiter.TryRedeem(ip, out var retry))
        {
            context.Response.Headers.RetryAfter = retry.ToString(System.Globalization.CultureInfo.InvariantCulture);
            db.AuditEvents.Add(Audit("device_pairing_rate_limited", null, null, context));
            await db.SaveChangesAsync(ct);
            return Error(context, 429, "rate_limited", "Too many pairing attempts. Try again later.");
        }
        await using var redemptionLock = await limiter.LockRedemptionAsync(pairingRequest.PairingSessionId, ct);
        var now = clock.GetUtcNow().UtcDateTime;
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        var pair = await db.DevicePairingSessions.SingleOrDefaultAsync(x => x.Id == pairingRequest.PairingSessionId, ct);
        if (pair is not null && pair.ConsumedAt is not null && pair.RevokedAt is null && pair.ExpiresAt > now
            && FixedHashEquals(pair.SecretHash, pairingRequest.PairingSecret))
        {
            db.AuditEvents.Add(Audit("device_pairing_replay", pair.UserId, null, context));
            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            return pair.PairingIdempotencyKey == idempotencyKey && pair.PairingRequestFingerprint == requestFingerprint
                ? Error(context, 409, "pairing_consumed", "The pairing session has already been used.")
                : Error(context, 409, "idempotency_conflict", "This idempotency key or pairing session was already used for a different request.");
        }
        if (pair is null || pair.RevokedAt is not null || pair.ExpiresAt <= now
            || !FixedHashEquals(pair.SecretHash, pairingRequest.PairingSecret))
        {
            db.AuditEvents.Add(Audit("device_pairing_failed", pair?.UserId, null, context));
            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            return Error(context, 401, "pairing_invalid", "The pairing session is invalid, expired, or already used.");
        }
        if (!await LockOwnerAsync(db, pair.UserId, ct)) return Error(context, 401, "pairing_invalid", "The pairing session is invalid, expired, or already used.");
        // Re-read after the owner lock to serialize pairing and account-wide revocation.
        await db.Entry(pair).ReloadAsync(ct);
        if (pair.ConsumedAt is not null && pair.RevokedAt is null && pair.ExpiresAt > now && FixedHashEquals(pair.SecretHash, pairingRequest.PairingSecret))
        {
            db.AuditEvents.Add(Audit("device_pairing_replay", pair.UserId, null, context));
            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            return pair.PairingIdempotencyKey == idempotencyKey && pair.PairingRequestFingerprint == requestFingerprint
                ? Error(context, 409, "pairing_consumed", "The pairing session has already been used.")
                : Error(context, 409, "idempotency_conflict", "This idempotency key or pairing session was already used for a different request.");
        }
        if (pair.RevokedAt is not null || pair.ExpiresAt <= now || !FixedHashEquals(pair.SecretHash, pairingRequest.PairingSecret))
            return Error(context, 401, "pairing_invalid", "The pairing session is invalid, expired, or already used.");

        pair.ConsumedAt = now;
        pair.PairingIdempotencyKey = idempotencyKey;
        pair.PairingRequestFingerprint = requestFingerprint;
        var device = new VaultDevice { UserId = pair.UserId, Name = pairingRequest.DeviceName.Trim(), Platform = "android", CreatedAt = now };
        var session = new AndroidSession { UserId = pair.UserId, DeviceId = device.Id, CreatedAt = now, RefreshExpiresAt = now.Add(RefreshLifetime) };
        var access = NewToken("va1_");
        var refresh = NewToken("vr1_");
        db.Devices.Add(device);
        db.AndroidSessions.Add(session);
        db.DeviceCredentials.Add(Credential(session.Id, "access", access, now, now.Add(AccessLifetime)));
        db.DeviceCredentials.Add(Credential(session.Id, "refresh", refresh, now, session.RefreshExpiresAt));
        db.AuditEvents.Add(Audit("device_paired", pair.UserId, device.Id, context));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return Results.Json(new TokenResponse("Bearer", access, now.Add(AccessLifetime), refresh, session.RefreshExpiresAt, pair.UserId, device.Id), statusCode: 201);
    }

    private static async Task<IResult> DeviceLoginAsync(
        DeviceLoginRequest? request,
        HttpContext context,
        VaultDbContext db,
        IPasswordHasher<VaultUser> hasher,
        CredentialAttemptLimiter limiter,
        TimeProvider clock,
        CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers.Vary = "Authorization";
        if (!JsonOnly(context)) return Error(context, 415, "json_required", "Content-Type must be application/json.");
        if (request is null || string.IsNullOrWhiteSpace(request.Identifier) || string.IsNullOrEmpty(request.Password)
            || string.IsNullOrWhiteSpace(request.DeviceName) || request.DeviceName.Trim().Length > 100 || request.DeviceName.Any(char.IsControl))
        {
            return Error(context, 400, "invalid_request", "Identifier, password, and device name are required.");
        }

        var ip = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        if (!limiter.TryConsume(ip, out var retryAfterSeconds))
        {
            context.Response.Headers.RetryAfter = retryAfterSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
            return Error(context, 429, "rate_limited", "Too many sign-in attempts. Try again later.");
        }

        var identifier = request.Identifier.Trim();
        var normalized = identifier.ToUpperInvariant();
        var matches = await db.Users
            .Where(x => x.NormalizedEmail == normalized || x.NormalizedUsername == normalized)
            .Take(2)
            .ToListAsync(ct);
        var user = matches.Count == 1 ? matches[0] : null;
        var hashOwner = user ?? limiter.DummyUser;
        var verification = hasher.VerifyHashedPassword(hashOwner, hashOwner.PasswordHash, request.Password);
        if (user is null || user.Status != "active" || verification == PasswordVerificationResult.Failed)
        {
            db.AuditEvents.Add(new VaultAuditEvent
            {
                EventType = "device_login_failed",
                ActorUserId = user?.Id,
                CorrelationId = context.TraceIdentifier
            });
            await db.SaveChangesAsync(ct);
            return Error(context, 401, "invalid_credentials", "Identifier and password did not match.");
        }

        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, user.Id, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        if (verification == PasswordVerificationResult.SuccessRehashNeeded)
            user.PasswordHash = hasher.HashPassword(user, request.Password);

        var now = clock.GetUtcNow().UtcDateTime;
        var platform = string.IsNullOrWhiteSpace(request.Platform) ? "android" : request.Platform.Trim();
        var device = new VaultDevice
        {
            UserId = user.Id,
            Name = request.DeviceName.Trim(),
            Platform = platform,
            CreatedAt = now
        };
        var session = new AndroidSession
        {
            UserId = user.Id,
            DeviceId = device.Id,
            CreatedAt = now,
            RefreshExpiresAt = now.Add(RefreshLifetime)
        };
        var access = NewToken("va1_");
        var refresh = NewToken("vr1_");
        db.Devices.Add(device);
        db.AndroidSessions.Add(session);
        db.DeviceCredentials.Add(Credential(session.Id, "access", access, now, now.Add(AccessLifetime)));
        db.DeviceCredentials.Add(Credential(session.Id, "refresh", refresh, now, session.RefreshExpiresAt));
        db.AuditEvents.Add(Audit("device_login_succeeded", user.Id, device.Id, context));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);

        return Results.Json(new TokenResponse("Bearer", access, now.Add(AccessLifetime), refresh, session.RefreshExpiresAt, user.Id, device.Id), statusCode: 201);
    }

    private static async Task<IResult> RevokeCurrentDeviceAsync(
        HttpContext context,
        VaultDbContext db,
        TimeProvider clock,
        CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var deviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        var sessionId = IdentityEndpoints.GetGuid(context.User, "sessionId");
        var now = clock.GetUtcNow().UtcDateTime;

        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, userId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");

        if (deviceId != Guid.Empty)
        {
            var device = await db.Devices.SingleOrDefaultAsync(x => x.Id == deviceId && x.UserId == userId, ct);
            if (device is not null && device.RevokedAt is null)
            {
                device.RevokedAt = now;
            }
        }

        AndroidSession? session = null;
        if (sessionId != Guid.Empty)
        {
            session = await db.AndroidSessions.SingleOrDefaultAsync(x => x.Id == sessionId && x.UserId == userId, ct);
        }
        else if (deviceId != Guid.Empty)
        {
            session = await db.AndroidSessions.SingleOrDefaultAsync(x => x.DeviceId == deviceId && x.UserId == userId && x.RevokedAt == null, ct);
        }

        if (session is not null && session.RevokedAt is null)
        {
            session.RevokedAt = now;
            await db.DeviceCredentials.Where(x => x.SessionId == session.Id && x.RevokedAt == null)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
        }

        db.AuditEvents.Add(Audit("device_revoked", userId, deviceId, context));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);

        if (context.User.Identity?.AuthenticationType == IdentityEndpoints.Scheme)
            await context.SignOutAsync(IdentityEndpoints.Scheme);

        return Results.NoContent();
    }

    private static async Task<IResult> RefreshAsync(RefreshRequest? request, HttpContext context, VaultDbContext db, TimeProvider clock, CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Headers.Vary = "Cookie, Authorization";
        if (context.Request.Headers.Cookie.Count > 0 || context.Request.Headers.Authorization.Count > 0)
            return Error(context, 400, "ambient_credentials_not_allowed", "Refresh accepts only the supplied JSON credential.");
        if (!JsonOnly(context)) return Error(context, 415, "json_required", "Content-Type must be application/json.");
        if (request is null || !ValidToken(request.RefreshToken, "vr1_", 47))
            return Error(context, 400, "invalid_request", "A refresh token is required.");
        var now = clock.GetUtcNow().UtcDateTime;
        var tokenHash = Hash(request.RefreshToken);
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        var credential = await db.DeviceCredentials.SingleOrDefaultAsync(x => x.TokenHash == tokenHash && x.TokenType == "refresh", ct);
        if (credential is null) return Error(context, 401, "invalid_refresh_token", "The refresh token is invalid or expired.");
        var session = await db.AndroidSessions.SingleAsync(x => x.Id == credential.SessionId, ct);
        if (!await LockOwnerAsync(db, session.UserId, ct)) return Error(context, 401, "invalid_refresh_token", "The refresh token is invalid or expired.");
        // Re-read after serialization on the account row so concurrent refreshes cannot both rotate one token.
        await db.Entry(credential).ReloadAsync(ct);
        await db.Entry(session).ReloadAsync(ct);
        if (credential.RevokedAt is not null || session.RevokedAt is not null || credential.ExpiresAt <= now || session.RefreshExpiresAt <= now)
        {
            // Reuse of a previously rotated refresh credential revokes the whole Android session.
            if (credential.RevokedAt is not null && session.RevokedAt is null)
            {
                session.RevokedAt = now;
                await db.DeviceCredentials.Where(x => x.SessionId == session.Id && x.RevokedAt == null).ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
                var device = await db.Devices.SingleAsync(x => x.Id == session.DeviceId, ct);
                device.RevokedAt = now;
                db.AuditEvents.Add(Audit("android_refresh_reuse_detected", session.UserId, session.DeviceId, context));
                await db.SaveChangesAsync(ct);
                await transaction.CommitAsync(ct);
            }
            return Error(context, 401, "invalid_refresh_token", "The refresh token is invalid or expired.");
        }
        credential.RevokedAt = now;
        await db.DeviceCredentials.Where(x => x.SessionId == session.Id && x.TokenType == "access" && x.RevokedAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
        session.RefreshExpiresAt = now.Add(RefreshLifetime);
        var access = NewToken("va1_");
        var refresh = NewToken("vr1_");
        db.DeviceCredentials.Add(Credential(session.Id, "access", access, now, now.Add(AccessLifetime)));
        db.DeviceCredentials.Add(Credential(session.Id, "refresh", refresh, now, session.RefreshExpiresAt));
        db.AuditEvents.Add(Audit("android_token_refreshed", session.UserId, session.DeviceId, context));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        return Results.Ok(new TokenResponse("Bearer", access, now.Add(AccessLifetime), refresh, session.RefreshExpiresAt, session.UserId, session.DeviceId));
    }

    private static async Task<IResult> RevokeAllAsync(HttpContext context, VaultDbContext db, TimeProvider clock, CancellationToken ct)
    {
        context.Response.Headers.CacheControl = "no-store";
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var now = clock.GetUtcNow().UtcDateTime;
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        if (!await LockOwnerAsync(db, userId, ct)) return Error(context, 401, "unauthorized", "Authentication is required.");
        await db.Devices.Where(x => x.UserId == userId && x.RevokedAt == null).ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
        await db.AndroidSessions.Where(x => x.UserId == userId && x.RevokedAt == null).ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
        await db.DeviceCredentials.Where(x => x.SessionId != Guid.Empty && db.AndroidSessions.Any(session => session.Id == x.SessionId && session.UserId == userId) && x.RevokedAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
        await db.DevicePairingSessions.Where(x => x.UserId == userId && x.ConsumedAt == null && x.RevokedAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAt, now), ct);
        db.AuditEvents.Add(Audit("sessions_revoked_all", userId, IdentityEndpoints.GetGuid(context.User, "deviceId"), context));
        await db.SaveChangesAsync(ct);
        await transaction.CommitAsync(ct);
        if (context.User.Identity?.AuthenticationType == IdentityEndpoints.Scheme)
            await context.SignOutAsync(IdentityEndpoints.Scheme);
        return Results.NoContent();
    }

    private static bool JsonOnly(HttpContext context) => context.Request.ContentType?.Split(';', 2)[0].Trim().Equals("application/json", StringComparison.OrdinalIgnoreCase) == true;
    private static bool IsOrigin(string? value) => Uri.TryCreate(value, UriKind.Absolute, out var uri) && (uri.Scheme is "https" or "http") && uri.AbsolutePath == "/" && uri.UserInfo.Length == 0 && uri.Query.Length == 0 && uri.Fragment.Length == 0;
    private static bool ValidPairRequest(PairRequest? r) => r is not null && r.PairingSessionId != Guid.Empty && ValidToken(r.PairingSecret, "", 43) && !string.IsNullOrWhiteSpace(r.DeviceName) && r.DeviceName.Trim().Length <= 100 && !r.DeviceName.Any(char.IsControl);
    private static bool ValidToken(string token, string prefix, int expectedLength) => token is not null && token.Length == expectedLength && token.StartsWith(prefix, StringComparison.Ordinal)
        && token.Skip(prefix.Length).All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_');
    private static async Task<bool> LockOwnerAsync(VaultDbContext db, Guid id, CancellationToken ct) => id != Guid.Empty && await db.Users.Where(x => x.Id == id && x.Status == "active").ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct) == 1;
    private static string NewToken(string prefix) => prefix + Base64Url(RandomNumberGenerator.GetBytes(32));
    private static string Base64Url(byte[] value) => Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    private static bool FixedHashEquals(string expected, string supplied) => CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(expected), Encoding.ASCII.GetBytes(Hash(supplied)));
    private static DeviceCredential Credential(Guid session, string type, string token, DateTime now, DateTime expiry) => new() { SessionId = session, TokenType = type, TokenHash = Hash(token), CreatedAt = now, ExpiresAt = expiry };
    private static VaultAuditEvent Audit(string type, Guid? userId, Guid? deviceId, HttpContext context) => new() { EventType = type, ActorUserId = userId, ActorDeviceId = deviceId, CorrelationId = context.TraceIdentifier };
    private static IResult Error(HttpContext context, int status, string code, string message) => IdentityErrors.Create(context, status, code, message);

    private static async Task<IResult> GetActiveDevicesAsync(HttpContext context, VaultDbContext db, CancellationToken ct)
    {
        var userId = IdentityEndpoints.GetGuid(context.User, "userId");
        var currentDeviceId = IdentityEndpoints.GetGuid(context.User, "deviceId");
        if (userId == Guid.Empty)
            return Error(context, 401, "unauthorized", "Authentication is required.");

        var devices = await db.Devices.AsNoTracking()
            .Where(d => d.UserId == userId && d.RevokedAt == null)
            .OrderByDescending(d => d.CreatedAt)
            .Select(d => new DeviceSummary(
                d.Id,
                d.Name,
                d.Platform,
                d.CreatedAt,
                d.Id == currentDeviceId
            ))
            .ToListAsync(ct);

        return Results.Ok(new DeviceListResponse(devices));
    }

    internal sealed record DeviceSummary(Guid Id, string Name, string Platform, DateTime CreatedAt, bool IsCurrent);
    internal sealed record DeviceListResponse(IReadOnlyList<DeviceSummary> Devices);
    internal sealed record DeviceLoginRequest(string Identifier, string Password, string DeviceName, string? Platform = "android");
    internal sealed record PairRequest(Guid PairingSessionId, string PairingSecret, string DeviceName);
    internal sealed record RefreshRequest(string RefreshToken);
    internal sealed record PairingSessionResponse(Guid PairingSessionId, string PairingSecret, string ServerOrigin, DateTime ExpiresAt);
    internal sealed record TokenResponse(string TokenType, string AccessToken, DateTime AccessTokenExpiresAt, string RefreshToken, DateTime RefreshTokenExpiresAt, Guid UserId, Guid DeviceId);
}

internal sealed class DevicePairingRateLimiter
{
    private readonly object gate = new();
    private readonly Dictionary<Guid, RedemptionGate> redemptionGates = new();
    private readonly Dictionary<Guid, (DateTimeOffset Window, int Count)> owners = new();
    private readonly Dictionary<string, (DateTimeOffset Window, int Count)> ips = new(StringComparer.Ordinal);
    private DateTimeOffset createWindow = DateTimeOffset.UtcNow, redeemWindow = DateTimeOffset.UtcNow;
    private int createCount, redeemCount;
    private static readonly TimeSpan WindowLength = TimeSpan.FromMinutes(10);
    public async ValueTask<IAsyncDisposable> LockRedemptionAsync(Guid pairingSessionId, CancellationToken ct)
    {
        RedemptionGate entry;
        lock (gate)
        {
            if (redemptionGates.Count >= 256)
            {
                foreach (var key in redemptionGates.Where(pair => pair.Value.References == 0).OrderBy(pair => pair.Value.LastUsed)
                    .Take(redemptionGates.Count - 192).Select(pair => pair.Key).ToArray())
                    redemptionGates.Remove(key);
            }
            if (!redemptionGates.TryGetValue(pairingSessionId, out entry!))
                redemptionGates[pairingSessionId] = entry = new RedemptionGate();
            entry.References++;
        }
        try
        {
            await entry.Semaphore.WaitAsync(ct);
            return new SemaphoreLease(this, pairingSessionId, entry);
        }
        catch
        {
            ReleaseReference(pairingSessionId, entry, releaseSemaphore: false);
            throw;
        }
    }
    public bool TryCreate(string ip, Guid owner, out int retrySeconds)
    {
        lock (gate)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - createWindow >= WindowLength) { createWindow = now; createCount = 0; owners.Clear(); }
            if (!owners.TryGetValue(owner, out var item) || now - item.Window >= WindowLength) item = (now, 0);
            if (item.Count >= 5 || createCount >= 30)
            {
                var reset = createCount >= 30 ? createWindow + WindowLength : item.Window + WindowLength;
                retrySeconds = Math.Max(1, (int)Math.Ceiling((reset - now).TotalSeconds));
                return false;
            }
            owners[owner] = (item.Window, item.Count + 1);
            createCount++;
            retrySeconds = 0;
            return true;
        }
    }

    public bool TryRedeem(string ip, out int retrySeconds)
    {
        lock (gate)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - redeemWindow >= WindowLength) { redeemWindow = now; redeemCount = 0; ips.Clear(); }
            if (!ips.TryGetValue(ip, out var item) || now - item.Window >= WindowLength) item = (now, 0);
            if (item.Count >= 10 || redeemCount >= 60)
            {
                var reset = redeemCount >= 60 ? redeemWindow + WindowLength : item.Window + WindowLength;
                retrySeconds = Math.Max(1, (int)Math.Ceiling((reset - now).TotalSeconds));
                return false;
            }
            ips[ip] = (item.Window, item.Count + 1);
            redeemCount++;
            retrySeconds = 0;
            return true;
        }
    }

    private void ReleaseReference(Guid id, RedemptionGate entry, bool releaseSemaphore)
    {
        if (releaseSemaphore) entry.Semaphore.Release();
        lock (gate)
        {
            entry.References--;
            entry.LastUsed = DateTimeOffset.UtcNow;
        }
    }

    private sealed class RedemptionGate
    {
        public SemaphoreSlim Semaphore { get; } = new(1, 1);
        public int References { get; set; }
        public DateTimeOffset LastUsed { get; set; } = DateTimeOffset.UtcNow;
    }

    private sealed class SemaphoreLease(DevicePairingRateLimiter owner, Guid id, RedemptionGate entry) : IAsyncDisposable
    {
        public ValueTask DisposeAsync()
        {
            owner.ReleaseReference(id, entry, releaseSemaphore: true);
            return ValueTask.CompletedTask;
        }
    }
}
