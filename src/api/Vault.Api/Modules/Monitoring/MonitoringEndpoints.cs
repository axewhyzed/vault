using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Monitoring;

internal static class MonitoringEndpoints
{
    public static IEndpointRouteBuilder MapMonitoringEndpoints(this IEndpointRouteBuilder endpoints)
    {
        endpoints.MapGet("/health/live", () => Results.Ok(new { status = "live" }))
            .AllowAnonymous()
            .WithName("Liveness");

        endpoints.MapGet("/health/ready", async (VaultDbContext database, CancellationToken cancellationToken) =>
        {
            try
            {
                if (!await database.Database.CanConnectAsync(cancellationToken)
                    || (await database.Database.GetPendingMigrationsAsync(cancellationToken)).Any())
                {
                    return Results.Problem(
                        title: "Service is not ready",
                        detail: "A required dependency is unavailable.",
                        statusCode: StatusCodes.Status503ServiceUnavailable);
                }

                return Results.Ok(new { status = "ready" });
            }
            catch (Exception)
            {
                return Results.Problem(
                    title: "Service is not ready",
                    detail: "A required dependency is unavailable.",
                    statusCode: StatusCodes.Status503ServiceUnavailable);
            }
        }).AllowAnonymous().WithName("Readiness");

        return endpoints;
    }
}
