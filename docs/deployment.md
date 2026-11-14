# Production deployment runbook

This runbook covers a Coolify/Traefik deployment with PostgreSQL kept private. It assumes the host, public hostname, proxy, and database service are provisioned. Keep live host and service identifiers and deployment logs in private operations records outside version control.

## Before exposing Vault

1. Confirm DNS, the public hostname, host firewall, and the trusted proxy address on its container network.
2. Create a dedicated Vault database and database role on the existing private PostgreSQL service. Do not publish PostgreSQL or the API on a host port.
3. Configure encrypted off-site backups for the database, `vault_file_data`, and Data Protection keys. Complete one isolated restore before real use. The local synthetic backup test is not a production restore.
4. Use `.env.production.example` as a checklist. Set its values in Coolify, or provide them through a private Compose environment file when deploying outside Coolify. Replace every placeholder, use immutable image references, and keep database/bootstrap/backup secrets out of Git and shell history.
5. Confirm the file volume has enough capacity for the 100,000,000-byte item cap and the API can observe actual filesystem free space. Upload initiation fails closed unless a requested file fits while retaining 20% of the filesystem's total capacity.

## Compose services and limits

`compose.production.yaml` exposes only the web container to Traefik. The API has no published port, and PostgreSQL remains on the private Coolify network. The API mounts:

- `vault_file_data` at `/var/lib/vault/files` for staging and opaque promoted objects;
- `vault_maintenance_lock` at `/run/vault-maintenance` for cross-container backup/sweep coordination;
- `vault_data_protection_keys` for cookie/CSRF keys.

Keep the file path fixed at `/var/lib/vault/files`; Compose mounts the named volume there. API upload, completion, and delete operations hold a shared maintenance lock. Backup takes the matching exclusive lock from before `pg_dump` through the Restic file-volume scan, and the sweep command also takes it exclusively. This keeps API file mutations from changing the volume between database and file capture.

The migration profile can be run as a one-shot before starting or updating the API:

```bash
docker compose -f compose.production.yaml --profile migrate run --rm api-migrate
```

Nginx allows the upload route up to 101 MiB at the proxy layer, disables request buffering, and uses long upload/download timeouts. The API streams raw `application/octet-stream` into staging, enforces the exact 100,000,000-byte cap, computes SHA-256, flushes staging, and promotes by same-volume no-overwrite rename. Zero-byte files are accepted. File content type is untrusted; a missing or invalid value becomes `application/octet-stream`, and downloads are attachments with `nosniff` and `no-store` headers.

The API reserves 20% of the actual storage filesystem's total capacity. It rejects initiation if free space cannot cover the requested upload and reserve, and fails closed if the volume cannot be measured. Active quotas remain 2,000,000,000 bytes and 10,000 items per owner. They are checked at initiation and rechecked under the owner lock at completion. The current plan does not reserve quota formally for concurrent sessions.

Expired reads are blocked immediately. `StorageCleanupWorker` starts after 30 seconds and runs a cleanup sweep every 15 minutes. An interrupted HTTP stream resets the session for retry with the same key. If the API stops mid-stream or immediate reconciliation is needed, the manual command below checks receiving sessions older than two hours. A complete staged file with the expected size/hash is recoverable; partial bytes can be removed and the session made retryable. The command is report-only by default. Inspect its output before using `--apply` during a maintenance window:

```bash
docker compose -f compose.production.yaml exec api dotnet Vault.Api.dll --sweep-files
docker compose -f compose.production.yaml exec api dotnet Vault.Api.dll --sweep-files --apply
```

Apply persists expired state and clears expired text, retries removal for expired/deleted file items, marks missing active file objects unavailable, recovers or resets stale staging sessions, and removes aged staging files. Unreferenced final objects are removed only after a 24-hour grace. Review promoted-but-uncommitted and younger orphan object keys; those remain for manual operator review. See [backup and recovery](recovery.md).

## Rollout and verification

Set `VAULT_API_IMAGE` and `VAULT_WEB_IMAGE` to immutable SHA-tagged or digest-pinned references before deployment; the Compose defaults use `latest`. Set `VAULT_BACKUP_IMAGE` similarly when using the backup Compose file. Run the migration profile, inspect service health and logs, then verify TLS, routing, private-only API/database access, sign-in, text/URL flows, and a synthetic file upload/download. Confirm the object is present in the file volume and the next encrypted backup. Use synthetic fixtures for drills. Record deployment outcomes and keep live hostnames, network identifiers, and service IDs in a private operations record outside version control.
