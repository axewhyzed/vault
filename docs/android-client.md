# Android Client Engineering Guide

The native Android client provides a secure, private clipboard and file drop interface with three main destinations: **Send**, **Inbox**, and **Activity**. It is built with Jetpack Compose, WorkManager, and OkHttp.

---

## 1. Sending Content

Vault supports sending from within the application and directly from Android's system **Share** sheet. Every send is reviewed in a confirmation dialog before upload.

### Send from Inside Vault
1. Open **Send** and select **Text**, **Link**, or **File**.
2. Provide content:
   - **Text:** Preserves raw character formatting and line breaks.
   - **Link:** Must be a valid absolute HTTP or HTTPS URL (Vault does not scrape link previews).
   - **File:** Spooled into Vault's private cache and capped at 100 MB.
3. Tap **Review send** to confirm the payload, destination, and retention period (1 to 7 days).
4. Tap **Send to Vault**. You can monitor the upload in the **Activity** tab.

### Send via Android Share Sheet
From any Android application (e.g., Chrome, Gallery, Files), tap **Share** and select **Vault**. Vault opens the review dialog with the shared content staged. If the device is not paired, pair it first and share the item again.

### Background Queue & Offline Outbox
- **Offline Outbox:** If your device has no network connection, sends are queued locally.
- **WorkManager:** Background tasks retry eligible network failures with exponential backoff as soon as Android detects active connectivity.
- **Activity Status:** Reports whether an item is pending review, waiting to send, transmitting, retrying, needs attention, or was accepted by the server.

---

## 2. Receiving Content (Inbox)

The Inbox displays active items owned by the paired Vault account. The item list is retrieved when the app opens or returns to the foreground, with manual **Refresh** and cursor-based **Load more** options.

### Item Actions
- **Text:** Inspect full contents and copy to the system clipboard.
- **URL:** Open absolute HTTP or HTTPS URLs in the default browser.
- **Files & Images:** Choose **Download attachment**. The app streams the download into a private temporary file, verifies the received byte count and SHA-256 against item metadata, and opens Android's Storage Access Framework (`Create document` picker) to save the file. The temporary file is immediately removed.

### Constraints & Security
- The app enforces `nosniff` and `no-store` headers and rejects partial responses.
- Expired items cannot be viewed or downloaded.
- The inbox list is held in memory for the active UI session; no unencrypted item database is stored on disk.

---

## 3. Authentication & API Contracts

All mobile requests use the paired device's Bearer token credential, refresh it transparently through the token refresh flow, and send `Cache-Control: no-store`. The Android client does not use browser cookies or anti-CSRF tokens.

### Key Endpoints
- `GET /api/v1/items?limit=30&cursor=...`: Returns the authenticated owner's active, unexpired items newest first, with pagination cursor and quota usage.
- `GET /api/v1/items/{id}`: Retrieves single item detail, text payload, or file metadata (canonical download route, MIME type, SHA-256 hash).
- `GET /api/v1/items/{id}/download`: Streams an owned file as an attachment. The client verifies the SHA-256 checksum and enforces the 100 MB limit before opening the document picker.

### Code Organization
- `MainActivity.kt`: Root container, edge-to-edge Compose layout, and navigation scaffold.
- `InboxUi.kt` & `InboxModels.kt`: Inbox presentation, attachment streaming, and download triggers.
- `VaultApi.kt`: OkHttp networking, token refresh interceptor, and API serialization.

---

## 4. Local Build & Automated Verification

To build and verify the Android client locally, use the repository's root verification script:

```powershell
./scripts/test-android.ps1
```

This script automatically discovers the local Android SDK and Java installation, running:
- `assembleDebug`: Verifies clean compilation and APK packaging.
- `testDebugUnitTest`: Executes the complete local unit test suite (testing API communication and token lifecycles with `MockWebServer`).
- `lintDebug`: Verifies Android lint rules with zero warnings treated as errors.
