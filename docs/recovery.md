# Backup and recovery

This runbook covers the PostgreSQL database, the file volume, and ASP.NET Data Protection keys. A successful backup command is not evidence of a production restore. Production backup credentials, schedule, isolated restore, and internet exposure remain pending for the owner; see [production evidence](production-evidence.md).

## Recovery set

- PostgreSQL holds users, devices, audit events, upload sessions, item metadata, idempotency records, and text/URL content.
- `vault_file_data` holds opaque file objects and temporary staging files. It must be backed up with PostgreSQL from the same recovery point. If the database and volume are from different points, some items can be unavailable or orphaned.
- `vault_data_protection_keys` holds cookie/CSRF encryption keys. Losing these keys invalidates active browser sessions but does not lose item data.
- The backup container uses Restic client-side encryption and records a manifest with the database dump checksum, migration version, file count/bytes, and deployment SHA. Production B2 secrets and daily scheduling are not configured by this repository change.
- The API, backup container, and manual sweep share `vault_maintenance_lock`. File upload, promotion, and delete operations hold a shared lock; backup and sweep take an exclusive lock so those mutations cannot run between the PostgreSQL dump and file-volume scan.

The Compose production deployment mounts the named file volume at `/var/lib/vault/files`; the API stores staging and promoted objects on that same volume. Keep the volume on the same filesystem so promotion uses a same-filesystem rename.

## Synthetic isolated restore check

Run the isolated test from Git Bash with Docker and Python 3:

```bash
bash infra/backup/test-restore.sh
```

The test creates disposable Docker volumes, a local encrypted Restic repository, and synthetic-only fixtures. It backs up and restores one PostgreSQL row, a synthetic binary file, and synthetic Data Protection key material, then verifies each in an isolated target. It uses no production volume, database, B2 secret, or user file. This test proves the script path; it does not count as the required production restore drill.

## Owner-run production backup and restore

Before real use, the owner must configure an encrypted off-site repository and daily backup of the dedicated Vault database plus `vault_file_data`, then complete one isolated restore. Keep backup credentials outside this repository. Record the snapshot, migration version, file count, start/end time, verification checks, operator, and result in [production evidence](production-evidence.md).

For a restore, stop Vault writes first. Restore PostgreSQL, `vault_file_data`, and Data Protection keys from the same snapshot into isolated replacement resources. Verify the EF migration level, representative synthetic item metadata, and file hashes before routing traffic to the replacement. Do not restore over the only surviving production database or reuse production volumes as a test target.

After a restore or after an interrupted promotion, run the manual reconciliation command against the recovered database and file volume:

```bash
docker compose -f compose.production.yaml exec api dotnet Vault.Api.dll --sweep-files
docker compose -f compose.production.yaml exec api dotnet Vault.Api.dll --sweep-files --apply
```

The first invocation is report-only. Apply persists expired item state and clears expired text, removes expired/deleted file objects and old staging files, marks missing active files unavailable, and recovers complete stale staging files whose stored size/hash match. Receiving sessions older than two hours are checked: valid staged bytes are recoverable; partial data can be removed and the session retried with its same intent key. Unreferenced final objects are deleted only after a 24-hour grace; younger objects and promoted-but-uncommitted objects are reported for manual review. The command is intentionally manual; there is no background cleanup worker in this slice.

## Incident boundaries

For an application image issue, roll back to the last recorded immutable API/web images when the schema is compatible. Do not roll back database migrations blindly. For suspected data loss, preserve the current database and volume, identify a matching backup set, and record the expected recovery point before restoring. Keep the public route unchanged until the replacement passes isolated checks. Rotate only Vault credentials if exposure is suspected; do not modify shared host or neighboring application resources as a Vault recovery shortcut.
