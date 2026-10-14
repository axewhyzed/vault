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
| One isolated restore (D5) | Passed | Native Coolify database restore mechanism verified. Execution manifest logged in Coolify UI. |
| D5 real-use prerequisite | Satisfied | Core controls, private network boundaries, dedicated database isolation, and off-site encrypted backup active. |

## Slice 4 deployment record

- Deployment date/time (UTC): 2026-10-13 19:01 UTC
- Vault commit SHA: 118c7d5858bc654518a3d4c12ce44000d9ee3d3b
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

Copy for every isolated or incident restore:

- Date/time started and ended (UTC):
- Operator / environment reference:
- Backup timestamp and artifact reference:
- PostgreSQL artifact checksum result:
- Matching Data Protection key set reference/checksum result:
- Restored commit/image and migration level:
- Health, owner-scoped synthetic retrieval, and anonymous rejection:
- Observed RPO / RTO:
- Failures and corrective actions:
- Isolated environment cleanup confirmed:
