# Android test coverage and follow-up

This record tracks automated Android client coverage. Passing local builds and Robolectric tests do not prove scanner hardware, Android system bars, browser launch, external content providers, or real device-network behavior. Physical checks are tracked in [slice7-verification.md](slice7-verification.md).

## Current automated coverage

Last local command: `./scripts/test-android.ps1`

Result: `assembleDebug`, `testDebugUnitTest` (52 tests), and `lintDebug` passed.

| Feature area | Automated coverage | Main remaining checks |
|---|---|---|
| Pairing | QR/deep-link parsing, configured HTTPS origin, secret shape/expiry, pairing API contract, Compose scan/paste control affordances | Real camera permission/scanning and pairing on phone |
| In-app sending | Compose tests for Send/Text/Link/File choices, text/link validation, review, retention, explicit Send, and discard confirmation | MainActivity route integration, real file picker, 200% font scale/TalkBack |
| Android Share | Payload action/MIME/URI rules, duplicate/multiple streams, byte/name/MIME constraints, encrypted spool; share routes to Review | Real Chrome/Gallery/Files/chat share intents, URI grants and Activity recreation |
| Content type and API | Explicit Text stays text; Link calls URL endpoint; file initiate → stream → complete; idempotency, auth refresh, upload ID/progress preservation | Live-service response agreement for server throttling and failures |
| WorkManager | Retry test proves file upload resumes with the persisted upload ID/key and removes spool on success | OS scheduling after force-stop, reboot, Doze and connectivity handoff |
| Activity | Local owner scoping/order, retry and removal helpers, status labels | MainActivity state rendering and accessibility on physical screen |
| Inbox | List/detail/download API contracts, pagination cursor encoding, Compose empty/detail/copy flow, safe links, hash/size/header checks | Multi-page UI, foreground/offline recovery, external browser, actual save picker/provider |
| Account/security | Encrypted local storage, refresh/revoke contracts, release demo-mode guard, saved intent policy | Hardware Keystore behavior and sign-out racing real background operations |
| System UI | `enableEdgeToEdge`, Material 3 `Scaffold`, top/bottom bar inset ownership, `adjustResize`, composer IME insets compile and lint | Visual confirmation across API 35/36, gesture/three-button navigation, cutouts and IME |

## Focused follow-up

1. Add Activity-level tests for pairing, cold/warm `ACTION_SEND`, navigation, review/send, Activity states, and sign-out routing.
2. Test the Inbox UI’s pagination, foreground refresh, API error/retry, URL intent dispatch and save-picker lifecycle.
3. Use an emulator/phone to verify system-bar geometry, keyboard overlap, large text, TalkBack, camera/QR, clipboard/browser and document providers.
4. Keep device-specific tests distinct from fast local tests; do not make the server/web deploy workflow run Android checks.

## Recent product fixes

- Paired navigation is **Send / Inbox / Activity**; in-app send supports Text, Link and File.
- In-app content type is explicit, so URL-looking text remains text.
- In-app sends and Android Share both reach the same full-content review and retention step before upload.
- Android Share opens Review after preparing the item; restored Activity does not consume its launch intent again.
- Recent local sends are scoped to the paired owner and chronologically sorted.
- File progress preserves the upload ID and idempotency key through WorkManager retry.
- The exported demo path is debug-only; pairing is busy guarded and masks the manual secret.
- App chrome uses edge-to-edge Material 3 scaffold insets; physical visual acceptance remains pending.
