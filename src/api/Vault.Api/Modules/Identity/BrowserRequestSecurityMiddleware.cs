using Microsoft.AspNetCore.Antiforgery;

namespace Vault.Api.Modules.Identity;

internal sealed class BrowserRequestSecurityMiddleware(RequestDelegate next, IConfiguration configuration, IAntiforgery antiforgery)
{
    public async Task InvokeAsync(HttpContext context)
    {
        context.Response.Headers["X-Frame-Options"] = "DENY";
        context.Response.Headers["Content-Security-Policy"] = "frame-ancestors 'none'; default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self';";
        context.Response.Headers["X-Content-Type-Options"] = "nosniff";

        var path = context.Request.Path;
        var isLogin = string.Equals(path.Value, "/api/v1/auth/login", StringComparison.OrdinalIgnoreCase);
        if (path.StartsWithSegments("/api/v1/items", StringComparison.OrdinalIgnoreCase))
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.Vary = "Cookie";
        }
        var isUnsafeApiRequest = path.StartsWithSegments("/api/v1", StringComparison.OrdinalIgnoreCase)
            && !HttpMethods.IsGet(context.Request.Method)
            && !HttpMethods.IsHead(context.Request.Method)
            && !HttpMethods.IsOptions(context.Request.Method);
        var hasCookieSession = context.Request.Cookies.ContainsKey(IdentityEndpoints.CookieName);
        if (path.StartsWithSegments("/api/v1/auth", StringComparison.OrdinalIgnoreCase))
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.Vary = "Cookie";
        }
        var requiresBrowserProtection = isLogin || (isUnsafeApiRequest && hasCookieSession);
        if (!requiresBrowserProtection)
        {
            await next(context);
            return;
        }

        var origins = configuration.GetSection("Security:AllowedOrigins").Get<string[]>() ?? [];
        var originHeaders = context.Request.Headers.Origin;
        if (originHeaders.Count != 1 || !IsAllowed(originHeaders[0], origins))
        {
            await IdentityErrors.Create(context, StatusCodes.Status403Forbidden, "invalid_origin", "A valid allowed Origin header is required.").ExecuteAsync(context);
            return;
        }

        // Login always needs CSRF protection; future cookie-authenticated mutations inherit it here.
        if (isLogin || hasCookieSession)
        {
            try
            {
                await antiforgery.ValidateRequestAsync(context);
            }
            catch (AntiforgeryValidationException)
            {
                await IdentityErrors.Create(context, StatusCodes.Status403Forbidden, "csrf_invalid", "The CSRF token is missing or invalid.").ExecuteAsync(context);
                return;
            }
        }

        await next(context);
    }

    private static bool IsAllowed(string? rawOrigin, IEnumerable<string> allowedOrigins)
    {
        if (string.IsNullOrWhiteSpace(rawOrigin) || !Uri.TryCreate(rawOrigin, UriKind.Absolute, out var origin)) return false;
        if ((origin.Scheme != Uri.UriSchemeHttps && origin.Scheme != Uri.UriSchemeHttp)
            || origin.UserInfo.Length > 0 || origin.Query.Length > 0 || origin.Fragment.Length > 0 || origin.AbsolutePath != "/") return false;
        return allowedOrigins.Any(value => Uri.TryCreate(value, UriKind.Absolute, out var allowed)
            && string.Equals(origin.Scheme, allowed.Scheme, StringComparison.OrdinalIgnoreCase)
            && string.Equals(origin.Host, allowed.Host, StringComparison.OrdinalIgnoreCase)
            && origin.Port == allowed.Port
            && allowed.AbsolutePath == "/" && allowed.Query.Length == 0 && allowed.Fragment.Length == 0 && allowed.UserInfo.Length == 0);
    }
}
