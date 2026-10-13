# Vault recovery runbook

This document is the recovery procedure for the thin text/URL deployment. Recovery is not proven until an isolated restore has been completed and recorded in [production evidence](production-evidence.md). A successful backup job by itself is not proof that the service can be recovered.

## Recovery set and policy

For the current slice, preserve a consistent set containing:

- An encrypted off-site PostgreSQL backup for the dedicated Vault database, with creation time and SHA-256 checksum.
- The matching `vault_file_data` volume (currently recorded as `object_count: 0`) and persistent ASP.NET Data Protection key volume, encrypted off-site with the database. Keep its application name `Vault` and restore the keys to `/home/app/.aspnet/DataProtection-Keys`. Without matching keys, existing authentication cookies may fail after recovery; users may need to sign in again.
- The API/web commit SHA and image identifiers, relevant EF migration level, non-secret configuration names/values, and restore instructions. Store secret values and encryption recovery material separately in the approved secret manager.

The backup runner snapshots PostgreSQL, `vault_file_data`, and `vault_data_protection_keys` at the same recovery point. Its manifest records UTC time, deployed commit, EF migration, database SHA-256, file-object count, and key inventory. Restic encrypts the snapshot before upload. Retention is 14 daily, 8 weekly, and 6 monthly snapshots. The zero file count accurately reports that file transfer is not implemented; verify nonzero file backup and a representative file restore before exposing that capability.

### Production backup architecture (Coolify Native S3 to Backblaze B2)

Production backups are managed natively through Coolify's integrated S3 database backup engine, eliminating host-level systemd dependencies and maintaining full dashboard observability:

- **Storage Provider:** Backblaze B2 Cloud Storage (S3-compatible)
- **Bucket:** `vault-backup-axewhyzed` (`s3.eu-central-003.backblazeb2.com`, region `eu-central-003`)
- **Encryption at Rest:** Backblaze Server-Side Encryption (SSE-B2 with AES-256) enforced automatically by Backblaze on all uploads.
- **Transport Security:** TLS 1.3 encrypted transfer directly from Coolify to Backblaze B2.
- **Schedule:** Daily at 03:00 UTC (`0 3 * * *`).
- **Scope:** Complete PostgreSQL cluster dump (`All databases`), protecting `vault_db` along with all collocated application databases.
- **Retention:** Single active off-site backup (`S3 retention = 1`) with local copies deleted immediately upon upload (`Local Deleted`), keeping VPS NVMe usage at 0 MB.
- **Session Keys Note:** The `vault_data_protection_keys` volume holds ASP.NET Core session keys used to sign temporary browser cookies and CSRF tokens. If keys are ever lost or regenerated, active sessions expire and the user simply signs in again; 100% of persistent data (items, users, passwords) resides in PostgreSQL `vault_db`.

### Recovery Procedure
1. In the Coolify Dashboard, navigate to `shared-postgres` (`postgresql-database-hd0i9wh6pfiqgtucmjxultfo`) -> **Backups** -> **Executions**.
2. Select the desired execution snapshot and trigger a restore, or download the `.dump.gz` archive.
3. To restore manually via CLI:
   ```bash
   gunzip -c backup.dump.gz | docker exec -i shared-postgres psql -U postgres -d vault_db
   ```
4. Verify application readiness at `https://vault.axewhyzedlabs.co.in/health/ready`.

*(Note: The repository also contains legacy standalone Restic utilities `compose.backup.yaml` / `compose.restore.yaml` if client-side encryption is ever required out-of-band).*

## Isolated restore drill

Use a separate, access-restricted Coolify environment or isolated host/network. Do not restore over production or connect a restore target to the live public hostname.

1. Select a PostgreSQL backup and Data Protection key backup from the same recovery point. Verify the backup artifact checksum and decryption material before restoring. Record source timestamp and expected database/migration version.
2. Provision an isolated PostgreSQL database and private app network with no public database/API port. Restore the database into that target using the documented tool and the target database's authorized admin role. Keep production credentials out of the isolated environment.
3. Restore the matching Data Protection key files into a dedicated volume mounted at `/home/app/.aspnet/DataProtection-Keys`, with ownership/permissions suitable for the non-root API process. Do not mix key sets between environments.
4. Deploy the recorded API and web images using isolated bootstrap credentials, `Database__ApplyMigrations=false`, `DataProtection__ApplicationName=Vault`, and a test-only allowed origin. Keep the API and database private. Do not configure the public production DNS name.
5. Verify health endpoints, expected migration state, and that a synthetic known text/URL fixture from the backup can be listed/read by its owner. Confirm another unauthenticated request is rejected. If using a restored cookie/key compatibility check, use only a controlled, nonproduction session and document its result.
6. Verify row counts or a safe metadata summary against the backup manifest; do not copy item content into the evidence record. Run database integrity/consistency checks appropriate to the deployed PostgreSQL version.
7. Record checksums, backup timestamp, measured RPO, restore start/end and RTO, data validation, failures, corrective actions, and operator in `production-evidence.md` or a dated evidence attachment. Mark the restore complete only after the application-level checks pass.
8. Tear down the isolated restore environment and its test credentials/volumes using the approved cleanup process. Preserve the evidence and required backup artifacts. Confirm production stayed healthy and unchanged.

## Production incident recovery

1. Declare the incident and stop risky deploys or maintenance. Check whether the failure is app, proxy, host, database, storage, or credential related. Preserve relevant logs without copying secrets or content.
2. If only the app image is faulty, follow the rollback section in [deployment](deployment.md): restore the prior immutable image and compatible configuration. Do not roll back the database blindly.
3. If PostgreSQL data loss/corruption is suspected, isolate the affected service and select the latest verified backup set. Record the chosen recovery point and expected data loss before restore. Do not overwrite the sole surviving database copy.
4. Provision a private replacement database and API environment. Restore PostgreSQL and the matching Data Protection keys from the same recovery set. Validate migration state, owner-scoped access, health, and representative text/URL retrieval before switching the web route.
5. Keep DNS and Traefik routes unchanged until the replacement passes checks. Then switch only the Vault web route through Coolify, verify TLS and synthetic browser flows, and monitor Vault plus neighboring applications.
6. Rotate credentials if exposure or compromise is suspected. Update only Vault's dedicated PostgreSQL role and Coolify secrets; do not rotate shared PostgreSQL administrator credentials as a routine Vault recovery step.
7. Record incident timeline, backup point, measured data loss and recovery time, validation, route switch, credential actions, and follow-up work. Do not state a recovery target was met unless measured evidence supports it.

## Rollback boundaries

A failed web/API deploy may be rolled back to a previously recorded image if its schema remains compatible. Database migrations may be forward-only; do not restore an old database over production merely to undo an application deploy. For incompatible or destructive changes, stop traffic to Vault, preserve current state, and prepare a reviewed forward repair or a coordinated database recovery. Never interrupt or reconfigure other applications on the shared host as a Vault rollback shortcut.
