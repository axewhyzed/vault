# Slice 6 verification

Slice 6 code verification is separate from VPS, backup/restore, and real-device acceptance. Local builds and automated tests do not prove the product flow on physical hardware or over the internet.

## Automated checks

From the repository root:

```powershell
./scripts/test.ps1
./scripts/test-android.ps1
```

`scripts/test.ps1` exercises the local HTTPS/PostgreSQL stack, the .NET API suite, web build/lint, and Slice 6 pairing/revocation flows. `scripts/test-android.ps1` builds the debug APK, runs Android unit tests, and runs Android lint using the installed Android Studio JBR/SDK. These local checks do not establish production or physical-device acceptance.

| Check | Status on 2026-10-15 |
|---|---|
| `npm run build` | Passed |
| `npm run lint` | Passed |
| `./scripts/test.ps1` local HTTPS/PostgreSQL, 36 API tests, web build/lint, pairing and revoke-all concurrency | Passed |
| `./scripts/test-android.ps1` debug APK, 15 unit tests, Android lint | Passed |

## Contract checklist

| Area | Expected check | Status |
|---|---|---|
| Web pairing create | Authenticated cookie, CSRF, Origin, JSON `{}`; response shape is validated before display | Implemented; verify against local API |
| QR and paste | QR content is pairing JSON; deep link carries only origin/session ID; secret remains separate for fallback | Implemented; verify against Android build |
| Expiration | Ten-minute countdown; secret cleared on expiry, sign-out, or unmount; regenerate creates a fresh session | Implemented; verify expiry path |
| Global revocation | Confirmed cookie + CSRF + Origin `POST /api/v1/auth/sessions/revoke-all`, body `{}`; `204` shows signed-out result | Implemented; verify against local API |
| Android pairing | QR/deep-link/paste paths exchange the session for device credentials over TLS | Automated checks passed; physical-device run pending |
| Share confirmation | Draft is prepared locally first; Send enqueues with selected retention, Cancel deletes the draft/spool | Unit checks passed; physical-device run pending |
| Share retry | Lost/failed upload response retries the same intent idempotently | Unit and API checks passed; physical-device run pending |

## Physical-device acceptance matrix

Run each row on a physical phone with a trusted HTTPS origin. Record Android version/device, app build hash, Vault commit/origin, date, expected and observed result, and any follow-up. Keep tokens, pairing secrets, passwords, and user content out of evidence.

| Scenario | Status | Result / evidence |
|---|---|---|
| Install debug app and pair from the web QR | Pending | |
| Paste full QR JSON and deep link plus separate secret | Pending | |
| Reject expired pairing and complete a regenerated pairing | Pending | |
| Share Chrome URL and plain text | Pending | |
| Share one photo from Gallery | Pending | |
| Share one document from Files | Pending | |
| Share a screenshot and a file from a chat app | Pending | |
| Background app during upload and restore status | Pending | |
| Force-stop/process death; retry completes one item | Pending | |
| Switch Wi-Fi and cellular during upload | Pending | |
| No network then reconnect and retry | Pending | |
| Revoke Android refresh tokens from web; confirm next request requires sign-in | Pending | |
| Source URI permission expires or source app removes content | Pending | |
| Maximum allowed file, duplicate worker execution, and lost completion response | Pending | |
| Confirm no cleartext transport or certificate-validation bypass | Pending | |
| Confirm web **Sign out everywhere** shows signed-out result | Pending | |

No physical-device acceptance, internet phone/PC flow, Slice 5 production rollout, production backup/restore, or six-week product gate is recorded here.
