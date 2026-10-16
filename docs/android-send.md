# Android send

The paired Vault app supports sending from inside the app and from Android's Share menu. Every send is reviewed before upload.

## Send from inside Vault

1. Open **Send** and choose **Text**, **Link**, or **File**.
2. Enter or select the content. Text preserves its exact contents; links must be complete HTTP(S) URLs; files are copied to Vault's encrypted private spool and limited to 100 MB.
3. Tap **Review send**. Check the full content, destination, and retention period (1–7 days).
4. Tap **Send to Vault**. Use **Activity** to see queued, sending, retrying, failed, and server-accepted sends.

Choosing **Text** keeps URL-looking text as text. Choosing **Link** sends it as a link. Vault does not fetch link previews.

## Send from another Android app

Use that app's **Share** action and select Vault. Share one piece of text or one file at a time. Vault opens the same review flow; it does not upload until **Send to Vault** is tapped. If the phone is not paired, pair it first and share the item again.

## Retry and status

**Activity** reports whether an item needs review, is waiting to send, is sending, is retrying, needs attention, or was accepted by Vault. **Sent to Vault** confirms server acceptance; it does not claim another device has already received or read the item. Failed sends offer Retry and Remove. WorkManager retries eligible network/server failures when Android allows the work to run.

All content remains scoped to the paired account. The selected retention is enforced by Vault after the send is accepted.
