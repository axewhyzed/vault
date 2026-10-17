using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.DependencyInjection;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Storage;

public sealed class StorageCleanupWorker(
    IServiceScopeFactory scopeFactory,
    LocalFileStore files,
    StorageMaintenanceGate gate,
    TimeProvider clock,
    ILogger<StorageCleanupWorker> logger) : BackgroundService
{
    private static readonly TimeSpan Interval = TimeSpan.FromMinutes(15);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        logger.LogInformation("Storage cleanup background worker started. Interval: {Interval}", Interval);

        try
        {
            await Task.Delay(TimeSpan.FromSeconds(30), clock, stoppingToken);
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            return;
        }

        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                await using var scope = scopeFactory.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<VaultDbContext>();

                logger.LogDebug("Starting scheduled storage cleanup sweep.");
                await FileMaintenance.RunAsync(db, files, gate, clock, TextWriter.Null, stoppingToken, applyChanges: true);
                logger.LogDebug("Storage cleanup sweep completed successfully.");
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Unexpected error occurred during storage cleanup sweep.");
            }

            try
            {
                await Task.Delay(Interval, clock, stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
        }

        logger.LogInformation("Storage cleanup background worker stopped.");
    }
}
