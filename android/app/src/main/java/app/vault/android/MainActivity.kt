package app.vault.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.core.content.IntentCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val store by lazy { EncryptedStore(this) }
    private var statusMessage by mutableStateOf("")
    private var pasteText by mutableStateOf("")
    private var payload by mutableStateOf<PairingPayload?>(null)
    private var pairingDeadline by mutableStateOf<Long?>(null)
    private var secret by mutableStateOf("")
    private var deviceName by mutableStateOf(android.os.Build.MODEL.take(100))
    private var refreshCounter by mutableIntStateOf(0)
    private var foregroundToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        setContent { VaultScreen() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIncoming(intent) }

    override fun onResume() { super.onResume(); foregroundToken++ }

    private fun parsePairing(raw: String) {
        try {
            val parsed = PairingPayload.parse(raw, BuildConfig.PAIRING_SERVER_ORIGIN)
            payload = parsed
            pairingDeadline = parsed.expiresAt?.let { java.time.Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis() + 10 * 60_000L
            secret = parsed.pairingSecret.orEmpty()
            statusMessage = "Pairing details ready."
        } catch (e: Exception) { payload = null; pairingDeadline = null; secret = ""; statusMessage = e.message ?: "Could not read pairing details." }
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent?.getBooleanExtra("vault_demo_mode", false) == true) {
            val origin = intent.getStringExtra("vault_demo_origin") ?: BuildConfig.PAIRING_SERVER_ORIGIN
            store.saveCredentials(Credentials(
                origin = origin,
                accessToken = "demo_access_token",
                accessExpiry = "2099-01-01T00:00:00Z",
                refreshToken = "demo_refresh_token",
                refreshExpiry = "2099-01-01T00:00:00Z",
                userId = "usr_owner_01",
                deviceId = "dev_phone_01"
            ))
            store.saveDraft(Draft(
                id = UUID.randomUUID().toString(),
                idempotencyKey = UUID.randomUUID().toString(),
                serverOrigin = origin,
                ownerUserId = "usr_owner_01",
                displayName = "quarterly-infrastructure-report.pdf",
                contentType = "application/pdf",
                sizeBytes = 8420000,
                bytesSent = 8420000,
                state = DraftState.AVAILABLE
            ))
            store.saveDraft(Draft(
                id = UUID.randomUUID().toString(),
                idempotencyKey = UUID.randomUUID().toString(),
                serverOrigin = origin,
                ownerUserId = "usr_owner_01",
                displayName = "Tailscale Exit Nodes & WireGuard Config",
                text = "Server: 192.168.1.100\nTailscale exit: 100.64.0.1",
                contentType = "text/plain",
                sizeBytes = 284,
                bytesSent = 284,
                state = DraftState.AVAILABLE
            ))
            statusMessage = "Paired with Vault server."
            refreshCounter++
        }
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let(::parsePairing)
        if (intent?.action != Intent.ACTION_SEND) return
        lifecycleScope.launch {
            try {
                val uris = mutableListOf<Uri>()
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
                val unique = uris.distinctBy { it.toString() }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                val share = SharePayload.from(intent.action, intent.type, text, unique.map(Uri::toString))
                val credentials = store.credentials() ?: throw IllegalStateException("Pair this device before sharing.")
                val origin = credentials.origin
                val draftId = UUID.randomUUID().toString()
                val draft = withContext(Dispatchers.IO) {
                    if (share.contentUri != null) {
                        val uri = Uri.parse(share.contentUri)
                        val metadata = contentMetadata(uri)
                        val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("The shared file is no longer available. Share it again.")
                        val spoolName = UUID.randomUUID().toString()
                        val result = input.use { store.createSpool(spoolName, it) }
                        Draft(draftId, UUID.randomUUID().toString(), origin, credentials.userId, spoolName = spoolName, displayName = metadata.first, contentType = metadata.second, sizeBytes = result.sizeBytes, sha256 = result.sha256)
                    } else {
                        val value = share.text!!
                        require(value.toByteArray(Charsets.UTF_8).size <= 262_144) { "Text exceeds the 256 KB limit." }
                        Draft(draftId, UUID.randomUUID().toString(), origin, credentials.userId, text = value, displayName = "Text snippet", contentType = "text/plain", sizeBytes = value.toByteArray(Charsets.UTF_8).size.toLong())
                    }
                }
                draftQueue().prepare(draft)
                statusMessage = "Review your send before uploading."
                refreshCounter++
            } catch (e: Exception) { statusMessage = e.message ?: "Could not accept this share." }
        }
    }

    private fun contentMetadata(uri: Uri): Pair<String, String> {
        var name = "shared-file"
        var size: Long? = null
        runCatching { contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) name = cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } ?: name
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
            }
        } }
        require(size == null || size!! <= Spooler.MAX_FILE_BYTES) { "Files may not exceed 100 MB." }
        name = name.substringAfterLast('/').substringAfterLast('\\').filterNot { Character.isISOControl(it) }.take(200).ifBlank { "shared-file" }
        val mime = contentResolver.getType(uri)?.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) } ?: "application/octet-stream"
        return name to mime
    }

    @Composable private fun VaultScreen() {
        val scope = rememberCoroutineScope()
        val credentials = remember(refreshCounter) { store.credentials() }
        val drafts = remember(refreshCounter) { store.allDrafts() }
        var selectedTab by remember(credentials?.userId) { mutableStateOf("Inbox") }
        LaunchedEffect(credentials?.deviceId, drafts.any { it.state == DraftState.QUEUED || it.state == DraftState.UPLOADING || it.state == DraftState.RETRYING }) {
            while (credentials != null && drafts.any { it.state == DraftState.QUEUED || it.state == DraftState.UPLOADING || it.state == DraftState.RETRYING }) {
                delay(1000); refreshCounter++
            }
        }
        LaunchedEffect(credentials?.deviceId) {
            if (credentials != null) store.allDrafts().filter { it.state == DraftState.QUEUED }.forEach { UploadWorker.enqueue(this@MainActivity, it.id) }
        }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::parsePairing) }
        LaunchedEffect(payload?.pairingSessionId, pairingDeadline) {
            val sessionId = payload?.pairingSessionId ?: return@LaunchedEffect
            val deadline = pairingDeadline ?: return@LaunchedEffect
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            if (payload?.pairingSessionId == sessionId && System.currentTimeMillis() >= deadline) {
                payload = null; pairingDeadline = null; secret = ""; pasteText = ""; statusMessage = "Pairing code expired. Create a fresh QR code."
            }
        }
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Vault", style = MaterialTheme.typography.headlineMedium)
                    if (credentials == null) {
                        Text("Pair this phone with your Vault server to send items to your inbox.")
                        OutlinedTextField(pasteText, { pasteText = it }, label = { Text("Paste QR data or vault://pair link") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { parsePairing(pasteText) }) { Text("Continue") }
                            OutlinedButton(onClick = { scanner.launch(ScanOptions().setPrompt("Scan Vault pairing QR").setBeepEnabled(false).setOrientationLocked(false)) }) { Text("Scan QR") }
                        }
                        payload?.let { selected ->
                            Text("Server: ${selected.serverOrigin}")
                            if (selected.pairingSecret == null) OutlinedTextField(secret, { secret = it }, label = { Text("10-minute pairing secret") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(deviceName, { deviceName = it.take(100) }, label = { Text("Device name") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { scope.launch {
                                if ((pairingDeadline ?: 0L) <= System.currentTimeMillis()) { payload = null; secret = ""; pasteText = ""; statusMessage = "Pairing code expired. Create a fresh QR code."; return@launch }
                                statusMessage = "Pairing…"
                                try { val paired = withContext(Dispatchers.IO) { vaultApi(this@MainActivity).pair(selected, secret.trim(), deviceName.trim()) }; store.saveCredentials(paired); payload = null; pairingDeadline = null; secret = ""; pasteText = ""; statusMessage = "This phone is paired."; refreshCounter++ }
                                catch (e: java.io.IOException) { statusMessage = "Pairing response was lost. Create a fresh pairing QR and pair again." }
                                catch (e: Exception) { statusMessage = e.message ?: "Pairing failed. Create a fresh pairing QR if this code was already used." }
                            } }, enabled = secret.isNotBlank() && deviceName.isNotBlank()) { Text("Pair phone") }
                        }
                    } else {
                        Text("Paired with ${credentials.origin}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedTab == "Inbox", onClick = { selectedTab = "Inbox" }, label = { Text("Inbox") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(selected = selectedTab == "Recent sends", onClick = { selectedTab = "Recent sends" }, label = { Text("Recent sends") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { refreshCounter++ }) { Text("Refresh status") }
                            Button(onClick = { scope.launch {
                                statusMessage = "Signing out everywhere…"
                                try { withContext(Dispatchers.IO) { vaultApi(this@MainActivity).revokeAll() }; statusMessage = "All sessions were signed out." }
                                catch (e: Exception) { statusMessage = e.message ?: "Could not revoke sessions." }
                                refreshCounter++
                            } }) { Text("Sign out everywhere") }
                        }
                        if (selectedTab == "Inbox") {
                            InboxTab(vaultApi(this@MainActivity), store, "${credentials.origin}|${credentials.userId}|${credentials.deviceId}", foregroundToken, Modifier.weight(1f))
                        } else {
                            Text("Recent sends", style = MaterialTheme.typography.titleLarge)
                            if (drafts.isEmpty()) Text("Shared items will appear here.")
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(drafts, key = { it.id }) { draft ->
                                if (draft.state == DraftState.PREPARED) {
                                    PreparedSendCard(draft,
                                        onSend = { retention -> draftQueue().send(draft.id, retention); statusMessage = "Added to your upload queue."; refreshCounter++ },
                                        onCancel = { draftQueue().cancel(draft.id); statusMessage = "Prepared send removed."; refreshCounter++ })
                                } else Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                    Text(draft.displayName, style = MaterialTheme.typography.titleMedium)
                                    val percent = if (draft.sizeBytes > 0) ((draft.bytesSent.coerceAtMost(draft.sizeBytes) * 100) / draft.sizeBytes).toInt() else 0
                                    Text(if (draft.state == DraftState.UPLOADING && draft.sizeBytes > 0) "uploading · $percent%" else draft.state.name.lowercase().replace('_', ' '))
                                    draft.safeError?.let { Text(it) }
                                    if (draft.state == DraftState.FAILED) TextButton(onClick = { store.saveDraft(draft.copy(state = DraftState.QUEUED, safeError = null)); UploadWorker.enqueue(this@MainActivity, draft.id); refreshCounter++ }) { Text("Retry") }
                                    if (draft.state == DraftState.FAILED) TextButton(onClick = { UploadWorker.cancel(this@MainActivity, draft.id); store.deleteDraft(draft.id); refreshCounter++ }) { Text("Remove failed send") }
                                } }
                            }
                        }
                        }
                    }
                    if (statusMessage.isNotBlank()) Text(statusMessage, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    private fun draftQueue() = DraftQueue(store,
        enqueue = { UploadWorker.enqueue(this, it) },
        cancelWork = { UploadWorker.cancel(this, it) })

    @Composable private fun PreparedSendCard(draft: Draft, onSend: (Int) -> Unit, onCancel: () -> Unit) {
        var retention by remember(draft.id) { mutableIntStateOf(draft.retentionDays) }
        var expanded by remember(draft.id) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ready to send", style = MaterialTheme.typography.titleMedium)
                Text(draft.displayName)
                if (draft.text != null) Text(draft.text.take(240), maxLines = 3)
                else Text("${draft.contentType} · ${draft.sizeBytes} bytes")
                Text("Destination: My Relay inbox")
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text("Keep for $retention day${if (retention == 1) "" else "s"} ▾") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        (1..7).forEach { days -> DropdownMenuItem(text = { Text("$days day${if (days == 1) "" else "s"}") }, onClick = { retention = days; expanded = false }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSend(retention) }) { Text("Send") }
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}
