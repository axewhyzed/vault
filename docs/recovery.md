# Vault recovery runbook

This document is the recovery procedure for the thin text/URL deployment. Recovery is not proven until an isolated restore has been completed and recorded in [production evidence](production-evidence.md). A successful backup job by itself is not proof that the service can be recovered.

## Recovery set and policy

For the current slice (authenticated text and URLs), the recovery set is structured as follows:

1. **Durable Application Data (PostgreSQL `vault_db`):**
   - 100% of persistent state—including user accounts, usernames, password hashes, device registrations, audit events, items (text and URLs), and quotas—resides inside the dedicated PostgreSQL `vault_db`.
   - Protected daily via Coolify's native S3 backup to Backblaze B2 (`vault-backup-axewhyzed`).
2. **Ephemeral Session Signing Keys (`vault_data_protection_keys`):**
   - Holds ASP.NET Core Data Protection keys used exclusively to encrypt temporary 8-hour authentication cookies (`__Host-Vault.Auth`) and CSRF tokens (`__Host-Vault.Csrf`).
   - Decoupled from durable data: if recovered without keys, ASP.NET Core automatically provisions a new key set. Active browser sessions expire and users simply sign in again. No user items, credentials, or application data are lost.
3. **Binary File Volume (`vault_file_data`):**
   - Currently declared but unused (`object_count: 0`), as binary file transfer is outside Slice 4 scope. When file transfer is introduced, volume snapshot replication will be integrated into the backup pipeline.

### Production backup architecture (Coolify Native S3 to Backblaze B2)

Production backups are managed natively through Coolify's integrated S3 database backup engine, eliminating host-level systemd dependencies and maintaining full dashboard observability:

- **Storage Provider:** Backblaze B2 Cloud Storage (S3-compatible)
- **Bucket:** `vault-backup-axewhyzed` (`s3.eu-central-003.backblazeb2.com`, region `eu-central-003`)
- **Encryption at Rest:** Backblaze Server-Side Encryption (SSE-B2 with AES-256) enforced automatically by Backblaze on all uploads.
- **Transport Security:** TLS 1.3 encrypted transfer directly from Coolify to Backblaze B2.
- **Schedule:** Daily at 03:00 UTC (`0 3 * * *`).
- **Scope:** Complete PostgreSQL cluster dump (`All databases`), protecting `vault_db` along with all collocated application databases.
- **Retention:** Single active off-site backup (`S3 retention = 1`) with local copies deleted immediately upon upload (`Local Deleted`), keeping VPS NVMe usage at 0 MB.
- **Out-of-Band Archiving:** The repository retains standalone Restic utilities (`compose.backup.yaml` / `compose.restore.yaml`) for optional multi-volume client-encrypted offline archives.

### Recovery Procedure
1. In the Coolify Dashboard, navigate to `shared-postgres` (`postgresql-database-hd0i9wh6pfiqgtucmjxultfo`) -> **Backups** -> **Executions**.
2. Select the desired execution snapshot and trigger a restore, or download the `.dump.gz` archive.
3. To restore manually via CLI:
   ```bash
   gunzip -c backup.dump.gz | docker exec -i shared-postgres psql -U postgres -d vault_db
   ```
4. Verify application readiness at `https://vault.axewhyzedlabs.co.in/health/ready`.

## Isolated restore drill

Use a separate, access-restricted environment or isolated database target on the private network. Do not restore over production or expose a restore target to the public domain.

1. Retrieve the latest PostgreSQL backup snapshot archive (`.dump.gz`) from Backblaze B2 via Coolify or S3 CLI. Verify archive integrity and record the source timestamp.
2. Provision an isolated PostgreSQL test database (e.g. `vault_restore_test`) on the private network using an authorized administrator role.
3. Restore the database dump into the test database:
   ```bash
   gunzip -c backup.dump.gz | docker exec -i shared-postgres psql -U postgres -d vault_restore_test
   ```
4. Run schema and data validation queries on the test database: confirm table row counts, verify the expected EF Core migration history level (`20261014154206_UsernameLoginAndBootstrapCredentialManagement`), and confirm the bootstrap account record exists.
5. Optionally deploy a private, non-routed test API container pointing to `vault_restore_test` with `Database__ApplyMigrations=false` to verify `/health/ready` returns 200 OK without public exposure.
6. Record start/end time, source snapshot timestamp, measured RTO, migration level, verification queries, operator, and pass/fail outcome in the `Restore event template` in `docs/production-evidence.md`.
7. Drop the temporary test database (`DROP DATABASE vault_restore_test;`) and clean up temporary test containers. Confirm production remained unaffected.

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
