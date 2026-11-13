# Slice 7 verification

This page covers the Slice 7 Android receive inbox and its physical-device acceptance matrix. It is a checklist, not a current test-result report.

## Automated checks

From the repository root, with Android Studio JBR and Android SDK Platform 36 installed:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT = "$env:USERPROFILE\Android"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
./scripts/test-android.ps1 -JavaHome $env:JAVA_HOME -AndroidSdk $env:ANDROID_SDK_ROOT
```

This runs Android `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. These automated checks cover parsing, paging, request behavior, safe URL validation, attachment verification, and Android build/lint rules represented by tests. They do not verify physical-device behavior, the selected document provider, or cross-device transfer over the public internet.

This page is a test procedure and device checklist, not a pass/fail report. Record run-specific results with the commit and date in CI or a private verification record.

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

This matrix covers Android device acceptance. Record production deployment and restore checks in [production-evidence.md](production-evidence.md).
