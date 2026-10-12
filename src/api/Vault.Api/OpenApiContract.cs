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
        document.Info.Description = "Browser authentication uses the Secure, HttpOnly, SameSite=Strict __Host-Vault.Auth cookie. Login and logout require a matching Origin from Security:AllowedOrigins and a CSRF token in the X-CSRF-TOKEN header.";
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes ??= new Dictionary<string, IOpenApiSecurityScheme>(StringComparer.Ordinal);
        document.Components.SecuritySchemes["VaultCookie"] = new OpenApiSecurityScheme
        {
            Type = SecuritySchemeType.ApiKey,
            In = ParameterLocation.Cookie,
            Name = "__Host-Vault.Auth",
            Description = "Eight-hour web session cookie. Mutating browser requests require an allowed Origin and CSRF token."
        };

        var scheme = new OpenApiSecuritySchemeReference("VaultCookie", document);
        foreach (var (path, pathItem) in document.Paths ?? new OpenApiPaths())
        foreach (var (method, operation) in pathItem.Operations!)
        {
            if (path is "/api/v1/auth/me" or "/api/v1/auth/logout" || path.StartsWith("/api/v1/items", StringComparison.Ordinal))
            {
                operation.Security ??= [];
                operation.Security.Add(new OpenApiSecurityRequirement { [scheme] = [] });
            }
            if (path is "/api/v1/auth/login" or "/api/v1/auth/logout" || path.StartsWith("/api/v1/items", StringComparison.Ordinal))
                operation.Description = $"{operation.Description} Requires an allowed Origin header and the antiforgery cookie plus X-CSRF-TOKEN header.";
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
            var isItemMutation = method.ToString().Equals("POST", StringComparison.OrdinalIgnoreCase)
                || method.ToString().Equals("DELETE", StringComparison.OrdinalIgnoreCase);
            if (path.StartsWith("/api/v1/items", StringComparison.Ordinal) && isItemMutation)
            {
                operation.Parameters ??= [];
                operation.Parameters.Add(new OpenApiParameter
                {
                    Name = "Origin", In = ParameterLocation.Header, Required = true,
                    Description = "Must exactly match one configured allowed origin.",
                    Schema = new OpenApiSchema { Type = JsonSchemaType.String }
                });
                operation.Parameters.Add(new OpenApiParameter
                {
                    Name = "X-CSRF-TOKEN", In = ParameterLocation.Header, Required = true,
                    Description = "Request token paired with the __Host-Vault.Csrf cookie.",
                    Schema = new OpenApiSchema { Type = JsonSchemaType.String }
                });
            }
        }
        return Task.CompletedTask;
    }
}
