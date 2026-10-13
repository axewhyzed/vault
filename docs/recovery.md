# Vault recovery runbook

This document is the recovery procedure for the thin text/URL deployment. Recovery is not proven until an isolated restore has been completed and recorded in [production evidence](production-evidence.md). A successful backup job by itself is not proof that the service can be recovered.

## Recovery set and policy

For the current slice, preserve a consistent set containing:

- An encrypted off-site PostgreSQL backup for the dedicated Vault database, with creation time and SHA-256 checksum.
- The matching `vault_file_data` volume (currently recorded as `object_count: 0`) and persistent ASP.NET Data Protection key volume, encrypted off-site with the database. Keep its application name `Vault` and restore the keys to `/home/app/.aspnet/DataProtection-Keys`. Without matching keys, existing authentication cookies may fail after recovery; users may need to sign in again.
- The API/web commit SHA and image identifiers, relevant EF migration level, non-secret configuration names/values, and restore instructions. Store secret values and encryption recovery material separately in the approved secret manager.

The backup runner snapshots PostgreSQL, `vault_file_data`, and `vault_data_protection_keys` at the same recovery point. Its manifest records UTC time, deployed commit, EF migration, database SHA-256, file-object count, and key inventory. Restic encrypts the snapshot before upload. Retention is 14 daily, 8 weekly, and 6 monthly snapshots. The zero file count accurately reports that file transfer is not implemented; verify nonzero file backup and a representative file restore before exposing that capability.

### Daily backup installation

The host requires Docker Compose v2 and systemd. Create `/etc/vault/vault-backup.env`, owned by root with mode `0600`, containing these values. Keep it and both secret files outside the repository:

```dotenv
VAULT_BACKUP_TARGET=production
VAULT_DEPLOYED_COMMIT_SHA=<deployed 7-64 digit git SHA>
VAULT_DB_HOST=<private PostgreSQL hostname on coolify>
VAULT_DB_PORT=5432
VAULT_DB_NAME=<dedicated Vault database>
VAULT_DB_USER=<dedicated Vault role>
VAULT_DB_PASSWORD=<dedicated Vault password>
VAULT_DB_SSLMODE=prefer
VAULT_FILE_VOLUME_NAME=vault_file_data
VAULT_DP_VOLUME_NAME=vault_data_protection_keys
RESTIC_REPOSITORY=s3:s3.<b2-region>.backblazeb2.com/<bucket>/vault
RESTIC_PASSWORD_FILE=/etc/vault/secrets/restic-password
B2_CREDENTIALS_FILE=/etc/vault/secrets/b2-credentials
AWS_DEFAULT_REGION=<b2-region>
```

The B2 credentials file uses the standard `[default]` profile with `aws_access_key_id` and `aws_secret_access_key`. Use a bucket-scoped B2 application key. Protect the env, password, and credentials files with `chown root:root` and `chmod 0600`. Keep an offline recovery copy of the Restic password; the B2 key alone cannot decrypt data.

Initialize the encrypted repository once and enable the daily systemd timer:

```sh
cd /opt/vault
docker compose --env-file /etc/vault/vault-backup.env --file compose.backup.yaml build backup
docker compose --env-file /etc/vault/vault-backup.env --file compose.backup.yaml run --rm --entrypoint restic backup init
install -o root -g root -m 0644 infra/systemd/vault-backup.service /etc/systemd/system/vault-backup.service
install -o root -g root -m 0644 infra/systemd/vault-backup.timer /etc/systemd/system/vault-backup.timer
systemctl daemon-reload
systemctl enable --now vault-backup.timer
```

The timer runs daily at 03:15 UTC with up to 30 minutes randomized delay. Check `systemctl list-timers vault-backup.timer` and `journalctl -u vault-backup.service`. Compose rotates runner logs at 10 MiB with three retained files; Restic prunes according to the retention above. The persistent `vault_backup_lock` volume prevents overlapping runs on this host.

For restore, use a separate env file containing only the B2/Restic values, a unique `RESTORE_TARGET_ID`, an explicit `RESTORE_SNAPSHOT` ID, `RESTORE_CONFIRM_ISOLATED=yes`, and the path to a temporary restore DB password file. The restore Compose stack creates uniquely named `vault_restore_*` volumes, has no published ports, does not join `coolify`, and never mounts production volumes. Its target DB is attached only to an internal network; the runner also gets a distinct egress-only bridge to read B2. Run `docker compose --env-file /etc/vault/restore.env --file compose.restore.yaml run --rm restore`, inspect its migration/file/key inventory, record evidence, and remove only that exact restore target. `infra/backup/test-restore.sh` exercises the backup and restore scripts against synthetic local PostgreSQL data and an internal-only Docker network without production or B2 credentials. Run it from the repository root in Bash with Docker and Python 3 available: `bash infra/backup/test-restore.sh`.

Target the D5 minimum of a daily backup and one isolated restore before any real use. Record observed RPO, elapsed restore time, checksum results, and application validation. The long-term operations plan calls for RPO under 24 hours and a monthly isolated restore; those are not complete until scheduled and evidenced. Host snapshots are an additional aid only and do not replace off-site backups.

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
