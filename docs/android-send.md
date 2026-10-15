# Android pairing and Share

This is the local Slice 6 client workflow. It does not establish production deployment or real-device acceptance.

## Build prerequisites

- Windows 10/11 with Android Studio and Android SDK Platform 36 / Build Tools installed.
- JDK 17 or newer, matching the repository Gradle wrapper requirements.
- A physical Android phone or emulator for local development. Hardware acceptance is tracked separately in [slice6-verification.md](slice6-verification.md).
- A running Vault Compose stack and a server origin reachable from the phone. Use HTTPS.

From the repository root:

```powershell
docker compose up --build --wait
Set-Location android
./gradlew.bat -PvaultDebugServerOrigin=https://localhost:8080 assembleDebug
```

The release app is hard-pinned to `https://vault.axewhyzedlabs.co.in`. The `vaultDebugServerOrigin` Gradle property configures only the debug app; debug also defaults to the production origin, so pass the property for local work. Do not change the release pin for local tests.

Install the generated debug APK at `android/app/build/outputs/apk/debug/app-debug.apk` using Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Local HTTPS with an Android emulator

The Compose stack binds to `127.0.0.1` by default and its origin defaults to `https://localhost:8080`. Create a mkcert leaf for that hostname, install only mkcert's public root CA in the emulator, and forward its loopback port to the host:

```powershell
./scripts/setup-local.ps1
mkcert -install
mkcert -cert-file .local/tls/dev-cert.pem -key-file .local/tls/dev-cert.key localhost
docker compose up --build --wait
mkcert -CAROOT
adb reverse tcp:8080 tcp:8080
Set-Location android
./gradlew.bat -PvaultDebugServerOrigin=https://localhost:8080 assembleDebug
```

`mkcert -CAROOT` prints the directory containing `rootCA.pem`. Copy only that public certificate to the emulator over a trusted local method and install it as a user CA in Android's security settings. If Compose was already running when the certificate was replaced, recreate it with `docker compose up --build --wait --force-recreate`. Then verify that the browser and app connect at `https://localhost:8080`. The debug Android network security config adds user-installed CAs (including this local mkcert CA) to its trust anchors. Release builds trust system roots only. Never copy `rootCA-key.pem`.

## Local HTTPS on a physical phone

For a phone on the same trusted private LAN, follow the mkcert LAN instructions in the root [README](../README.md): choose a stable private IPv4 address for the PC, issue a certificate whose SAN includes `localhost` and that IP, bind the web port to that address, and set both `VAULT_LAN_ORIGIN` and `VAULT_SERVER_ORIGIN` to the matching `https://<private-ip>:8080` origin. Build Android debug with that identical origin:

```powershell
Set-Location android
./gradlew.bat -PvaultDebugServerOrigin=https://192.168.1.25:8080 assembleDebug
```

Replace the sample IP with the PC's stable LAN address. The certificate SAN, web origin, API `VAULT_SERVER_ORIGIN`, and `vaultDebugServerOrigin` must match exactly. Install only mkcert's public `rootCA.pem` on the phone as a user CA; debug builds trust user-installed CAs, while release builds trust system roots only. Keep any firewall rule limited to the Private profile and LocalSubnet if one is needed. Do not configure router port forwarding.

Do not disable certificate validation, add a trust-all certificate handler, or continue through a browser/app certificate warning. If local CA trust is unavailable on a device, use a trusted HTTPS host for that device test; do not substitute cleartext HTTP.

## Pair Android

1. Sign in to Vault in the web browser and open **Pair Android**.
2. Select **Generate pairing code**. The browser calls `POST /api/v1/devices/pairing-sessions` with cookie authentication, `X-CSRF-TOKEN`, browser Origin, JSON content type, and body `{}`.
3. The API returns `{ "pairingSessionId", "pairingSecret", "serverOrigin", "expiresAt" }`. The web page shows a 10-minute countdown and a QR whose content is JSON: `{ "version": 1, "serverOrigin": "…", "pairingSessionId": "…", "pairingSecret": "…", "expiresAt": "…" }`.
4. In Android, scan the QR or paste the complete QR JSON in the pairing flow. The deep link is `vault://pair?serverOrigin=<percent-encoded-origin>&pairingSessionId=<uuid>`; it intentionally carries only public values, so Android asks for the separate short-lived secret. The paste fallback accepts the full QR JSON or the deep link followed by the secret.
5. Confirm the device name and finish pairing before the countdown reaches zero. The secret is held only in the web page's memory, never placed in a URL, browser storage, or application logs; the page clears it on expiry, sign-out, or unmount. Regenerate an expired code. Creating a new code does not invalidate an earlier unused code; each session remains usable until it expires, is consumed, or all sessions are revoked.

The pairing endpoint requires the signed-in browser cookie plus CSRF and Origin validation. It requires an empty JSON object. Pair requests from Android do not send an ambient web cookie or `Authorization` header. The pair-completion request exchanges the session ID and short-lived secret for Android credentials over HTTPS.

## Share and retry

From another Android app, choose **Share** and select Vault. The receiver accepts text and a single readable `content://` stream. Review the item, choose retention, and press **Send** to queue it. **Cancel** removes the encrypted local draft and spool; it never deletes or modifies the source content. A queued or interrupted upload remains visible in the Vault app's status; choose retry for that same share intent. Retry uses its original idempotency key, so an ambiguous/lost response does not create a second item. A new Share action creates a new intent and key. A source app may revoke access to a temporary file URI; if Vault can no longer read it, select the source again and start a new share.

Open the signed-in web inbox to confirm the new item. Files appear as attachment metadata and download as attachments; Vault does not preview file bytes.

## Sign out everywhere

The web inbox has a visible **Sign out everywhere** control. Confirming it sends `POST /api/v1/auth/sessions/revoke-all` with the browser cookie, fresh CSRF token, Origin, and `{}`. On `204`, the current browser is signed out and Android refresh credentials are revoked. Sign in again on each device you want to use.
