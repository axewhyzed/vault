using System.Security.Claims;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Identity;

internal static class IdentityEndpoints
{
    public const string Scheme = "VaultCookie";
    public const string CookieName = "__Host-Vault.Auth";

    public static IEndpointRouteBuilder MapIdentityEndpoints(this IEndpointRouteBuilder endpoints)
    {
        var group = endpoints.MapGroup("/api/v1/auth").WithTags("Authentication");
        group.MapGet("/csrf", (HttpContext context, IAntiforgery antiforgery) =>
            {
                context.Response.Headers.CacheControl = "no-store";
                var tokens = antiforgery.GetAndStoreTokens(context);
                return Results.Ok(new CsrfResponse(tokens.RequestToken!, "X-CSRF-TOKEN"));
            })
            .AllowAnonymous()
            .WithName("GetCsrfToken")
            .WithSummary("Issue a CSRF token for login and logout requests.");

        group.MapPost("/login", LoginAsync)
            .AllowAnonymous()
            .WithName("Login")
            .WithSummary("Sign in with an email address or username and password.")
            .WithDescription("Requires an allowed Origin header, the antiforgery cookie, and the X-CSRF-TOKEN request header. Success sets the Secure, HttpOnly, SameSite=Strict __Host-Vault.Auth cookie, valid for eight hours.");

        group.MapGet("/me", async (ClaimsPrincipal principal, VaultDbContext database, CancellationToken cancellationToken) =>
            {
                var userId = GetGuid(principal, "userId");
                var deviceId = GetGuid(principal, "deviceId");
                var account = await database.Users.AsNoTracking().SingleAsync(x => x.Id == userId, cancellationToken);
                return Results.Ok(new AccountResponse(account.Id, account.Email, deviceId, account.Username));
            })
            .RequireAuthorization()
            .WithName("GetCurrentAccount");

        group.MapPost("/logout", LogoutAsync)
            .RequireAuthorization()
            .WithName("Logout")
            .WithSummary("Revoke the current web device and clear its authentication cookie.")
            .WithDescription("Requires an allowed Origin header, the antiforgery cookie, and the X-CSRF-TOKEN request header.");

        return endpoints;
    }

    private static async Task<IResult> LoginAsync(
        LoginRequest? request,
        HttpContext context,
        VaultDbContext database,
        IPasswordHasher<VaultUser> hasher,
        CredentialAttemptLimiter limiter,
        CancellationToken cancellationToken)
    {
        var identifier = request?.GetIdentifier()?.Trim();
        if (request is null || string.IsNullOrWhiteSpace(identifier) || string.IsNullOrEmpty(request.Password))
            return IdentityErrors.Create(context, StatusCodes.Status400BadRequest, "invalid_request", "Email or username and password are required.");

        var ip = context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        if (!limiter.TryConsume(ip, out var retryAfterSeconds))
        {
            context.Response.Headers.RetryAfter = retryAfterSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
            return IdentityErrors.Create(context, StatusCodes.Status429TooManyRequests, "rate_limited", "Too many sign-in attempts. Try again later.");
        }

        var normalized = identifier.ToUpperInvariant();
        var matches = await database.Users
            .Where(x => x.NormalizedEmail == normalized || x.NormalizedUsername == normalized)
            .Take(2)
            .ToListAsync(cancellationToken);
        var user = matches.Count == 1 ? matches[0] : null;
        var hashOwner = user ?? limiter.DummyUser;
        var verification = hasher.VerifyHashedPassword(hashOwner, hashOwner.PasswordHash, request.Password);
        if (user is null || user.Status != "active" || verification == PasswordVerificationResult.Failed)
        {
            database.AuditEvents.Add(new VaultAuditEvent { EventType = "login_failed", ActorUserId = user?.Id, CorrelationId = context.TraceIdentifier });
            await database.SaveChangesAsync(cancellationToken);
            return IdentityErrors.Create(context, StatusCodes.Status401Unauthorized, "invalid_credentials", "Email or username and password did not match.");
        }

        await using var transaction = await database.Database.BeginTransactionAsync(cancellationToken);
        if (!await LockOwnerAsync(database, user.Id, cancellationToken))
            return IdentityErrors.Create(context, StatusCodes.Status401Unauthorized, "unauthorized", "Authentication is required.");
        if (verification == PasswordVerificationResult.SuccessRehashNeeded)
            user.PasswordHash = hasher.HashPassword(user, request.Password);

        var device = new VaultDevice { UserId = user.Id };
        database.Devices.Add(device);
        database.AuditEvents.Add(new VaultAuditEvent
        {
            EventType = "login_succeeded",
            ActorUserId = user.Id,
            ActorDeviceId = device.Id,
            CorrelationId = context.TraceIdentifier
        });
        await database.SaveChangesAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);

