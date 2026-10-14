using Microsoft.EntityFrameworkCore;
using Vault.Api.Modules.Shared.Persistence;

namespace Vault.Api.Modules.Storage;

public static class FileMaintenance
{
    public static async Task RunAsync(VaultDbContext db, LocalFileStore files, StorageMaintenanceGate gate,
        TimeProvider clock, TextWriter output, CancellationToken ct, bool applyChanges = false)
    {
        using var maintenanceLock = gate.EnterExclusive();
        files.Initialize();
        var now = clock.GetUtcNow().UtcDateTime;
        var expiredItemCount = await db.Items.LongCountAsync(x => x.State == "available" && x.ExpiresAt <= now, ct);
        var owners = await db.Items.AsNoTracking().Where(x =>
                (x.State == "available" && x.ExpiresAt <= now)
                || (x.Type == "file" && ((x.State == "expired" || x.State == "unavailable") && x.ExpiresAt <= now || x.State == "deleted")))
            .Select(x => x.OwnerUserId).Distinct().ToListAsync(ct);
        var removeKeys = new List<string>();
        foreach (var ownerId in applyChanges ? owners : [])
        {
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            var ownerLocked = await db.Users.Where(x => x.Id == ownerId && x.Status == "active")
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct);
            if (ownerLocked == 0) continue;
            var cleanupSessions = db.UploadSessions.Where(session => session.OwnerUserId == ownerId && session.State == "complete"
                && session.ItemId != null && db.Items.Any(item => item.Id == session.ItemId && item.Type == "file"
                    && ((item.State == "available" && item.ExpiresAt <= now)
                        || (item.State == "expired" && item.ExpiresAt <= now)
                        || (item.State == "unavailable" && item.ExpiresAt <= now)
                        || item.State == "deleted")));
            // Sweep follows the global owner -> session lock order used by completion.
            await cleanupSessions.ExecuteUpdateAsync(s => s.SetProperty(x => x.State, x => x.State), ct);
            var cleanupKeys = await cleanupSessions.Select(x => x.StorageKey).ToListAsync(ct);
            removeKeys.AddRange(cleanupKeys);
            await db.Items.Where(x => x.OwnerUserId == ownerId && x.State == "available" && x.ExpiresAt <= now)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "expired").SetProperty(x => x.TextContent, (string?)null)
                    .SetProperty(x => x.UpdatedAt, now).SetProperty(x => x.Version, x => x.Version + 1), ct);
            await db.Items.Where(x => x.OwnerUserId == ownerId && x.State == "unavailable" && x.Type == "file" && x.ExpiresAt <= now)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "expired").SetProperty(x => x.TextContent, (string?)null)
                    .SetProperty(x => x.UpdatedAt, now).SetProperty(x => x.Version, x => x.Version + 1), ct);
            await db.Items.Where(x => x.OwnerUserId == ownerId && x.State == "expired" && x.TextContent != null)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.TextContent, (string?)null), ct);
            await tx.CommitAsync(ct);
        }

        var expiredUploads = await db.UploadSessions.Where(x => (x.State != "complete" && x.State != "expired" && x.ExpiresAt <= now)
                || (x.State == "expired" && x.ExpiresAt <= now && x.ItemId == null))
            .Select(x => new { x.OwnerUserId, x.Id, x.StagingKey, x.StorageKey }).ToListAsync(ct);
        foreach (var upload in expiredUploads)
        {
            if (!applyChanges) continue;
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            var ownerLocked = await db.Users.Where(x => x.Id == upload.OwnerUserId && x.Status == "active")
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct);
            if (ownerLocked == 0) continue;
            var expired = await db.UploadSessions.Where(x => x.Id == upload.Id && x.State != "complete" && x.ExpiresAt <= now)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "expired").SetProperty(x => x.UpdatedAt, now), ct);
            await tx.CommitAsync(ct);
            if (expired > 0)
            {
                files.DeleteStaging(upload.StagingKey);
                files.DeleteObject(upload.StorageKey);
            }
        }

        var staleReceiving = await db.UploadSessions.AsNoTracking()
            .Where(x => x.State == "receiving" && x.ExpiresAt > now && x.UpdatedAt <= now.AddHours(-2))
            .Select(x => new { x.OwnerUserId, x.Id, x.StagingKey, x.ExpectedSizeBytes, x.ExpectedSha256 }).ToListAsync(ct);
        var recoveredStages = 0;
        var resetUploads = 0;
        foreach (var upload in staleReceiving)
        {
            var inspected = files.InspectStaging(upload.StagingKey);
            var validStage = inspected is not null && inspected.Value.Size == upload.ExpectedSizeBytes
                && (upload.ExpectedSha256 is null || string.Equals(inspected.Value.Sha256, upload.ExpectedSha256, StringComparison.Ordinal));
            if (!applyChanges)
            {
                if (validStage) recoveredStages++; else resetUploads++;
                continue;
            }
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            var ownerLocked = await db.Users.Where(x => x.Id == upload.OwnerUserId && x.Status == "active")
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct);
            if (ownerLocked == 0) continue;
            var reset = await db.UploadSessions.Where(x => x.Id == upload.Id && x.State == "receiving" && x.UpdatedAt <= now.AddHours(-2))
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, validStage ? "staged" : "initiated")
                    .SetProperty(x => x.SizeBytes, validStage ? inspected!.Value.Size : null)
                    .SetProperty(x => x.Sha256, validStage ? inspected!.Value.Sha256 : null)
                    .SetProperty(x => x.UpdatedAt, now), ct);
            await tx.CommitAsync(ct);
            if (reset > 0)
            {
                if (!validStage) files.DeleteStaging(upload.StagingKey);
                if (validStage) recoveredStages++; else resetUploads++;
            }
        }

        if (!applyChanges)
        {
            var expiredOrDeletedKeys = await (from session in db.UploadSessions.AsNoTracking()
                                               join item in db.Items.AsNoTracking() on session.ItemId equals item.Id
                                               where session.State == "complete" && item.Type == "file"
                                                   && ((item.State == "available" && item.ExpiresAt <= now)
                                                       || (item.State == "expired" && item.ExpiresAt <= now)
                                                       || (item.State == "unavailable" && item.ExpiresAt <= now)
                                                       || item.State == "deleted")
                                               select session.StorageKey).ToListAsync(ct);
            removeKeys.AddRange(expiredOrDeletedKeys);
        }
        else
        {
            foreach (var key in removeKeys.Distinct(StringComparer.Ordinal)) files.DeleteObject(key);
        }

        var activeFiles = await (from session in db.UploadSessions.AsNoTracking()
                                 join item in db.Items.AsNoTracking() on session.ItemId equals item.Id
                                 where session.State == "complete" && item.State == "available" && item.ExpiresAt > now
                                 select new { session.OwnerUserId, session.StorageKey, item.Id }).ToListAsync(ct);
        var missingFiles = 0;
        foreach (var entry in activeFiles.Where(x => !File.Exists(files.ObjectPath(x.StorageKey))))
        {
            if (!applyChanges) { missingFiles++; continue; }
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            var ownerLocked = await db.Users.Where(x => x.Id == entry.OwnerUserId && x.Status == "active")
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, x => x.Status), ct);
            if (ownerLocked == 0) continue;
            await db.Items.Where(x => x.Id == entry.Id && x.OwnerUserId == entry.OwnerUserId && x.State == "available")
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.State, "unavailable").SetProperty(x => x.UpdatedAt, now).SetProperty(x => x.Version, x => x.Version + 1), ct);
            await tx.CommitAsync(ct);
            missingFiles++;
        }

        var stagedPromotions = await db.UploadSessions.AsNoTracking().Where(x => x.State == "staged" && x.ItemId == null)
            .Select(x => new { x.Id, x.StorageKey, x.StagingKey }).ToListAsync(ct);
        var promotedUncommitted = stagedPromotions.Where(x => File.Exists(files.ObjectPath(x.StorageKey)) && !File.Exists(files.StagingPath(x.StagingKey))).ToArray();
        var referencedObjects = await db.UploadSessions.Where(x => x.State == "complete" || x.State == "staged")
            .Select(x => x.StorageKey).ToListAsync(ct);
        var referenced = referencedObjects.ToHashSet(StringComparer.Ordinal);
        var orphanPaths = files.EnumerateObjects().Where(path => !referenced.Contains(Path.GetFileName(path))).ToArray();
        var agedOrphanPaths = orphanPaths.Where(path => File.GetLastWriteTimeUtc(path) <= now.AddHours(-24)).ToArray();
        var staleStaging = await db.UploadSessions.Where(x => x.State == "initiated" || x.State == "receiving" || x.State == "failed")
            .Select(x => x.StagingKey).ToListAsync(ct);
        var liveStaging = staleStaging.ToHashSet(StringComparer.Ordinal);
        var stalePaths = files.EnumerateStaging().Where(path => !liveStaging.Contains(Path.GetFileName(path))
            && File.GetLastWriteTimeUtc(path) < now.AddHours(-1)).ToArray();
        if (applyChanges)
        {
            foreach (var path in agedOrphanPaths) try { File.Delete(path); } catch (IOException) { }
            foreach (var path in stalePaths) try { File.Delete(path); } catch (IOException) { }
        }

        await output.WriteLineAsync($"mode={(applyChanges ? "apply" : "report-only")} expired_items={expiredItemCount} expired_uploads={expiredUploads.Count} expired_or_deleted_files={removeKeys.Distinct(StringComparer.Ordinal).Count()} abandoned_uploads_checked={staleReceiving.Count} {(applyChanges ? "valid_stages_recovered" : "valid_stages_recoverable")}={recoveredStages} {(applyChanges ? "partial_stages_reset" : "partial_stages_to_reset")}={resetUploads} missing_active_files={(applyChanges ? "marked_unavailable" : "reported") + "=" + missingFiles} promoted_uncommitted={promotedUncommitted.Length} orphan_objects={orphanPaths.Length} aged_orphan_objects_24h={agedOrphanPaths.Length} stale_staging_removed={(applyChanges ? stalePaths.Length : 0)}");
        foreach (var session in promotedUncommitted) await output.WriteLineAsync($"promoted_uncommitted_upload={session.Id:D}");
        foreach (var path in orphanPaths) await output.WriteLineAsync($"orphan_object={Path.GetFileName(path)} age_hours={(now - File.GetLastWriteTimeUtc(path)).TotalHours:F1} aged_for_cleanup={File.GetLastWriteTimeUtc(path) <= now.AddHours(-24)}");
    }
}
