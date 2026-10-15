# Vault

Vault is a private cross-device inbox. The local client supports signing in to the React web client with an email address or username, pairing the Android app with a short-lived QR or paste code, sending text and HTTP(S) links, streaming files into private staging, browsing item metadata, viewing/copying text, downloading files as attachments, and deleting items. The web app also provides a confirmed “Sign out everywhere” action. The stack uses an ASP.NET Core API and PostgreSQL with EF Core migrations, health endpoints, JSON request logs, browser cookie authentication, and owner-scoped item data. File bytes live outside PostgreSQL in the `file_data` volume.

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

Open <https://localhost:8080> (or the configured `VAULT_WEB_PORT`). The bootstrap email and username default to `owner@vault.local` and `owner`; the generated password is in `.env` as `VAULT_BOOTSTRAP_PASSWORD`. Sign in with either identifier. The UI is exposed only on the host loopback address. Nginx terminates HTTPS and proxies `/api/*` to the private API container. PostgreSQL and the API have no host-published ports. The database persists in `postgres_data`; Data Protection keys persist separately in `data_protection_keys` under the fixed `Vault` application name.

The local Compose network gives Nginx a stable address, which is the only proxy IP trusted by the API. Nginx replaces forwarded client headers at the trust boundary. The test stack uses a separate network subnet and proxy address so it can run beside the ordinary development stack.

## Android build, pairing, and Share

For the Android client, install Android Studio with the Android SDK 36 platform/build tools and JDK 17. The release build is pinned to `https://vault.axewhyzedlabs.co.in`; debug builds default to that origin too, so pass `-PvaultDebugServerOrigin` explicitly for local work. Follow [docs/android-send.md](docs/android-send.md) to issue a local mkcert certificate and install its public CA in the emulator/device before building. For an Android emulator using the `localhost` certificate, first run `adb reverse tcp:8080 tcp:8080`, then from `android/` build:

```powershell
./gradlew.bat -PvaultDebugServerOrigin=https://localhost:8080 assembleDebug
```

For physical-phone LAN work, use the LAN HTTPS setup below and build with `-PvaultDebugServerOrigin=https://<PC-LAN-IP>:8080`; the app's pairing origin, web origin, `VAULT_SERVER_ORIGIN`, and certificate SAN must all match exactly. Debug builds additionally trust user-installed CA roots so a local mkcert CA can be used; release builds trust system roots only. Local Android setup and the native share steps are in [docs/android-send.md](docs/android-send.md). In the signed-in web inbox, choose **Generate pairing code**. Scan the QR, or paste the displayed JSON into Android; if using the deep link, enter the separate short-lived secret when prompted. Pairing lasts ten minutes. The QR JSON contains the one-time secret, while the Android deep link contains only the server origin and public pairing-session ID. Generate a fresh code after expiry; an earlier unused code remains usable until its own expiry or consumption. After pairing, use Android's Share action for text or one file and confirm the result in the web inbox. An interrupted upload can be retried from Android's upload status; retries reuse the intent's idempotency key.

For phone-on-LAN work, follow the optional local TLS steps below and use a certificate trusted by both the browser and the Android app. Keep the PC's stable private-LAN origin the same in the browser, certificate SAN, and Android server origin. Do not turn off TLS checks or accept a certificate warning. This repository's Android hardware acceptance matrix remains pending; see [docs/slice6-verification.md](docs/slice6-verification.md).

Stop the stack and keep its database and file volume with `docker compose down`. To remove the local database, file bytes, and Data Protection keys, use `docker compose down --volumes`.

## Deployment status

Production deployment is still pending for the owner. No VPS, DNS, firewall, Coolify, database, or backup-provider resources have been changed. The production Compose file routes only the web frontend through Traefik; the API and database have no published host ports. Use a dedicated Vault database and role on the existing private PostgreSQL server, and keep all credentials outside this repository.

The deployment configuration is `compose.production.yaml`; copy `.env.production.example` to an external secret-managed environment and replace every placeholder after inspecting host networks and proxy IP. The production API mounts `vault_file_data` at `/var/lib/vault/files` and shares `vault_maintenance_lock` with the backup and sweep commands, so file mutations are excluded across the PostgreSQL dump and file scan. The local synthetic backup/restore drill is `bash infra/backup/test-restore.sh` and verifies synthetic database, file, and key fixtures. It does not prove a production backup or restore. Use [docs/deployment.md](docs/deployment.md), [docs/recovery.md](docs/recovery.md), and [docs/production-evidence.md](docs/production-evidence.md) for owner-run deployment, restore, and evidence steps. Live deployment, DNS/TLS, off-site backup, isolated production restore, and internet browser checks remain pending; the six-week product gate has not started.

## One-command checks

Run (requires Docker Desktop's engine to be running):

```powershell
./scripts/test.ps1
```

The script creates a temporary localhost certificate, then uses an isolated Compose project with a fresh database and test-only bootstrap credentials. It verifies migrations and bootstrap behavior, exercises CSRF/login/session/logout and text/link flows through HTTPS Nginx, runs API tests, and runs the web build and lint. API tests include a synthetic streamed file upload, checksum, replay, owner scope, detail, and attachment-download checks. Cleanup removes only that test project and its volumes. The isolated encrypted backup/restore drill is available separately via Git Bash and Python 3: `bash infra/backup/test-restore.sh`; it verifies synthetic database, file, and key fixtures without production resources or B2 credentials.

The text limit is 262,144 UTF-8 bytes; links are trimmed absolute HTTP(S) URLs up to 8,192 UTF-8 bytes. Files stream with a 100,000,000-byte cap and server-computed SHA-256; empty files are accepted. Staging and opaque promoted objects share one volume. Downloads are attachment-only with `nosniff` and `no-store`; MIME values are untrusted, and missing or invalid MIME falls back to `application/octet-stream`. Retention defaults to seven days and can be shortened to one through seven days. Active quota is 2,000,000,000 bytes and 10,000 items per owner, checked at file initiation and completion. The file volume must preserve 20% of actual total capacity; checks fail closed, including during streamed writes. Run `dotnet Vault.Api.dll --sweep-files` to report cleanup actions, then add `--apply` to apply them; there is no background worker yet.

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
VAULT_SERVER_ORIGIN=https://192.168.1.25:8080
```

For the Android app, copy only the public mkcert root CA (`rootCA.pem`) to the phone and install it as a user CA. Debug builds are configured to trust user-installed CAs; release builds trust system CAs only. Rebuild the debug APK with the same LAN origin:

```powershell
Set-Location android
./gradlew.bat -PvaultDebugServerOrigin=https://192.168.1.25:8080 assembleDebug
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

Return to loopback after the demo by setting `VAULT_BIND_ADDRESS=127.0.0.1`, `VAULT_SERVER_ORIGIN=https://localhost:8080`, and clearing `VAULT_LAN_ORIGIN` in `.env`, then running `docker compose up --build --wait --force-recreate`. Build a separate emulator debug APK with `-PvaultDebugServerOrigin=https://localhost:8080`. If the LAN IP changes, issue a new certificate with the new IP SAN and update both origin settings and the Android debug property.

## Repository layout

- `src/api/Vault.Api`: ASP.NET Core API and domain module boundaries.
- `web`: React/TypeScript client and static Nginx image.
- `infra/nginx.conf`: local HTTPS Nginx proxy configuration.
- `tests/api/Vault.Api.Tests`: API tests.
- `android`: native Android Share client.
- `compose.yaml`: local PostgreSQL, API, and HTTPS web stack.
