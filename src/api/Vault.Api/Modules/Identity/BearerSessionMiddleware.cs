using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Identity;

internal sealed class BearerSessionMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context, VaultDbContext db)
    {
        var hasCookie = context.Request.Headers.Cookie.Count > 0;
        var authorization = context.Request.Headers.Authorization.ToString();
        var hasAuthorization = !string.IsNullOrWhiteSpace(authorization);
        if (hasCookie && hasAuthorization)
        {
            await IdentityErrors.Create(context, 400, "mixed_credentials", "Use either a browser session or a bearer token.").ExecuteAsync(context);
            return;
        }

        if (hasAuthorization)
        {
            if (!authorization.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || authorization.Length <= 7)
            {
                await IdentityErrors.Create(context, 401, "invalid_token", "A valid bearer token is required.").ExecuteAsync(context);
                return;
            }
            var token = authorization[7..].Trim();
            if (!token.StartsWith("va1_", StringComparison.Ordinal) || token.Length != 47)
            {
                await IdentityErrors.Create(context, 401, "invalid_token", "A valid bearer token is required.").ExecuteAsync(context);
                return;
            }
            var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))).ToLowerInvariant();
            var now = DateTime.UtcNow;
            var match = await (from credential in db.DeviceCredentials.AsNoTracking()
                               join session in db.AndroidSessions.AsNoTracking() on credential.SessionId equals session.Id
                               join device in db.Devices.AsNoTracking() on session.DeviceId equals device.Id
                               join user in db.Users.AsNoTracking() on session.UserId equals user.Id
                               where credential.TokenHash == hash && credential.TokenType == "access"
                                   && credential.RevokedAt == null && credential.ExpiresAt > now
                                   && session.RevokedAt == null && device.RevokedAt == null && user.Status == "active"
                               select new { user.Id, session.DeviceId }).SingleOrDefaultAsync(context.RequestAborted);
            if (match is null)
            {
                await IdentityErrors.Create(context, 401, "invalid_token", "A valid bearer token is required.").ExecuteAsync(context);
                return;
            }
            var claims = new[] { new Claim("userId", match.Id.ToString()), new Claim("deviceId", match.DeviceId.ToString()), new Claim(ClaimTypes.NameIdentifier, match.Id.ToString()) };
            context.User = new ClaimsPrincipal(new ClaimsIdentity(claims, "VaultBearer"));
        }
        await next(context);
    }
}
