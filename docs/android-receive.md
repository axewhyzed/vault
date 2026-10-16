# Android receive inbox

Slice 7 adds a read-only Android view of recent items owned by the paired Vault account. The inbox is retrieved when the paired app opens or returns to the foreground, and after pairing. It also provides **Refresh** and cursor-based **Load more** controls. The inbox list lives only in the current UI session; the app does not persist or serve a cached inbox.

## Navigation and item actions

The paired app opens on **Inbox**. **Inbox** is a tab beside **Recent sends**; the existing status and sign-out controls remain above the tabs. Tap an item row to open its detail view, then use the action for its type:

- **Text:** inspect and copy the text to the Android clipboard.
- **URL:** open only a valid absolute HTTP or HTTPS URL, with no credentials, in an external app such as the browser. Vault does not fetch the URL or show a preview.
- **File or image:** choose **Download attachment**. Vault streams the response into a private temporary file, checks the received byte count and SHA-256 against the item metadata, then opens Android's **Create document** picker with a sanitized suggested filename. The temporary file is removed after save, cancellation, or failure; the selected destination is also cleaned up if writing fails where the document provider allows it.

The inbox does not support push notifications, search, previews, item edits, or deletion. It reads only the first recent page on opening/foregrounding; additional pages are fetched only when the user selects **Load more**. Copy and open are rejected when the item has expired. API authorization and expiry checks remain authoritative if an item's state changes after it was listed.

## API and implementation paths

All requests use the paired device's bearer credential, refresh it through the existing token flow as needed, and send `Cache-Control: no-store`. They do not use browser cookies or CSRF tokens.

- `GET /api/v1/items?limit=30&cursor=…` returns the authenticated owner's active, unexpired items newest first. The API accepts `limit` from 1 through 100, returns `nextCursor` when another page exists, and includes quota counts. The Android client sends a cursor as one encoded query parameter.
- `GET /api/v1/items/{id}` retrieves one item detail. Text and URL details include their text value. File details include the canonical relative download URL, content type, and SHA-256 metadata.
- `GET /api/v1/items/{id}/download` streams an owned file as an attachment. The Android client requires the canonical item download route, successful attachment response, `nosniff`, and no-store headers; it rejects partial-content responses and verifies size (at most 100,000,000 bytes) and SHA-256 before making the file available to the save picker.

Relevant code is in [MainActivity.kt](../android/app/src/main/java/app/vault/android/MainActivity.kt), [InboxUi.kt](../android/app/src/main/java/app/vault/android/InboxUi.kt), [InboxModels.kt](../android/app/src/main/java/app/vault/android/InboxModels.kt), [VaultApi.kt](../android/app/src/main/java/app/vault/android/VaultApi.kt), [TransferEndpoints.cs](../src/api/Vault.Api/Modules/Transfers/TransferEndpoints.cs), and [FileEndpoints.cs](../src/api/Vault.Api/Modules/Storage/FileEndpoints.cs).

## Local Android build and automated checks

On this Windows development machine, use Android Studio's bundled JBR and the installed SDK Platform 36. From the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT = "$env:USERPROFILE\Android"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
./scripts/test-android.ps1 -JavaHome $env:JAVA_HOME -AndroidSdk $env:ANDROID_SDK_ROOT
```

The script runs `assembleDebug`, `testDebugUnitTest`, and `lintDebug` using the Gradle wrapper. To invoke those Gradle tasks directly with the same local JBR/SDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SDK_ROOT = "$env:USERPROFILE\Android"
$env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
Push-Location android
try { ./gradlew.bat --no-daemon --console=plain assembleDebug testDebugUnitTest lintDebug } finally { Pop-Location }
```

Automated checks cover API/client behavior and local build, unit-test, and lint tasks; they do not prove that an Android document provider, browser, certificate store, or physical device behaves as expected. Follow the separate [Slice 7 verification record](slice7-verification.md) for the pending manual acceptance matrix. Slice 6 physical-device evidence, production Slice 5/7 rollout, production backup/restore, internet phone-to-PC proof, and the overall MVP gate remain pending until recorded from actual runs.
