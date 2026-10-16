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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    private var selectedTab by mutableStateOf("Inbox")
    private var pairingInProgress by mutableStateOf(false)
    private var signOutConfirmation by mutableStateOf(false)
    private var signOutInProgress by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val processLaunchIntent = shouldHandleLaunchIntent(savedInstanceState != null)
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getString(STATE_SELECTED_TAB, "Inbox")
            pasteText = ""
            secret = ""
            deviceName = savedInstanceState.getString(STATE_DEVICE_NAME, deviceName)
            pairingDeadline = savedInstanceState.takeIf { it.containsKey(STATE_PAIRING_DEADLINE) }?.getLong(STATE_PAIRING_DEADLINE)
            val serverOrigin = savedInstanceState.getString(STATE_PAIRING_ORIGIN)
            val sessionId = savedInstanceState.getString(STATE_PAIRING_SESSION)
            if (serverOrigin != null && sessionId != null) payload = PairingPayload(serverOrigin, sessionId, expiresAt = savedInstanceState.getString(STATE_PAIRING_EXPIRY))
            statusMessage = savedInstanceState.getString(STATE_STATUS, "")
        }
        if (processLaunchIntent) handleIncoming(intent)
        setIntent(Intent(this, MainActivity::class.java))
        setContent { VaultScreen() }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncoming(intent); setIntent(Intent(this, MainActivity::class.java)) }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_TAB, selectedTab)
        outState.putString(STATE_DEVICE_NAME, deviceName)
        outState.putString(STATE_STATUS, statusMessage)
        pairingDeadline?.let { outState.putLong(STATE_PAIRING_DEADLINE, it) }
        payload?.let {
            outState.putString(STATE_PAIRING_ORIGIN, it.serverOrigin)
            outState.putString(STATE_PAIRING_SESSION, it.pairingSessionId)
            outState.putString(STATE_PAIRING_EXPIRY, it.expiresAt)
        }
        super.onSaveInstanceState(outState)
    }

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
        if (shouldApplyDemoMode(BuildConfig.DEBUG, intent?.getBooleanExtra("vault_demo_mode", false) == true)) {
            val origin = intent?.getStringExtra("vault_demo_origin") ?: BuildConfig.PAIRING_SERVER_ORIGIN
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
                        require(isShareTextWithinLimit(value)) { "Text exceeds the 256 KB limit." }
                        Draft(draftId, UUID.randomUUID().toString(), origin, credentials.userId, text = value, displayName = "Text snippet", contentType = "text/plain", sizeBytes = value.toByteArray(Charsets.UTF_8).size.toLong())
                    }
                }
                draftQueue().prepare(draft)
                selectedTab = "Recent sends"
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
        validateSharedFileSize(size)
        return safeSharedFileName(name) to safeSharedMimeType(contentResolver.getType(uri))
    }

    @Composable private fun VaultScreen() {
        val scope = rememberCoroutineScope()
        val credentials = remember(refreshCounter) { store.credentials() }
        val drafts = remember(refreshCounter, credentials?.origin, credentials?.userId) { store.draftsFor(credentials?.origin, credentials?.userId) }
        LaunchedEffect(credentials?.deviceId, drafts.any { it.state == DraftState.QUEUED || it.state == DraftState.UPLOADING || it.state == DraftState.RETRYING }) {
            while (credentials != null && drafts.any { it.state == DraftState.QUEUED || it.state == DraftState.UPLOADING || it.state == DraftState.RETRYING }) {
                delay(1000); refreshCounter++
            }
        }
        LaunchedEffect(credentials?.deviceId) {
            if (credentials != null) store.draftsFor(credentials.origin, credentials.userId).filter { it.state == DraftState.QUEUED }.forEach { UploadWorker.enqueue(this@MainActivity, it.id) }
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
                val screenModifier = Modifier.fillMaxSize().padding(20.dp).then(
                    if (credentials == null) Modifier.verticalScroll(rememberScrollState()) else Modifier
                )
                Column(screenModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Vault", style = MaterialTheme.typography.headlineMedium)
                    if (credentials == null) {
                        PairingCodeEntry(
                            value = pasteText,
                            onValueChange = { pasteText = it },
                            onContinue = { parsePairing(pasteText) },
                            onScan = { scanner.launch(ScanOptions().setPrompt("Scan Vault pairing QR").setBeepEnabled(false).setOrientationLocked(false)) },
                        )
                        payload?.let { selected ->
                            Text("Server: ${selected.serverOrigin}")
                            if (selected.pairingSecret == null) OutlinedTextField(secret, { secret = it }, label = { Text("10-minute pairing secret") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(deviceName, { deviceName = it.take(100) }, label = { Text("Device name") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                if (pairingInProgress) return@Button
                                pairingInProgress = true
                                scope.launch {
                                    try {
                                        if ((pairingDeadline ?: 0L) <= System.currentTimeMillis()) { payload = null; secret = ""; pasteText = ""; statusMessage = "Pairing code expired. Create a fresh QR code."; return@launch }
                                        statusMessage = "Pairing…"
                                        try {
                                            val paired = withContext(Dispatchers.IO) { vaultApi(this@MainActivity).pair(selected, secret.trim(), deviceName.trim()) }
                                            store.saveCredentials(paired); payload = null; pairingDeadline = null; secret = ""; pasteText = ""; selectedTab = "Inbox"; statusMessage = "This phone is paired."; refreshCounter++
                                        } catch (e: java.io.IOException) { statusMessage = "Pairing response was lost. Create a fresh pairing QR and pair again." }
                                        catch (e: Exception) { statusMessage = e.message ?: "Pairing failed. Create a fresh pairing QR if this code was already used." }
                                    } finally { pairingInProgress = false }
                                }
                            }, enabled = secret.isNotBlank() && deviceName.isNotBlank() && !pairingInProgress) { Text(if (pairingInProgress) "Pairing…" else "Pair phone") }
                        }
                    } else {
                        Text("Paired with ${credentials.origin}")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = selectedTab == "Inbox", onClick = { selectedTab = "Inbox" }, label = { Text("Inbox") }, modifier = Modifier.heightIn(min = 48.dp))
                            FilterChip(selected = selectedTab == "Recent sends", onClick = { selectedTab = "Recent sends" }, label = { Text("Recent sends") }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (selectedTab == "Recent sends") OutlinedButton(onClick = { refreshCounter++ }) { Text("Refresh sends") }
                            Button(onClick = { signOutConfirmation = true }, enabled = !signOutInProgress) { Text(if (signOutInProgress) "Signing out…" else "Sign out everywhere") }
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
            if (signOutConfirmation) {
                SignOutConfirmationDialog(
                    busy = signOutInProgress,
                    onDismiss = { signOutConfirmation = false },
                    onConfirm = {
                        signOutConfirmation = false
                        signOutInProgress = true
                        scope.launch {
                            statusMessage = "Signing out everywhere…"
                            try { withContext(Dispatchers.IO) { vaultApi(this@MainActivity).revokeAll() }; statusMessage = "All sessions were signed out." }
                            catch (e: Exception) { statusMessage = e.message ?: "Could not revoke sessions." }
                            finally { signOutInProgress = false; refreshCounter++ }
                        }
                    },
                )
            }
        }
    }

    private fun draftQueue() = DraftQueue(store,
        enqueue = { UploadWorker.enqueue(this, it) },
        cancelWork = { UploadWorker.cancel(this, it) })

    companion object {
        private const val STATE_SELECTED_TAB = "vault.selectedTab"
        private const val STATE_DEVICE_NAME = "vault.deviceName"
        private const val STATE_STATUS = "vault.status"
        private const val STATE_PAIRING_DEADLINE = "vault.pairingDeadline"
        private const val STATE_PAIRING_ORIGIN = "vault.pairingOrigin"
        private const val STATE_PAIRING_SESSION = "vault.pairingSession"
        private const val STATE_PAIRING_EXPIRY = "vault.pairingExpiry"
    }

}
