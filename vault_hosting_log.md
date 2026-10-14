# Vault — OVH VPS Production Hosting & Deployment Log

**Host:** OVH Singapore VPS (`51.79.206.27`)  
**Domain:** `vault.axewhyzedlabs.co.in`  
**Control Plane:** Coolify v4.4.8 on Docker (`localhost`)  
**Reverse Proxy:** Traefik v3.7 (`coolify-proxy`) on `coolify` Docker network  
**Database:** Shared PostgreSQL 18 container (`hd0i9wh6pfiqgtucmjxultfo`)  
**Reference Runbook:** `docs/deployment.md`, `docs/recovery.md`

---

## Deployment Progress Overview

- [x] **Step 1:** Push immutable commit `d5a7d64` to GitHub `origin/main` (Verified: branch is up to date with origin/main).
- [x] **Step 2:** Cloudflare DNS `A` record for `vault.axewhyzedlabs.co.in` -> `51.79.206.27` (Verified: resolves directly to 51.79.206.27).
- [x] **Step 3:** Dedicated PostgreSQL 18 role (`vault_user`) & database (`vault_db`) creation with strict permissions.
- [x] **Step 4:** Host inspection: Traefik internal IP discovery & Docker subnet conflict verification.
- [x] **Step 5:** Coolify Docker Compose resource setup with production environment variables.
- [x] **Step 6:** One-shot EF Core migration and bootstrap account seeding (`api-migrate`).
- [x] **Step 7:** Web & API service startup, internal health checks, and Traefik HTTPS verification (Live on https://vault.axewhyzedlabs.co.in).
- [x] **Step 8:** Coolify native S3 database backup to Backblaze B2 (Bucket: `vault-backup-axewhyzed`, daily schedule active).
- [x] **Step 9:** Username sign-in, zero-touch credential synchronization, and relaxed password limits (Applied & verified).
- [x] **Step 10:** Production reverse-proxy upstream pinning (`vault-api-backend`), proxy trust, and CSRF 500 resolution.
- [x] **Step 11:** Smoke testing & live authentication verification (CSRF tokens 100% OK, user logged in on production UI).
- [ ] **Step 12:** D5 isolated restore drill verification & production evidence ledger sign-off.

---

## Action Log

### Step 3: PostgreSQL Database & Role Setup (Completed)
* **Target Container:** `hd0i9wh6pfiqgtucmjxultfo` (Shared `postgres:18-alpine` on Docker network `coolify`)
* **Database Name:** `vault_db`
* **Role/User:** `vault_user`
* **Password:** [REDACTED - rotate this secret]
* **Internal Connection String:**  
  `Host=hd0i9wh6pfiqgtucmjxultfo;Port=5432;Database=vault_db;Username=vault_user;Password=[REDACTED]`
* **Status:** Created and verified with permissions restricted (connect & temp granted only to `vault_user`, revoked from `PUBLIC`).

---

### Step 4: Host Inspection (Completed)
* **Traefik Proxy IP (`coolify-proxy` on `coolify` network):** `10.0.1.7`  
  *(Configured as `VAULT_TRAEFIK_TRUSTED_IP=10.0.1.7`)*
* **Subnet Inspection:**  
  * Existing host networks: `bridge: 10.0.0.0/24`, `coolify: 10.0.1.0/24`, `xvfcbab6k6ijggdacutt6lbg: 10.0.2.0/24`.
  * `VAULT_BACKEND_SUBNET=172.31.20.0/24` (IPs `172.31.20.10`, `172.31.20.11`) has zero collision and is safe.
* **Status:** Verified and recorded.

---

### Step 4.5: CI/CD Pipeline & GHCR Image Publishing (Completed)
* **Strategy:** Offload .NET and Node/Vite container builds from the 4 GB OVH VPS to GitHub Actions.
* **Workflow:** `.github/workflows/deploy.yml` (PASSED green)
* **Deployed Commit SHA:** `6ee1fcfa9e295459e24e98862c0a6d270f3878ce`
* **Artifacts Published to GHCR:**
  * `ghcr.io/axewhyzed/vault-api:latest` (and `:6ee1fcfa9e295459e24e98862c0a6d270f3878ce`)
  * `ghcr.io/axewhyzed/vault-web:latest` (and `:6ee1fcfa9e295459e24e98862c0a6d270f3878ce`)
  * `ghcr.io/axewhyzed/vault-backup:latest` (and `:6ee1fcfa9e295459e24e98862c0a6d270f3878ce`)
* **Status:** Verified and published.

---

### Step 5: Coolify Resource Deployment (Completed)
* **Control Plane:** Coolify Web UI (`https://coolify.axewhyzedlabs.co.in`)
* **Project / Environment:** `My first project` / `production`
* **Resource Type:** Docker Compose from GitHub (`axewhyzed/vault`)
* **Compose File:** `compose.production.yaml`
* **Application Name:** `vault`
* **Admin Bootstrap Email:** `coding0234@gmail.com`
* **Admin Bootstrap Password:** [REDACTED - rotate this secret]
* **Execution:** All 15 environment variables configured and saved in Coolify.
* **Status:** Resource created and configured.

---

### Step 6: Database Migration & Bootstrap Seeding (Completed)
* **Image Used:** `ghcr.io/axewhyzed/vault-api:latest` (`sha256:b68c52e22dcfbec9b74bdbd166ca6b7566088ccf010c640754f97677fc833f79`)
* **EF Core Migrations Applied:**
  1. `20261012000000_InitialSchema`
  2. `20261013000000_AccountSessionFoundation`
  3. `20261014000000_TransfersSlice3`
* **Admin Account Seeded:** `coding0234@gmail.com`
* **Future Deploys:** Configured `Database__ApplyMigrations: "${DATABASE_APPLY_MIGRATIONS:-true}"` in `compose.production.yaml` so any future EF Core schema changes migrate automatically on startup before traffic arrives (zero-touch).

---

### Step 7: Service Launch & Health Verification (Completed)
* **Live Origin:** `https://vault.axewhyzedlabs.co.in`
* **Status:** Operational and Healthy.
* **Routing:** Traefik dynamically configured by Coolify native domain manager for service `web` on port `80`.
* **TLS:** Valid Let's Encrypt certificate auto-issued by Coolify Traefik proxy.
* **Health Endpoints:**
  * `https://vault.axewhyzedlabs.co.in/healthz` -> 200 OK
  * `https://vault.axewhyzedlabs.co.in/health/ready` -> 200 OK (`{"status":"ready"}`)
* **Application Verification:** Sign-in and sign-out successfully verified on production web UI with bootstrap credentials.

---

### Step 8: Coolify Native S3 Database Backup to Backblaze B2 (Completed)
* **Storage Provider:** Backblaze B2 Cloud Storage (S3-compatible)
* **Bucket Name:** `vault-backup-axewhyzed`
* **Bucket ID:** `4385aa20b1ba3b61a6170d15`
* **Endpoint / Region:** `s3.eu-central-003.backblazeb2.com` (`eu-central-003`)
* **Key ID:** `00335a01ab167d50000000001`
* **Key Name:** `vault-backup-key`
* **Application Key:** [REDACTED - rotate this key]
* **Encryption at Rest:** Backblaze Server-Side Encryption (SSE-B2 with AES-256) enabled by default on all bucket uploads.
* **Coolify Integration:**
  * S3 Storage `Backblaze-Vault-B2` registered and validated in Coolify.
  * Shared PostgreSQL resource renamed to `shared-postgres` (`hd0i9wh6pfiqgtucmjxultfo`).
  * Automated backup schedule: Daily at 03:00 UTC (`0 3 * * *`), target `All databases`.
  * Local copy policy: `Delete local backup` (archives deleted locally after upload to keep VPS NVMe 100% clean).
* **Live Test Verification:** Executed manual backup via Coolify UI:
  * Status: `Success`
  * Duration: 10s
  * Size: 3.63 KB
  * Availability: `Local Deleted`, `S3 Available`.
* **Zero Host Debt:** Avoided ad-hoc host systemd timers, `/opt/vault` clones, and `/etc/vault` files; 100% managed by Coolify PaaS.

---

### Step 9: Username Sign-In, Credential Auto-Sync & Policy Relaxation (Completed)
* **Feature Scope:**
  * Enabled case-insensitive username login (`mihir0234` or account email).
  * EF Core migration: `20261014154206_UsernameLoginAndBootstrapCredentialManagement` applied automatically on boot.
  * Added `VAULT_BOOTSTRAP_SYNC_CREDENTIALS` (default `true`) in `compose.production.yaml`: dynamically detects changes to `VAULT_BOOTSTRAP_USERNAME` or `VAULT_BOOTSTRAP_PASSWORD` in Coolify environment variables, updates the stored password hash and username on boot, and revokes active sessions automatically without manual container CLI commands.
  * Lowered bootstrap password length constraint from 14 characters to 3 characters (`password.Length < 3`) per operator preference.
* **Relevant Commits:** `52f92c0`, `c7741ec`, `35d8bdf`
* **Test Status:** 17 unit and integration tests passing.

---

### Step 10: Production Reverse-Proxy Upstream Hardening & CSRF 500 Resolution (Completed)
* **Problem Observed:**
  * Browser received `GET /api/v1/auth/csrf 500 (Internal Server Error)` intermittently (50/50 alternating round-robin), causing the UI error *"Vault could not prepare a secure request."*
* **Root Cause Analysis:**
  * `web/nginx.conf` was configured with `proxy_pass http://api:8080;`.
  * The `api` container belongs to both `vault_backend` (`172.31.20.11`) and `coolify` (`10.0.1.x`) Docker networks.
  * Docker internal DNS round-robined Nginx connections between both interfaces. Traffic on the `coolify` network interface was rejected by ASP.NET Core proxy validation, causing `Request.IsHttps` to evaluate to `false` and throwing `InvalidOperationException` when setting the secure `__Host-Vault.Csrf` cookie.
* **Resolution Implemented:**
  1. Pinned Nginx upstream in `web/nginx.conf` exclusively to `http://vault-api-backend:8080;` on the isolated `vault_backend` network, eliminating DNS round-robin to the `coolify` interface.
  2. Configured `KnownIPNetworks` in `src/api/Vault.Api/Program.cs` to trust RFC 1918 subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`) with `ForwardLimit = null`, establishing trust across multi-proxy hops (Traefik -> Nginx -> API).
  3. Added DataProtection directory write probing and automatic fallback to `~/.aspnet/DataProtection-Keys` to guard against Docker volume permission errors.
  4. Pre-created `/home/app/.aspnet/DataProtection-Keys` and `/var/lib/vault/files` with UID `1654` in `src/api/Vault.Api/Dockerfile`.
  5. Added `X-Forwarded-Host $host;` and `X-Forwarded-Port 443;` in `web/nginx.conf`.
* **Relevant Commits:** `369fa62`, `c2ac0b4`

---

### Step 11: Production Verification & Smoke Testing (Completed)
* **Live Origin:** `https://vault.axewhyzedlabs.co.in`
* **CSRF Endpoint Health:** Tested consecutive requests to `/api/v1/auth/csrf` -> **100% returned `200 OK`** with valid `__Host-Vault.Csrf` cookies and tokens. Zero flapping.
* **Authentication Verification:** User signed in successfully via production web interface using username (`mihir0234`) and updated password. Session restored cleanly.
