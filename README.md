# Vault

Vault is a private cross-device inbox. The current local slice supports signing in to the React web client, sending text and HTTP(S) links, browsing item metadata, viewing/copying text, and deleting items. The stack uses an ASP.NET Core API and PostgreSQL with EF Core migrations, health endpoints, JSON request logs, browser cookie authentication, and owner-scoped item data.

## First local setup

Requirements: Docker Desktop with Docker Compose v2, PowerShell 7 or Windows PowerShell, .NET 10 SDK, and Node.js/npm for the full verification script.

From the repository root, create a local HTTPS certificate and ignored local credentials:

```powershell
./scripts/setup-local.ps1
```

The script asks .NET to trust its localhost development certificate and exports the certificate and key to `.local/tls/`. It does not remove existing .NET certificates. On first use, it creates a high-entropy bootstrap password in the ignored `.env` file. Keep that file private; it holds the local account credentials. `.env.example` contains placeholders only.

Start Vault:

```powershell
docker compose up --build --wait
```

Open <https://localhost:8080> (or the configured `VAULT_WEB_PORT`). The bootstrap email defaults to `owner@vault.local`; its generated password is in `.env` as `VAULT_BOOTSTRAP_PASSWORD`. The UI is exposed only on the host loopback address. Nginx terminates HTTPS and proxies `/api/*` to the private API container. PostgreSQL and the API have no host-published ports. The database persists in `postgres_data`; Data Protection keys persist separately in `data_protection_keys` under the fixed `Vault` application name.

The local Compose network gives Nginx a stable address, which is the only proxy IP trusted by the API. Nginx replaces forwarded client headers at the trust boundary. The test stack uses a separate network subnet and proxy address so it can run beside the ordinary development stack.

Stop the stack and keep its database with `docker compose down`. To remove the local database and Data Protection keys, use `docker compose down --volumes`.

## Slice 4 deployment status

Slice 4 prepares a production deployment for the existing OVH Singapore VPS running Coolify/Traefik and its private PostgreSQL 18 container. No VPS, DNS, firewall, Coolify, database, or B2 resources have been changed. The production Compose file routes only the web frontend through Traefik; the API and database have no published host ports. Use a dedicated Vault database and role on the existing private PostgreSQL server, and keep all credentials outside this repository.

The deployment configuration is `compose.production.yaml`, which deploys pre-built images published to GHCR via GitHub Actions (`.github/workflows/deploy.yml`); copy `.env.production.example` to an external secret-managed environment and replace every placeholder after inspecting the host's networks and proxy IP. The encrypted backup profile and daily systemd units are under `compose.backup.yaml` and `infra/systemd/`. Use [docs/deployment.md](docs/deployment.md), [docs/recovery.md](docs/recovery.md), and [docs/production-evidence.md](docs/production-evidence.md) for the owner-run deployment, restore, and evidence steps. Live deployment, DNS/TLS, B2 credentials, isolated production restore, and internet browser checks remain pending. Current Slice 4 code still supports text and URLs only; it does not implement file transfer or begin the six-week product gate.

## One-command checks

Run (requires Docker Desktop's engine to be running):

```powershell
./scripts/test.ps1
```

The script creates a temporary localhost certificate, then uses an isolated Compose project with a fresh database and test-only bootstrap credentials. It verifies fresh PostgreSQL migration and bootstrap behavior, exercises CSRF/login/session/logout and text/link item flows through HTTPS Nginx, tests same-key concurrency, tied-cursor pagination, and the quota race on PostgreSQL, runs the API tests, and runs the web build and lint. It forces the test listener to `127.0.0.1`, uses a random host port and unused private subnet, and restores process environment on exit. Cleanup removes only that test project, its volumes, and its project-tagged test images. The regular development stack, its TLS files, and its `vault_postgres_data` volume are left alone. Test payloads are synthetic. If Docker's engine is stopped, it exits with a clear prerequisite message. The isolated encrypted backup/restore drill is available separately via Git Bash and Python 3: `bash infra/backup/test-restore.sh`. It uses synthetic data and a local Restic repository, without production resources or B2 credentials.

The Slice 3 text limit is 262,144 UTF-8 bytes. Links are trimmed and must be absolute HTTP(S) URLs (up to 8,192 UTF-8 bytes). Retention defaults to seven days and can be shortened to one through seven days. Active quota is 2,000,000,000 bytes and 10,000 items per owner. The sender must reuse an `Idempotency-Key` UUID when retrying one send; create responses contain metadata only, and replay remains metadata-only after expiry or deletion. This slice stores text and URLs only: binary files, Android clients, multi-account UX, and a cleanup worker are later work.

The default listener is `127.0.0.1`; the local development certificate covers `localhost`. To test from a phone on the same private LAN, use a stable private IPv4 address for the PC (prefer a router DHCP reservation), such as `192.168.1.25`. The optional steps below bind Vault to that address only. They do not open a router port or expose Vault to the internet.

Install [mkcert](https://github.com/FiloSottile/mkcert#installation) on Windows using Scoop or Chocolatey, then in PowerShell from the repository root replace the example IP with the PC's stable LAN IPv4 address:

```powershell
$lanIp = '192.168.1.25'
./scripts/setup-local.ps1
mkcert -install
mkcert -cert-file .local/tls/dev-cert.pem -key-file .local/tls/dev-cert.key localhost $lanIp
mkcert -CAROOT
```

`mkcert -CAROOT` prints the directory holding `rootCA.pem`. Copy **only** that public CA certificate to the phone over a trusted local method. Install it on the phone as a CA certificate using the device's certificate/security settings, then use a browser that trusts user-installed CAs. Never copy `rootCA-key.pem`; it can impersonate any site trusted by that CA. The mkcert project describes the CA files and warns against sharing the private key in its [official instructions](https://github.com/FiloSottile/mkcert#installing-the-ca-on-other-systems).

Set these values in the ignored `.env` file, keeping the URL IP identical to the certificate SAN and the address bound by Docker:

```dotenv
VAULT_WEB_PORT=8080
VAULT_BIND_ADDRESS=192.168.1.25
VAULT_LAN_ORIGIN=https://192.168.1.25:8080
```

Recreate the local services without removing their volumes:

```powershell
docker compose up --build --wait --force-recreate
```

If Windows Firewall blocks access and you choose to allow it, run this in an elevated PowerShell window. It allows TCP 8080 only for the Windows **Private** network profile and addresses classified as the local subnet:

```powershell
New-NetFirewallRule -DisplayName 'Vault LAN on private network' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080 -Profile Private -RemoteAddress LocalSubnet
```

If you added that rule, remove it when finished with `Remove-NetFirewallRule -DisplayName 'Vault LAN on private network'`. Do not change a Public network profile, disable certificate checks, accept a browser certificate warning, or configure router port forwarding. On the phone, browse to `https://192.168.1.25:8080`; confirm the certificate is trusted before signing in. This repository has not yet been verified on a second LAN device, so the real phone-on-LAN demo remains pending.

Return to loopback after the demo by setting `VAULT_BIND_ADDRESS=127.0.0.1` and clearing `VAULT_LAN_ORIGIN` in `.env`, then running `docker compose up --build --wait --force-recreate`. If the LAN IP changes, issue a new certificate with the new IP SAN and update both `.env` values.

## Repository layout

- `src/api/Vault.Api`: ASP.NET Core API and domain module boundaries.
- `web`: React/TypeScript client and static Nginx image.
- `infra/nginx.conf`: local HTTPS Nginx proxy configuration.
- `tests/api/Vault.Api.Tests`: API tests.
- `compose.yaml`: local PostgreSQL, API, and HTTPS web stack.
