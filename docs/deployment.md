# Slice 4 deployment runbook

This runbook describes the first thin production deployment of the current Vault web/API slice: authenticated text and HTTP(S) URL items only. The chosen public origin is `https://vault.axewhyzedlabs.co.in`.

The target is the existing OVH Singapore VPS with Coolify v4 and Traefik, alongside existing applications, and its private PostgreSQL 18 container on the `coolify` Docker network. Keep the existing apps and shared proxy in service. Deploy Vault as its own Coolify resources and use a dedicated PostgreSQL database and login role. PostgreSQL and the API must have no public or host-published ports. Traefik exposes only the web frontend over HTTPS.

This is a procedure, not evidence that Vault is live. Slice 4 does not include binary files, Android clients, the MVP exit gate, or starting the six-week product gate. Do not announce those outcomes until their acceptance checks are completed and recorded.

## Before exposing the hostname

1. Review the pending items in [production evidence](production-evidence.md). D5 is a hard gate: sign-in rate limiting, bounded item creation, encrypted off-site backup, and one isolated restore must be ready before the internet-facing service is used for real content. A URL or text item only; there are no file bytes in this slice.
2. Confirm `vault.axewhyzedlabs.co.in` resolves to the existing OVH VPS address in the authoritative DNS provider. Use DNS-only routing as for the other Coolify hostnames unless the current edge configuration has been separately reviewed. Do not alter the DNS records for existing applications.
3. Container images (`ghcr.io/axewhyzed/vault-api`, `ghcr.io/axewhyzed/vault-web`, and `ghcr.io/axewhyzed/vault-backup`) are built, tested, and published to GitHub Container Registry (GHCR) via the `.github/workflows/deploy.yml` workflow on push to `main`. In Coolify, deploy the reviewed immutable commit as the `compose.production.yaml` application. Coolify pulls the pre-built images from `ghcr.io` (using the host's existing GHCR registry credentials), eliminating CPU and RAM build strain on the VPS. The API listens on container port `8080`; the web Nginx container listens on port `80` and proxies `/api/*` and `/health/*` to the API over the private `vault_backend` network. Traefik labels route `vault.axewhyzedlabs.co.in` to the web container. Confirm HTTP to HTTPS redirect and valid TLS. Do not publish API port 8080 or database port 5432 on the VPS.
4. The production Compose topology attaches the API to the existing `coolify` network for private PostgreSQL access, and attaches API and web to an internal-only `vault_backend` network for application traffic. Verify the web container resolves the API upstream `vault-api-backend` and `/api` requests reach the API before public routing. No service has a published host port. Do not create a new public-facing database route or change the network/port mappings of other resources.
5. Confirm the OVH/cloud firewall and host firewall still allow only the required inbound SSH, HTTP, and HTTPS ports (currently 22/tcp, 80/tcp, and 443/tcp). Check that 5432, 8080, Coolify's management ports, and any other application ports remain closed from the internet. The setup log records an earlier scan; repeat this check for this deployment and save the result. Never weaken global Docker/Traefik or firewall rules to get Vault working.
6. Verify a dedicated database and role exist before setting the app connection string. Example only; substitute unique Vault-specific names and generate a new password in the secret manager:

   ```sql
   CREATE ROLE <vault_login_role> WITH LOGIN PASSWORD '<set-out-of-band>';
   CREATE DATABASE <vault_database> OWNER <vault_login_role>;
   REVOKE CONNECT, TEMPORARY ON DATABASE <vault_database> FROM PUBLIC;
   GRANT CONNECT ON DATABASE <vault_database> TO <vault_login_role>;
   ```

   Run through an authorized PostgreSQL administration path on the private Docker network. Never put the shared PostgreSQL administrator credential in the Vault resource. Keep the role/database dedicated to Vault and never grant Vault access to other applications' databases.
7. Confirm the exact Traefik source IP as observed by the web container on the `coolify` network, then set `VAULT_TRAEFIK_TRUSTED_IP` to that single address for Nginx real-IP processing. The web-to-API hop uses the configured private address `VAULT_WEB_IP` (default `172.31.20.10`); `TrustedProxyIp` in the API must match it. Choose `VAULT_BACKEND_SUBNET`, `VAULT_WEB_IP`, and `VAULT_API_IP` after inspecting host Docker networks; the defaults are examples and must be changed if the subnet collides. The API trusts at most one forwarded hop and ignores forwarded headers from unknown peers. Do not guess either address or trust a whole subnet. If the observed proxy address changes, update the setting and re-verify rate-limit client IP attribution before exposure.

## Required production settings

Set the following in the Coolify API resource's environment/secret manager. Values below are names or placeholders, not credentials. Do not commit production environment files or paste secrets into tickets/logs.

| Setting | Production value |
|---|---|
| `VAULT_API_IMAGE` | `ghcr.io/axewhyzed/vault-api:latest` (or explicit immutable sha tag) |
| `VAULT_WEB_IMAGE` | `ghcr.io/axewhyzed/vault-web:latest` (or explicit immutable sha tag) |
| `ASPNETCORE_ENVIRONMENT` | `Production` |
| `ASPNETCORE_HTTP_PORTS` | `8080` |
| `VAULT_DB_HOST` | `<private-postgres-container-hostname>` |
| `VAULT_DB_PORT` | `5432` |
| `VAULT_DB_NAME` | `<dedicated-vault-database>` |
| `VAULT_DB_USER` | `<dedicated-vault-role>` |
| `VAULT_DB_PASSWORD` | `<secret>` |
| `Database__ApplyMigrations` | `false` on the API service; the one-off `api-migrate` Compose profile enables migrations before rollout |
| `Database__MigrationOnly` | `false` on the API service; the migration profile sets it to `true` |
| `Bootstrap__Email` | `<owner-login-email>` |
| `Bootstrap__Password` | `<unique-high-entropy-bootstrap-password>` |
| `Security__AllowedOrigins__0` | `https://vault.axewhyzedlabs.co.in` |
| `TrustedProxyIp` | The same address configured in `VAULT_WEB_IP` |
| `VAULT_TRAEFIK_TRUSTED_IP` | `<verified-single-coolify-proxy-ip>` |
| VAULT_BACKEND_SUBNET | <unused-private-cidr> |
| VAULT_WEB_IP | <unused-ip-in-backend-subnet> |
| VAULT_API_IP | <different-unused-ip-in-backend-subnet> |
| `DataProtection__ApplicationName` | `Vault` |
| `DataProtection__KeysPath` | `/home/app/.aspnet/DataProtection-Keys` |

Mount the dedicated `vault_data_protection_keys` persistent volume at `/home/app/.aspnet/DataProtection-Keys` in the API container, writable by its non-root runtime user. Preserve it across deploys and include it in the encrypted backup set so existing browser cookies remain decryptable after restore. It must not be shared with other apps. Do not publish the volume or put keys in the image. The Compose `dpkeys-init` one-shot initializes ownership of both this key volume and the currently empty `vault_file_data` volume for UID 1654 before API or migration startup.

The frontend is same-origin; it needs no database credentials. Its Traefik labels in `compose.production.yaml` route `https://vault.axewhyzedlabs.co.in` to internal port `80`. The current production Nginx upstream is `vault-api-backend` on the internal network; do not configure a public API URL.

## Deploy and smoke-check

1. Confirm the selected commit has passed the GitHub Actions CI workflow (`build-publish`) and pre-built images are published to GHCR. Deploy the API and web services from those exact immutable images; record the commit SHA and resulting image digests/tags. Do not use an unverified manual tag as the deployment record.
2. Take a fresh encrypted off-site PostgreSQL backup before the first migration. For later schema changes, use a compatible/expand migration and take a pre-change backup. Run the one-off `api-migrate` service with the `migrate` profile and wait for successful completion before deploying/routing the API. The production API service remains configured with migrations disabled; do not start public traffic until migration and bootstrap checks pass.
3. Wait for the API `/health/ready` check and web `/healthz` check to pass. Through the HTTPS hostname, confirm the TLS certificate is valid, HTTP redirects to HTTPS, and `/health/ready` returns a healthy response without exposing internal details.
4. From a trusted test browser, verify sign-in, CSRF-protected text create/list/detail/copy/delete, and URL create/list/open/delete using synthetic content only. Confirm an anonymous item request is rejected. Confirm item responses are not cacheable. Do not submit personal or sensitive content during smoke testing.
5. Verify sign-in attempts are attributed to the actual client IP after the Traefik forwarding boundary and the configured rate limit operates as expected. Do not infer this solely from a forwarded header supplied by the client.
6. From an external network, check that 5432, 8080, Coolify management ports, and other non-web ports are closed; only the intended public web entry points should answer. Check existing application health and host capacity after deployment.
7. Record every result, including failures, in [production evidence](production-evidence.md). Only after D5's backup and isolated restore evidence is complete may the owner mark the first real-use gate satisfied. Starting a six-week measurement period requires separate explicit evidence of the relevant stable-production use; a thin deployment alone does not start it.

## Operational boundaries

- Current deployed scope: text and HTTP(S) URL items. The API has per-owner byte/count checks and seven-day maximum retention; current UI/API evidence must be recorded before calling production checks complete.
- The Compose file declares a `vault_file_data` volume, but current code does not accept or store binary file content. Do not treat the unused volume as file-transfer or file-backup evidence, and do not advertise file transfer based on these instructions.
- Keep the API and PostgreSQL internal to the private Docker network. Traefik routes to the web frontend only; do not route `/api` directly at the API service.
- Do not change shared proxy, database, firewall, or other Coolify resources as part of routine Vault deployments unless an independently reviewed change requires it.
- Logs are structured JSON but still require review for data minimization and retention. Never place passwords, cookies, authorization headers, or item content in operational notes.
