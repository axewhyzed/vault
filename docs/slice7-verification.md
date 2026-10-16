# Slice 7 verification

Slice 7 is the Android read-only receive inbox. Local implementation checks and automated tests are separate from phone acceptance, internet use, and production evidence. This record does not claim that the Slice 7 flow has passed on a physical phone.

## Automated checks

From the repository root, with Android Studio JBR and Android SDK Platform 36 installed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT = "$env:USERPROFILE\Android"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
./scripts/test-android.ps1 -JavaHome $env:JAVA_HOME -AndroidSdk $env:ANDROID_SDK_ROOT
```

This runs Android `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. These automated checks can validate parsing, paging, request behavior, safe URL validation, attachment verification, and Android build/lint rules covered by the tests. They do not demonstrate UI operation on physical hardware, behavior of the selected document provider, or cross-device transfer over the public internet. API-stack checks are run separately with `./scripts/test.ps1` when verifying backend and web changes.

| Check | Status |
|---|---|
| Android local build, 52 JVM/Robolectric tests (including Compose screens and a WorkManager retry), and lint via `scripts/test-android.ps1` | Passed locally after the Android UX refresh; source-level test matrix is in `docs/android-test-coverage.md` |
| Backend/API and web checks via `scripts/test.ps1` | Passed locally with Docker: 36 API tests, HTTPS/PostgreSQL integration scenarios, web build and lint |
| Physical-device acceptance below | Pending |
| Internet phone-to-PC/PC-to-phone flow | Pending |
| Production Slice 5/7 rollout and backup/restore | Pending |
| MVP exit gate and product gate | Pending |

Record command output, commit/build identity, date, and any follow-up without recording credentials, pairing secrets, or real transferred content.

## Physical-device acceptance matrix

Run with a paired physical Android phone and a trusted HTTPS origin. Include device and Android version, app build hash, Vault commit and origin, date, expected and observed result, and follow-up. Use synthetic test data only.

| Scenario | Status | Result / evidence |
|---|---|---|
| Paired app opens on Send and shows Send / Inbox / Activity navigation | Pending | |
| In-app Text, Link, and File compose → review → explicit send works and preserves the chosen content type | Pending | |
| Android Share text/link/file enters the same review flow before upload | Pending | |
| Returning to the foreground refreshes the first page | Pending | |
| Manual Refresh replaces the first page; Load more appends the next cursor page without duplicates | Pending | |
| Empty inbox, API unavailable, and retry states are understandable and recover after reconnect | Pending | |
| Tap a text item, inspect it, and copy the exact text to clipboard | Pending | |
| Tap an HTTP(S) URL and open it in an external browser; unsafe URL variants do not launch | Pending | |
| Retrieve a PC-sent image, verify it before the Android save picker, and save it to a chosen location | Pending | |
| Retrieve a PC-sent general file, verify it before the Android save picker, and save it to a chosen location | Pending | |
| Cancel the document picker; no partial destination or private temporary file remains | Pending | |
| Interrupt or fail a save; temporary data is removed and the user can retry | Pending | |
| Expired or deleted item returns a safe message and no content | Pending | |
| Revoke the Android session with web **Sign out everywhere**; inbox request requires pairing again | Pending | |
| Confirm attachment response is an attachment with `nosniff`/no-store and exact verified size/hash | Pending | |
| Confirm no push notification, search, preview, item mutation, or persisted inbox cache is required by this slice | Pending | |

## Evidence boundary

Slice 6 physical-device acceptance remains pending. Slice 7 code and automated results do not establish a physical-device pass. The production Slice 5 rollout, Slice 7 production rollout, production backup/restore, internet cross-device proof, and MVP exit gate remain pending until actual owner evidence is added. The six-week product gate must not be marked complete from local tests or implementation alone.
