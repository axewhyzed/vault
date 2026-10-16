# Android test coverage and follow-up

This record tracks automated coverage for the Android client. A passing local build or Robolectric test does not establish that scanner hardware, Android background scheduling, browser/clipboard integration, document providers, or real network behavior works on a phone. Those checks remain in the physical-device matrix in [slice7-verification.md](slice7-verification.md).

## Current automated coverage

Last local command: `./scripts/test-android.ps1`

Result: `assembleDebug`, `testDebugUnitTest` (47 tests), and `lintDebug` passed.

| Feature area | Automated coverage | Main remaining checks |
|---|---|---|
| Pairing payload | QR/deep-link parsing, configured HTTPS origin, secret shape, expiry bounds, duplicate parameters, secret excluded from deep link | Real QR scan/camera permission; activity recreation during pairing; real pairing against production |
| Pairing API | Pair request path/body, idempotency header, no cookie/bearer, token response validation | Pairing rate limit and expired/replayed session against live stack |
| Share intake | Supported action/MIME/URI rules, duplicate and multiple streams, UTF-8 text limit, file size/name/MIME validation, spool byte cap/hash | Real Chrome/Gallery/Files/chat share intents; provider grants, revoked streams, and unknown metadata on device |
| Send review and queue | Retention and prepared/cancel queue state; Compose test verifies explicit Send, selected retention, and Cancel | Full activity navigation from ACTION_SEND; screen reader/focus behavior; cold-start share during process recreation |
| Upload API | Text idempotency/refresh tests; file initiate → streamed content → complete; progress and failed upload state preserve upload id | Live service cross-check for all server failure responses and largest-file performance |
| WorkManager | Test worker executes a retry after file upload progress, retains upload id/key, resumes without re-initiation, and removes spool after success | OS scheduling after app force-stop, device reboot, Doze, and connectivity handoff |
| Credentials and local data | Encrypted store round-trip, account-scoped Recent sends, chronological ordering, concurrent refresh, global revoke request/success/failure | Keystore behavior across supported OEM/API versions; sign-out racing real background work and account changes |
| Inbox API | List cursor encoding, refresh-on-401 once, bounded limit, detail parsing, malformed response, expired item, owner mismatch | Backend contract drift remains covered by the separate API integration suite |
| Inbox UI | Compose/Robolectric tests for empty state, refresh affordance, detail navigation, exact text display/copy | Populated multi-page list, foreground refresh, flaky/offline errors, URL external launch, actual picker/save lifecycle |
| Attachment download | No redirect/cookies, authenticated route, required response headers, exact byte count, max size, hash mismatch and temp cleanup | Real Android filesystem pressure, provider failures, process death while picker is open |
| Activity safety | Launch intent processing policy and debug-only demo-mode policy | Full ActivityScenario tests for incoming intents and exported component behavior on device |

## Follow-up automated test work

1. Add screen/state tests for pairing success/error/expiry, share-to-review navigation and intent recreation, all Recent sends states, inbox pagination/error/retry/foreground refresh, URL-open dispatch, and sign-out confirmation.
2. Expand MockWebServer cases for every endpoint's 401/403/404/410/413/429/5xx behavior, malformed payloads, response loss, and refresh races.
3. Add lifecycle tests for cancellation and stale responses after Back, owner change, sign-out, and activity recreation. Define cleanup ownership for private download temp files across process death.
4. Add test seams only where needed: narrow API/action interfaces, injected time/dispatchers, and worker dependencies. Keep the tests deterministic and keep Android CI scoped to `android/**`.
5. Run the physical-device matrix separately: Chrome text/URL, Gallery image, Files document, QR scanning, clipboard/browser, document provider save/cancel/failure, Keystore, worker retry after backgrounding/process death, Wi-Fi/cellular/offline recovery, expiry/revocation, and both internet transfer directions.

## Recent regressions guarded

- A successful share selects **Recent sends** so its review card is visible.
- A restored Activity does not consume its launch intent again; pairing state is restored without putting the pairing secret in saved instance state.
- Release builds ignore the demo-mode intent flag.
- Recent sends are scoped to the paired origin/account and sorted by recorded creation time.
- File upload progress updates preserve the server upload id and idempotency key across a retry.
- Pairing has a busy guard, masks the manual secret field, and scrolls on compact screens. Global sign-out requires confirmation.
