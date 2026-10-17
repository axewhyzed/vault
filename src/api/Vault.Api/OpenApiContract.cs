using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace Vault.Api;

internal static class OpenApiContract
{
    public static Task TransformAsync(OpenApiDocument document, OpenApiDocumentTransformerContext context, CancellationToken cancellationToken)
    {
        document.Info ??= new OpenApiInfo();
        document.Info.Title = "Vault API";
        document.Info.Version = "1.0.0";
        document.Info.Description = "Browser authentication uses the Secure, HttpOnly, SameSite=Strict __Host-Vault.Auth cookie. Android requests use short lived Bearer access tokens backed by a database lookup on every request. Cookie-authenticated mutations require a matching Origin from Security:AllowedOrigins and a CSRF token in the X-CSRF-TOKEN header.";
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes ??= new Dictionary<string, IOpenApiSecurityScheme>(StringComparer.Ordinal);
        document.Components.SecuritySchemes["VaultCookie"] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.ApiKey,
            In = ParameterLocation.Cookie,
            Name = "__Host-Vault.Auth",
            Description = "Eight-hour web session cookie. Mutating browser requests require an allowed Origin and CSRF token."
        };
        document.Components.SecuritySchemes["VaultBearer"] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.Http,
            Scheme = "bearer",
            BearerFormat = "va1_…",
            Description = "Fifteen minute Android access token. Each request checks the token hash and session state in the database."
        };

        var cookieScheme = new OpenApiSecuritySchemeReference("VaultCookie", document);
        var bearerScheme = new OpenApiSecuritySchemeReference("VaultBearer", document);
        foreach (var (path, pathItem) in document.Paths ?? new OpenApiPaths())
        foreach (var (method, operation) in pathItem.Operations!)
        {
            var isUnsafeOperation = method.ToString() is "POST" or "PUT" or "PATCH" or "DELETE";
            if (path is "/api/v1/auth/me" || path is "/api/v1/devices" || path is "/api/v1/devices/{id}" || path.StartsWith("/api/v1/items", StringComparison.Ordinal) || path.StartsWith("/api/v1/uploads", StringComparison.Ordinal))
            {
                operation.Security ??= [];
                operation.Security.Add(new OpenApiSecurityRequirement { [cookieScheme] = [] });
                operation.Security.Add(new OpenApiSecurityRequirement { [bearerScheme] = [] });
            }
            if (path is "/api/v1/auth/logout") operation.Security = [new OpenApiSecurityRequirement { [cookieScheme] = [] }];
            if (path is "/api/v1/auth/sessions/revoke-all" or "/api/v1/devices/revoke-current" or "/api/v1/devices/{id}")
            {
                operation.Security = [new OpenApiSecurityRequirement { [cookieScheme] = [] }, new OpenApiSecurityRequirement { [bearerScheme] = [] }];
                operation.Description = $"{operation.Description} Browser-cookie requests require an allowed Origin header and the antiforgery cookie plus X-CSRF-TOKEN header. Bearer requests do not use ambient browser credentials.";
            }
            if (path is "/api/v1/devices/pairing-sessions")
            {
                operation.Security = [new OpenApiSecurityRequirement { [cookieScheme] = [] }];
                operation.Description = $"{operation.Description} Requires an allowed Origin header and the antiforgery cookie plus X-CSRF-TOKEN header.";
            }
            if (path is "/api/v1/auth/login" or "/api/v1/auth/logout" || isUnsafeOperation && (path.StartsWith("/api/v1/items", StringComparison.Ordinal) || path.StartsWith("/api/v1/uploads", StringComparison.Ordinal) || path is "/api/v1/devices/{id}" || path is "/api/v1/devices/pairing-sessions"))
                operation.Description = $"{operation.Description} Cookie-authenticated requests require an allowed Origin header and the antiforgery cookie plus X-CSRF-TOKEN header.";
            if (path is "/api/v1/devices/pair")
            {
                operation.Parameters ??= [];
                operation.Parameters.Add(new OpenApiParameter { Name = "Idempotency-Key", In = ParameterLocation.Header, Required = true,
                    Description = "UUID identifying this pairing attempt.", Schema = new OpenApiSchema { Type = JsonSchemaType.String, Format = "uuid" } });
            }
            if (isUnsafeOperation && (path is "/api/v1/auth/sessions/revoke-all" or "/api/v1/devices/revoke-current" or "/api/v1/devices/pairing-sessions" or "/api/v1/auth/logout" or "/api/v1/devices/{id}" || path.StartsWith("/api/v1/items", StringComparison.Ordinal) || path.StartsWith("/api/v1/uploads", StringComparison.Ordinal)))
            {
                operation.Parameters ??= [];
                operation.Parameters.Add(new OpenApiParameter { Name = "Origin", In = ParameterLocation.Header, Required = path is "/api/v1/devices/pairing-sessions" or "/api/v1/auth/logout",
                    Description = "Required for browser-cookie authenticated mutations and must exactly match a configured allowed origin.", Schema = new OpenApiSchema { Type = JsonSchemaType.String } });
                operation.Parameters.Add(new OpenApiParameter { Name = "X-CSRF-TOKEN", In = ParameterLocation.Header, Required = path is "/api/v1/devices/pairing-sessions" or "/api/v1/auth/logout",
                    Description = "Required for browser-cookie authenticated mutations; paired with the __Host-Vault.Csrf cookie.", Schema = new OpenApiSchema { Type = JsonSchemaType.String } });
            }
            if (path is "/api/v1/items/text" or "/api/v1/items/url")
            {
                operation.Parameters ??= [];
                operation.Parameters.Add(new OpenApiParameter
                {
                    Name = "Idempotency-Key", In = ParameterLocation.Header, Required = true,
                    Description = "UUID identifying this create intent; reuse it only to retry the identical request.",
                    Schema = new OpenApiSchema { Type = JsonSchemaType.String, Format = "uuid" }
                });
            }
            if (path is "/api/v1/uploads/initiate")
            {
                operation.Parameters ??= [];
                operation.Parameters.Add(new OpenApiParameter { Name = "Idempotency-Key", In = ParameterLocation.Header, Required = true,
                    Description = "UUID identifying this upload intent; reuse it only for the same upload metadata.",
                    Schema = new OpenApiSchema { Type = JsonSchemaType.String, Format = "uuid" } });
            }
        }
        return Task.CompletedTask;
    }
}
