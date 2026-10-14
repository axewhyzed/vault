# Production evidence ledger

This ledger separates repository/configuration facts from production facts. **Pending** means no evidence has yet been recorded here; it is not a pass. Attach sanitized command output, screenshots, or checksums by reference. Never include secret values, cookies, access tokens, item text, or URL query content.

## Slice 4 D5 and exposure gate

| Check | Status | Evidence to record |
|---|---|---|
| Chosen hostname and DNS | Passed | `vault.axewhyzedlabs.co.in` resolved to OVH VPS IP `51.79.206.27` via Cloudflare DNS (DNS-Only / Grey Cloud). Existing records preserved. |
| TLS and routing | Passed | Valid Let's Encrypt TLS certificate auto-issued by Coolify Traefik proxy. HTTP automatically redirects to HTTPS. `/healthz` returns `200 OK`, `/health/ready` returns `200 OK`. Routing is scoped strictly to web service (port 80). |
| Host and cloud firewall | Passed | External ports 22, 80, 443 open. Internal container ports (5432, 8080) and Coolify management ports closed to public internet via UFW and DOCKER-USER chain. |
| Existing app preservation | Passed | Existing apps (`buffet-platform`, `cloudflare-warp`, `coolify-proxy`, `coolify-db`) healthy and operational before and after Vault deploy. |
| Private API and PostgreSQL | Passed | API and PostgreSQL run internal to `coolify` and `vault_backend` (`172.31.20.0/24`) networks. Neither has published host port bindings. |
| Dedicated PostgreSQL isolation | Passed | Dedicated role `vault_user` and database `vault_db` on `shared-postgres` (`hd0i9wh6pfiqgtucmjxultfo`). Public schema privileges revoked. Verified isolated from other databases. |
| Trusted client IP | Passed | `VAULT_TRAEFIK_TRUSTED_IP` configured to Traefik internal peer `10.0.1.7`. `VAULT_WEB_IP` (`172.31.20.10`) trusted by API `TrustedProxyIp`. |
| Sign-in rate limit | Passed | ASP.NET rate limiting active for credential attempts. Verified by local and production test harnesses. |
| Text/URL item bounds | Passed | Application verification passed. Synthetic flows validated. |
| Encrypted off-site PostgreSQL backup | Passed | Backblaze B2 S3 storage registered (`vault-backup-axewhyzed`, `eu-central-003`). Coolify automated daily backup verified (`● Success`, duration 10s, local copy pruned, S3 available). Protected by Backblaze SSE-B2 (AES-256) at rest. |
| Data Protection key policy | Passed | Persistent volume `vault_data_protection_keys` retains cookie signing keys. Ephemeral auth state decoupled from durable PostgreSQL storage. |
| One isolated restore (D5) | Passed | Snapshot `2026-10-14 03:00:16 UTC` streamed directly from Backblaze B2 into isolated database `vault_restore_test`. Verified schema integrity (3 migrations, 1 user, 1 device, 0 items). Measured RTO ~12s. Test DB dropped and CLI purged cleanly. |
| D5 real-use prerequisite | Satisfied | Isolated restore drill successfully executed and verified against off-site Backblaze B2 backup. Production service is now fully cleared for real content and persistent use. |

## Slice 4 deployment record

- Deployment date/time (UTC): 2026-10-14 17:45 UTC
- Vault commit SHA: c2ac0b4defddc8ac98bad70da8fdf3c9c049d2cf
- API/web image identifiers: ghcr.io/axewhyzed/vault-api:latest, ghcr.io/axewhyzed/vault-web:latest
- EF migration level: 20261014154206_UsernameLoginAndBootstrapCredentialManagement (InitialSchema, AccountSessionFoundation, TransfersSlice3, UsernameLoginAndBootstrapCredentialManagement)
- Coolify deployment result and health: Healthy (200 OK on /healthz, /health/ready, and /api/v1/auth/csrf)
- Smoke flow result: User successfully signed in and restored session on live web UI (https://vault.axewhyzedlabs.co.in) using username and short password
- External checks and operator: Mihir Bambhaniya
- Backup engine: Coolify S3 native backup to Backblaze B2 (Bucket: vault-backup-axewhyzed)
- Issues/follow-up: None. Service fully live. Upstream pinned to vault-api-backend on private backend network.

## Product and MVP gates

- Slice 4 scope demonstrated: Pending. Record phone browser to PC browser text/URL behavior only if it is actually completed over the internet.
- MVP exit gate: **Not established by Slice 4.** Android send/receive, file flows, reliability scenarios, complete security floor, and recovery floor require their own evidence.
- Six-week product gate start: **Not started by this deployment record.** Enter a start date only after the owner confirms stable production use and begins the planned metadata-only measurement. Record weekly review references; never collect item content.
- File storage / backup of file bytes: **Not in current Slice 4 scope.** Add its storage volume and consistency/checksum evidence before enabling file exposure.

## Restore event template

Completed isolated restore drill:

- Date/time started and ended (UTC): 2026-10-14 18:25 UTC to 2026-10-14 18:36 UTC
- Operator / environment reference: Mihir Bambhaniya / Coolify PaaS on OVH Singapore VPS (`51.79.206.27`), isolated container terminal execution on `shared-postgres` (`hd0i9wh6pfiqgtucmjxultfo`)
- Backup timestamp and artifact reference: 2026-10-14 03:00:16 UTC (`data/coolify/backups/databases/root-team-0/shared-postgres-hd0i9wh6pfiqgtucmjxultfo/pg-dump-all-1791946807-fllwomnci4apyg7fjbtiyzox.gz`, size 3717 bytes, bucket `vault-backup-axewhyzed` on Backblaze B2 `eu-central-003`)
- PostgreSQL artifact checksum result: Streamed directly from Backblaze B2 S3 over TLS, gunzip decompressed, database redirection filter (`sed 's/\\connect vault_db/\\connect vault_restore_test/g'`) applied safely
- Matching Data Protection key set reference/checksum result: Not required for durable recovery; decoupled persistent volume `vault_data_protection_keys` holds ephemeral 8h session cookies only. Cold start regenerates keys cleanly.
- Restored commit/image and migration level: Restored to snapshot state at 03:00 UTC containing 3 migrations (`20261012000000_InitialSchema`, `20261013000000_AccountSessionFoundation`, `20261014000000_TransfersSlice3`).
- Health, owner-scoped synthetic retrieval, and anonymous rejection: Table integrity verified in isolated `vault_restore_test`: `users` (1 row, bootstrap user `coding0234@gmail.com`), `devices` (1 row), `relay_items` (0 rows), `__EFMigrationsHistory` (3 rows).
- Observed RPO / RTO: RPO = ~15.5 hours (daily schedule interval); RTO = ~12 seconds (streaming download, uncompress, and SQL ingestion into test database).
- Failures and corrective actions: Initial table query used PascalCase identifier; corrected to EF Core PostgreSQL lowercase schema (`users`, `devices`, `relay_items`).
- Isolated environment cleanup confirmed: `DROP DATABASE vault_restore_test;` executed successfully. `apk del aws-cli` purged 54 temporary packages. Production database `vault_db` and neighboring containers remained untouched and 100% operational.
