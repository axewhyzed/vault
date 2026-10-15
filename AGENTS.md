# Agent Guidelines & Repository Standards

This document establishes operational rules, architecture boundaries, and workflow standards for all AI coding agents working in the Vault repository.

---

## 1. Repository Architecture & Boundaries

- **`src/api/Vault.Api`**: ASP.NET Core 10 Minimal APIs & domain modules (Identity, Storage, Transfers, Shared Persistence).
- **`web`**: React 19 + TypeScript frontend served via Nginx.
- **`android`**: Native Kotlin Android client (Compose UI, WorkManager, OkHttp, ZXing).
- **`infra`**: Nginx proxy, PostgreSQL, backup/restore scripts, and container definitions.
- **`.github/workflows`**:
  - `deploy.yml`: Backend & Web validation, tests, and publishing Docker images (`vault-api`, `vault-web`, `vault-backup`) to GHCR.
  - `android.yml`: Isolated Android client CI, unit testing, linting, artifact generation, and gated GitHub Releases.

---

## 2. Android Client Development & Commit Rules

To prevent release spam, broken builds, and repository bloat, all agents modifying `android/**` must adhere strictly to these rules:

### A. Cohesive Batching (No Micro-Commits)
- **Do not commit trivial, single-line, or intermediate trial-and-error edits** to `android/**`.
- Changes to `android/**` must represent complete, self-contained, and functional units of work (e.g., an entire feature implementation, complete UI flow, or verified bug fix).

### B. Mandatory Local Verification Before Commit
- **Never use GitHub Actions as a remote compile or lint debugger.**
- Before committing any change touching `android/**`, you **must** run the local verification script from the repository root:
  ```powershell
  ./scripts/test-android.ps1
  ```
  *(Or execute `android/gradlew.bat --no-daemon assembleDebug testDebugUnitTest lintDebug` directly).*
- Ensure that the debug APK builds cleanly, all 15+ unit tests pass, and Android lint reports no errors.

### C. Version Code & Name Hygiene
- **Do not bump `versionCode` or `versionName`** in `android/app/build.gradle.kts` on routine commits.
- Version increments are reserved strictly for intentional, user-requested milestone releases.

### D. Zero-Binary Policy
- **Never commit compiled binaries or secrets to git**, including:
  - `*.apk`, `*.aab`
  - `*.jar`, `*.aar`, `*.so`
  - `*.jks`, `*.keystore`
- All binary builds belong in Gradle output directories (`build/`) and are distributed solely through GitHub Actions artifacts or GitHub Releases.

### E. Release & Distribution Protocol
- **Continuous Builds:** Normal commits to `main` trigger `android.yml` to run tests and upload `vault-debug-apk` as a 14-day workflow artifact in the Actions tab. Normal commits **never** create a GitHub Release.
- **Milestone Releases:** Official GitHub Releases are cut **only** when:
  1. A git tag starting with `v` is pushed (e.g. `v0.1.0`):
     ```bash
     git tag v0.1.0
     git push origin v0.1.0
     ```
  2. Or `android.yml` is manually triggered via `workflow_dispatch` with `publish_release: true`.

---

## 3. CI/CD Decoupling & Trigger Scoping Rules

- **Code-Only Triggering (No Doc/Asset Runs):**
  - Workflows must **never** run on pure documentation, markdown, or asset changes (`docs/**`, `README.md`, `LICENSE`, `AGENTS.md`, `*.md`, `docs/assets/**`).
  - `deploy.yml` strictly triggers on server/web code paths (`src/**`, `web/**`, `infra/**`, `tests/**`, `compose*.yaml`).
  - `android.yml` strictly triggers on Android client code (`android/**` excluding `!android/**.md`).
- **Do not re-couple Android to `deploy.yml`**:
  - `deploy.yml` is responsible for server-side Docker container builds (`vault-api`, `vault-web`, `vault-backup`).
  - It must remain fast and lightweight, running only `.NET` API tests and Web build/lint.
  - It must never track `android/**` paths.
- **Do not add backend/web paths to `android.yml`**:
  - Android unit tests mock HTTP endpoints locally using `MockWebServer` and do not test against live C# backends.
  - Adding `src/**` or `web/**` triggers to `android.yml` causes unnecessary runner minutes and noise without providing integration verification.

---

## 4. Git Identity & Commit Standards

- Always author commits using the repository owner's identity:
  - Name: `Mihir Bambhaniya`
  - Email: `mihir0234@outlook.com`
- Use Conventional Commits formatting:
  - `feat(android): ...`
  - `fix(android): ...`
  - `ci: ...`
  - `test: ...`
  - `docs: ...`