        var claims = new[]
        {
            new Claim("userId", user.Id.ToString()),
            new Claim("deviceId", device.Id.ToString()),
            new Claim(ClaimTypes.NameIdentifier, user.Id.ToString())
        };
        var identity = new ClaimsIdentity(claims, IdentityEndpoints.Scheme);
        await context.SignInAsync(IdentityEndpoints.Scheme, new ClaimsPrincipal(identity), new AuthenticationProperties
        {
            IsPersistent = true,
            IssuedUtc = DateTimeOffset.UtcNow,
            ExpiresUtc = DateTimeOffset.UtcNow.AddHours(8),
            AllowRefresh = false
        });

        return Results.Ok(new AccountResponse(user.Id, user.Email, device.Id, user.Username));
    }

    private static async Task<IResult> LogoutAsync(
        HttpContext context,
        VaultDbContext database,
        CancellationToken cancellationToken)
    {
        if (context.User.Identity?.AuthenticationType != Scheme)
            return IdentityErrors.Create(context, StatusCodes.Status403Forbidden, "cookie_session_required", "Logout requires a browser session.");
        var userId = GetGuid(context.User, "userId");
        var deviceId = GetGuid(context.User, "deviceId");
        await using (var transaction = await database.Database.BeginTransactionAsync(cancellationToken))
        {
            if (await LockOwnerAsync(database, userId, cancellationToken))
            {
                var device = await database.Devices.SingleOrDefaultAsync(x => x.Id == deviceId && x.UserId == userId, cancellationToken);
                if (device is not null && device.RevokedAt is null)
                {
                    device.RevokedAt = DateTime.UtcNow;
                    database.AuditEvents.Add(new VaultAuditEvent
                    {
                        EventType = "logout",
                        ActorUserId = userId,
                        ActorDeviceId = deviceId,
                        CorrelationId = context.TraceIdentifier
                    });
                    await database.SaveChangesAsync(cancellationToken);
                }
            }
            await transaction.CommitAsync(cancellationToken);
        }
        await context.SignOutAsync(IdentityEndpoints.Scheme);
        return Results.NoContent();
    }

    internal static Guid GetGuid(ClaimsPrincipal principal, string claim) =>
        Guid.TryParse(principal.FindFirstValue(claim), out var value) ? value : Guid.Empty;

    internal static async Task<bool> LockOwnerAsync(VaultDbContext database, Guid userId, CancellationToken cancellationToken) =>
        userId != Guid.Empty && await database.Users.Where(user => user.Id == userId && user.Status == "active")
            .ExecuteUpdateAsync(setters => setters.SetProperty(user => user.Status, user => user.Status), cancellationToken) == 1;

    internal sealed record LoginRequest(string Password, string? Identifier = null, string? Email = null, string? Username = null)
    {
        public string? GetIdentifier() => !string.IsNullOrWhiteSpace(Identifier)
            ? Identifier
            : !string.IsNullOrWhiteSpace(Username) ? Username : Email;
    }
    internal sealed record AccountResponse(Guid UserId, string Email, Guid DeviceId, string? Username = null);
    internal sealed record CsrfResponse(string Token, string HeaderName);
}

internal static class IdentityErrors
{
    public static IResult Create(HttpContext context, int status, string code, string message) =>
        Results.Json(new { code, message, correlationId = context.TraceIdentifier }, statusCode: status);
}

internal sealed class CredentialAttemptLimiter
{
    private readonly object _gate = new();
    private readonly Dictionary<string, (DateTimeOffset Start, int Count)> _byIp = new(StringComparer.Ordinal);
    private DateTimeOffset _globalStart = DateTimeOffset.UtcNow;
    private int _globalCount;
    private static readonly TimeSpan Window = TimeSpan.FromMinutes(15);
    public VaultUser DummyUser { get; }

    public CredentialAttemptLimiter(IPasswordHasher<VaultUser> hasher)
    {
        DummyUser = new VaultUser { PasswordHash = hasher.HashPassword(new VaultUser(), "nonexistent-account-dummy-password") };
    }

    public bool TryConsume(string ip, out int retryAfterSeconds)
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            if (now - _globalStart >= Window) { _globalStart = now; _globalCount = 0; }
            if (!_byIp.TryGetValue(ip, out var bucket) || now - bucket.Start >= Window) bucket = (now, 0);
            var globalBlocked = _globalCount >= 30;
            var ipBlocked = bucket.Count >= 5;
            if (globalBlocked || ipBlocked)
            {
                var resetAt = globalBlocked ? _globalStart + Window : bucket.Start + Window;
                retryAfterSeconds = Math.Max(1, (int)Math.Ceiling((resetAt - now).TotalSeconds));
                return false;
            }
            _globalCount++;
            _byIp[ip] = (bucket.Start, bucket.Count + 1);
            foreach (var key in _byIp.Where(pair => now - pair.Value.Start >= Window).Select(pair => pair.Key).ToArray()) _byIp.Remove(key);
            if (_byIp.Count > 4096)
            {
                var excess = _byIp.Count - 4096;
                foreach (var key in _byIp.OrderBy(pair => pair.Value.Start).Take(excess).Select(pair => pair.Key).ToArray()) _byIp.Remove(key);
            }
            retryAfterSeconds = 0;
            return true;
        }
    }
}
