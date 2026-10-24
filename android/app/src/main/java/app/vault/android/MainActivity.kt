package app.vault.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.util.UUID
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send

private enum class VaultDestination { SEND, INBOX, HUB }
private enum class NoticeTone { INFO, SUCCESS, ERROR }

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
    private var selectedDestination by mutableStateOf(VaultDestination.SEND)
    private var composeKind by mutableStateOf<ComposeKind?>(null)
    private var composeText by mutableStateOf("")
    private var composeName by mutableStateOf("")
    private var composeFileDraft by mutableStateOf<Draft?>(null)
    private var editingDraftId by mutableStateOf<String?>(null)
    private var reviewDraftId by mutableStateOf<String?>(null)
    private var accountScreen by mutableStateOf(false)
    private var preparingFile by mutableStateOf(false)
    private var pairingInProgress by mutableStateOf(false)
    private var signOutConfirmation by mutableStateOf(false)
    private var signOutInProgress by mutableStateOf(false)
    private var userSignedOut by mutableStateOf(false)
    private var statusTone by mutableStateOf(NoticeTone.INFO)
    private var hubPingLatencyMs by mutableStateOf<Long?>(null)
    private var hubHasPinged by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val processLaunchIntent = shouldHandleLaunchIntent(savedInstanceState != null)
        if (savedInstanceState != null) {
            selectedDestination = runCatching { VaultDestination.valueOf(savedInstanceState.getString(STATE_SELECTED_TAB, "SEND")) }.getOrDefault(VaultDestination.SEND)
            reviewDraftId = savedInstanceState.getString(STATE_REVIEW_DRAFT_ID)
            accountScreen = savedInstanceState.getBoolean(STATE_ACCOUNT_SCREEN, false)
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
        outState.putString(STATE_SELECTED_TAB, selectedDestination.name)
        outState.putString(STATE_REVIEW_DRAFT_ID, reviewDraftId)
        outState.putBoolean(STATE_ACCOUNT_SCREEN, accountScreen)
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

    private fun showStatus(message: String, tone: NoticeTone = NoticeTone.INFO) {
        statusMessage = message
        statusTone = tone
    }

    private fun parsePairing(raw: String) {
        try {
            val parsed = PairingPayload.parse(raw)
            payload = parsed
            pairingDeadline = parsed.expiresAt?.let { java.time.Instant.parse(it).toEpochMilli() } ?: System.currentTimeMillis() + 10 * 60_000L
            secret = parsed.pairingSecret.orEmpty()
            showStatus("Pairing details ready.")
        } catch (e: Exception) { payload = null; pairingDeadline = null; secret = ""; showStatus(e.message ?: "Could not read pairing details.", NoticeTone.ERROR) }
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
            showStatus("Paired with Vault server.", NoticeTone.SUCCESS)
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
                val credentials = store.credentials() ?: throw IllegalStateException("Pair this phone first, then share the item again.")
                val origin = credentials.origin
                val draftId = UUID.randomUUID().toString()
                val draft = withContext(Dispatchers.IO) {
                    if (share.contentUri != null) {
                        val uri = Uri.parse(share.contentUri)
                        if (uri.authority.equals("${packageName}.fileprovider", ignoreCase = true)) {
                            throw SecurityException("Internal file URIs cannot be shared into Vault.")
                        }
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
                selectedDestination = VaultDestination.SEND
                reviewDraftId = draft.id
                showStatus("Review your send before uploading.")
                refreshCounter++
            } catch (e: Exception) { showStatus(e.message ?: "Could not accept this share.", NoticeTone.ERROR) }
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

    private fun openComposer(kind: ComposeKind, draftId: String? = null) {
        val draft = draftId?.let(store::draft)
        editingDraftId = draft?.id
        composeKind = kind
        composeText = draft?.text.orEmpty()
        composeName = draft?.displayName.orEmpty()
        composeFileDraft = if (kind == ComposeKind.FILE) draft else null
        accountScreen = false
        reviewDraftId = null
        selectedDestination = VaultDestination.SEND
    }

    private fun leaveComposer() {
        composeKind = null
        composeText = ""
        composeName = ""
        composeFileDraft = null
        editingDraftId = null
        selectedDestination = VaultDestination.SEND
    }

    private fun reviewComposer() {
        val kind = composeKind ?: return
        val credentials = store.credentials() ?: run {
            showStatus("Pair this phone before sending.", NoticeTone.ERROR)
            return
        }
        val original = editingDraftId?.let(store::draft)
        val candidate = when (kind) {
            ComposeKind.FILE -> composeFileDraft ?: return
            ComposeKind.TEXT, ComposeKind.LINK -> {
                val value = if (kind == ComposeKind.LINK) composeText.trim() else composeText
                if (kind == ComposeKind.TEXT && (!isShareTextWithinLimit(value) || value.isBlank())) {
                    showStatus(if (value.isBlank()) "Enter some text to send." else "Text exceeds the 256 KB limit.", NoticeTone.ERROR)
                    return
                }
                if (kind == ComposeKind.LINK && (!isSafeExternalHttpUrl(value) || !isShareTextWithinLimit(value))) {
                    showStatus("Enter a complete HTTP or HTTPS link under 256 KB.", NoticeTone.ERROR)
                    return
                }
                val displayName = composeName.trim().ifBlank {
                    if (kind == ComposeKind.TEXT) "Text snippet" else runCatching { java.net.URI(value).host }.getOrNull() ?: "Link"
                }
                val contentKind = if (kind == ComposeKind.TEXT) DraftContentKind.TEXT else DraftContentKind.URL
                val contentType = if (kind == ComposeKind.TEXT) "text/plain" else "text/uri-list"
                val size = value.toByteArray(Charsets.UTF_8).size.toLong()
                if (original != null) original.copy(
                    text = value, spoolName = null, sha256 = null, uploadId = null,
                    displayName = displayName, contentType = contentType, sizeBytes = size,
                    bytesSent = 0, state = DraftState.PREPARED, safeError = null, contentKind = contentKind,
                ) else Draft(
                    id = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
                    serverOrigin = credentials.origin, ownerUserId = credentials.userId,
                    text = value, displayName = displayName, contentType = contentType,
                    sizeBytes = size, state = DraftState.PREPARED, contentKind = contentKind,
                )
            }
        }
        val prepared = draftQueue().prepare(candidate.copy(displayName = composeName.trim().ifBlank { candidate.displayName }))
        reviewDraftId = prepared.id
        composeKind = null
        accountScreen = false
        refreshCounter++
    }

    private fun preparePickedFile(uri: Uri) {
        val credentials = store.credentials() ?: run {
            showStatus("Pair this phone before sending a file.", NoticeTone.ERROR)
            return
        }
        if (uri.authority.equals("${packageName}.fileprovider", ignoreCase = true)) {
            showStatus("Internal file URIs cannot be selected.", NoticeTone.ERROR)
            return
        }
        val existing = editingDraftId?.let(store::draft)
        val oldSpool = existing?.spoolName
        preparingFile = true
        lifecycleScope.launch {
            var newSpoolName: String? = null
            try {
                val candidate = withContext(Dispatchers.IO) {
                    val metadata = contentMetadata(uri)
                    val spoolName = UUID.randomUUID().toString()
                    newSpoolName = spoolName
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("This file is no longer available. Choose it again.")
                    val result = input.use { store.createSpool(spoolName, it) }
                    val base = existing ?: Draft(
                        id = UUID.randomUUID().toString(), idempotencyKey = UUID.randomUUID().toString(),
                        serverOrigin = credentials.origin, ownerUserId = credentials.userId,
                        displayName = metadata.first, contentType = metadata.second, sizeBytes = result.sizeBytes,
                    )
                    base.copy(
                        text = null, spoolName = spoolName, sha256 = result.sha256,
                        displayName = composeName.trim().ifBlank { metadata.first }, contentType = metadata.second,
                        sizeBytes = result.sizeBytes, bytesSent = 0, uploadId = null,
                        state = DraftState.PREPARED, safeError = null, contentKind = DraftContentKind.FILE,
                    )
                }
                val prepared = draftQueue().prepare(candidate)
                if (oldSpool != null && oldSpool != newSpoolName) store.deleteSpool(oldSpool)
                composeFileDraft = prepared
                composeName = prepared.displayName
                refreshCounter++
            } catch (e: Exception) {
                store.deleteSpool(newSpoolName)
                showStatus(e.message ?: "Could not prepare that file.", NoticeTone.ERROR)
            } finally { preparingFile = false }
        }
    }

    private fun backFromFullScreen() {
        when {
            accountScreen -> accountScreen = false
            reviewDraftId != null -> {
                reviewDraftId = null
                selectedDestination = VaultDestination.SEND
            }
            composeKind != null -> {
                composeKind = null
                composeFileDraft = null
                editingDraftId = null
                selectedDestination = VaultDestination.SEND
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun VaultScreen() {
        val scope = rememberCoroutineScope()
        val authEpoch by EncryptedStore.authRevision.collectAsState()
        val draftsEpoch by EncryptedStore.draftsRevision.collectAsState()
        val liveProgress by UploadProgressTracker.progress.collectAsState()
        val credentials = remember(authEpoch, refreshCounter, foregroundToken) { store.credentials() }
        val drafts = remember(authEpoch, draftsEpoch, refreshCounter, foregroundToken, credentials?.origin, credentials?.userId) { store.draftsFor(credentials?.origin, credentials?.userId) }
        val snackbarHostState = remember { SnackbarHostState() }
        var loginErrorMessage by remember { mutableStateOf<String?>(null) }

        var hadCredentials by remember { mutableStateOf(credentials != null) }
        LaunchedEffect(credentials) {
            if (credentials == null && hadCredentials) {
                reviewDraftId = null
                accountScreen = false
                selectedDestination = VaultDestination.SEND
                composeKind = null
                composeFileDraft = null
                editingDraftId = null
                if (!userSignedOut) {
                    showStatus("Session expired or revoked. Please sign in or pair again.", NoticeTone.ERROR)
                }
                userSignedOut = false
            }
            hadCredentials = (credentials != null)
        }

        LaunchedEffect(foregroundToken, credentials?.deviceId) {
            if (credentials != null) {
                val valid = withContext(Dispatchers.IO) {
                    vaultApi(this@MainActivity).validateSession().getOrDefault(true)
                }
                if (!valid) {
                    store.clearCredentials()
                }
            }
        }
        LaunchedEffect(statusMessage, statusTone) {
            val message = statusMessage
            if (message.isNotBlank()) {
                snackbarHostState.showSnackbar(message)
                if (statusMessage == message) statusMessage = ""
            }
        }
        LaunchedEffect(credentials?.deviceId) {
            if (credentials != null) {
                androidx.work.WorkManager.getInstance(this@MainActivity)
                    .getWorkInfosByTagFlow(UploadWorker.TAG_UPLOAD)
                    .collect {
                        refreshCounter++
                    }
            }
        }
        LaunchedEffect(credentials?.deviceId) {
            if (credentials != null) {
                store.draftsFor(credentials.origin, credentials.userId).forEach { draft ->
                    when (draft.state) {
                        DraftState.QUEUED, DraftState.RETRYING -> UploadWorker.enqueue(this@MainActivity, draft.id)
                        DraftState.UPLOADING -> {
                            val recovered = draft.copy(state = DraftState.QUEUED, safeError = null)
                            store.saveDraft(recovered)
                            UploadWorker.enqueue(this@MainActivity, recovered.id, replace = true)
                        }
                        else -> {}
                    }
                }
            }
        }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(::parsePairing) }
        val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) preparePickedFile(uri)
        }
        LaunchedEffect(payload?.pairingSessionId, pairingDeadline) {
            val sessionId = payload?.pairingSessionId ?: return@LaunchedEffect
            val deadline = pairingDeadline ?: return@LaunchedEffect
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            if (payload?.pairingSessionId == sessionId && System.currentTimeMillis() >= deadline) {
                payload = null; pairingDeadline = null; secret = ""; pasteText = ""
                showStatus("Pairing code expired. Create a fresh QR code.", NoticeTone.ERROR)
            }
        }
        val snackbarHost: @Composable () -> Unit = {
            SnackbarHost(snackbarHostState) { data ->
                val error = statusTone == NoticeTone.ERROR
                val background = when {
                    error -> MaterialTheme.colorScheme.errorContainer
                    statusTone == NoticeTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.inverseSurface
                }
                val foreground = when {
                    error -> MaterialTheme.colorScheme.onErrorContainer
                    statusTone == NoticeTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.inverseOnSurface
                }
                Snackbar(snackbarData = data, containerColor = background, contentColor = foreground)
            }
        }

        VaultTheme {
            if (credentials == null) {
                Scaffold(
                    snackbarHost = snackbarHost,
                ) { innerPadding ->
                    Box(
                        Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                            .imePadding()
                    ) {
                        if (payload == null) {
                            WelcomeScreen(
                                onScanQr = { scanner.launch(ScanOptions().setPrompt("Scan Vault pairing QR").setBeepEnabled(false).setOrientationLocked(false)) },
                                onLogin = { origin, identifier, password, devName ->
                                    if (pairingInProgress) return@WelcomeScreen
                                    pairingInProgress = true
                                    loginErrorMessage = null
                                    scope.launch {
                                        showStatus("Signing in…")
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                vaultApi(this@MainActivity).deviceLogin(origin, identifier, password, devName)
                                            }
                                            result.onSuccess {
                                                loginErrorMessage = null
                                                deviceName = devName
                                                selectedDestination = VaultDestination.SEND
                                                showStatus("Signed in to Vault.", NoticeTone.SUCCESS)
                                                refreshCounter++
                                            }.onFailure { e ->
                                                val err = e.message ?: "Sign-in failed."
                                                loginErrorMessage = err
                                                showStatus(err, NoticeTone.ERROR)
                                            }
                                        } catch (e: Exception) {
                                            val err = e.message ?: "Sign-in failed."
                                            loginErrorMessage = err
                                            showStatus(err, NoticeTone.ERROR)
                                        } finally {
                                            pairingInProgress = false
                                        }
                                    }
                                },
                                defaultOrigin = BuildConfig.PAIRING_SERVER_ORIGIN,
                                defaultDeviceName = deviceName,
                                busy = pairingInProgress,
                                errorMessage = loginErrorMessage ?: if (statusTone == NoticeTone.ERROR) statusMessage else null,
                                onClearError = {
                                    loginErrorMessage = null
                                    if (statusTone == NoticeTone.ERROR) statusMessage = ""
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            payload?.let { selected ->
                                Column(
                                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text("Pair with ${selected.serverOrigin}", style = MaterialTheme.typography.titleMedium)
                                            if (selected.pairingSecret == null) OutlinedTextField(
                                                secret, { secret = it }, label = { Text("10-minute pairing secret") },
                                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true,
                                            )
                                            OutlinedTextField(deviceName, { deviceName = it.take(100) }, label = { Text("Phone name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = { payload = null; secret = ""; pairingDeadline = null },
                                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                                ) { Text("Cancel") }
                                                Button(
                                                    onClick = {
                                                        if (pairingInProgress) return@Button
                                                        pairingInProgress = true
                                                        scope.launch {
                                                            try {
                                                                if ((pairingDeadline ?: 0L) <= System.currentTimeMillis()) {
                                                                    payload = null; secret = ""
                                                                    showStatus("Pairing code expired. Create a fresh QR code.", NoticeTone.ERROR)
                                                                    return@launch
                                                                }
                                                                showStatus("Pairing…")
                                                                try {
                                                                    val paired = withContext(Dispatchers.IO) { vaultApi(this@MainActivity).pair(selected, secret.trim(), deviceName.trim()) }
                                                                    store.saveCredentials(paired); payload = null; pairingDeadline = null; secret = ""
                                                                    selectedDestination = VaultDestination.SEND
                                                                    showStatus("This phone is paired.", NoticeTone.SUCCESS)
                                                                    refreshCounter++
                                                                } catch (e: java.io.IOException) {
                                                                    showStatus("Pairing response was lost. Create a fresh pairing QR and pair again.", NoticeTone.ERROR)
                                                                } catch (e: Exception) {
                                                                    showStatus(e.message ?: "Pairing failed. Create a fresh pairing QR if this code was already used.", NoticeTone.ERROR)
                                                                }
                                                            } finally { pairingInProgress = false }
                                                        }
                                                    },
                                                    enabled = secret.isNotBlank() && deviceName.isNotBlank() && !pairingInProgress,
                                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                                ) { Text(if (pairingInProgress) "Pairing…" else "Pair phone") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val isSubpage = accountScreen || composeKind != null || reviewDraftId != null
                val title = when {
                    accountScreen -> "Account"
                    reviewDraftId != null -> "Review send"
                    composeKind != null -> composeKind?.let { kind -> when (kind) { ComposeKind.TEXT -> "Send text"; ComposeKind.LINK -> "Send link"; ComposeKind.FILE -> "Send file" } } ?: "Send"
                    selectedDestination == VaultDestination.SEND -> "Send"
                    selectedDestination == VaultDestination.INBOX -> "Inbox"
                    else -> "Vault Hub"
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (!isSubpage) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_vault_logo),
                                            contentDescription = "Vault",
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                            },
                            navigationIcon = {
                                if (isSubpage) IconButton(onClick = ::backFromFullScreen) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                if (!accountScreen && selectedDestination != VaultDestination.HUB) IconButton(onClick = { selectedDestination = VaultDestination.HUB }) {
                                    Icon(Icons.Filled.Hub, contentDescription = "Vault Hub")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        if (!isSubpage) NavigationBar {
                            NavigationBarItem(
                                selected = selectedDestination == VaultDestination.SEND,
                                onClick = { selectedDestination = VaultDestination.SEND },
                                icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) }, label = { Text("Send") },
                            )
                            NavigationBarItem(
                                selected = selectedDestination == VaultDestination.INBOX,
                                onClick = { selectedDestination = VaultDestination.INBOX },
                                icon = { Icon(Icons.Filled.Inbox, contentDescription = null) }, label = { Text("Inbox") },
                            )
                            NavigationBarItem(
                                selected = selectedDestination == VaultDestination.HUB,
                                onClick = { selectedDestination = VaultDestination.HUB },
                                icon = { Icon(Icons.Filled.Hub, contentDescription = null) }, label = { Text("Hub") },
                            )
                        }
                    },
                    snackbarHost = snackbarHost,
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                        when {
                            accountScreen -> AccountScreen(credentials, deviceName, onSignOut = { signOutConfirmation = true }, modifier = Modifier.fillMaxSize())
                            reviewDraftId != null -> {
                                val draft = remember(refreshCounter, reviewDraftId) { reviewDraftId?.let(store::draft) }
                                if (draft == null) MissingDraftScreen(Modifier.fillMaxSize())
                                else ReviewSendScreen(
                                    draft = draft,
                                    onSend = { days, hours ->
                                        runCatching { draftQueue().send(draft.id, days, hours) }
                                            .onSuccess {
                                                reviewDraftId = null
                                                selectedDestination = VaultDestination.SEND
                                                val message = if (isNetworkConnected(this@MainActivity)) {
                                                    "Added to Vault. Upload will retry if the network drops."
                                                } else {
                                                    "Queued for upload. Will send when connected."
                                                }
                                                showStatus(message, NoticeTone.SUCCESS)
                                                refreshCounter++
                                            }
                                            .onFailure { showStatus(it.message ?: "Could not queue this send.", NoticeTone.ERROR) }
                                    },
                                    onEdit = { days, hours ->
                                        store.saveDraft(draft.copy(retentionDays = days, retentionHours = hours))
                                        openComposer(draft.contentKind.toComposeKind(draft.text), draft.id)
                                        refreshCounter++
                                    },
                                    onDiscard = {
                                        draftQueue().cancel(draft.id)
                                        reviewDraftId = null; selectedDestination = VaultDestination.SEND
                                        showStatus("Prepared send discarded.")
                                        refreshCounter++
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            composeKind != null -> SendComposerScreen(
                                kind = composeKind!!,
                                text = composeText,
                                displayName = composeName,
                                selectedFile = composeFileDraft,
                                preparingFile = preparingFile,
                                notice = null,
                                onTextChange = { composeText = it },
                                onNameChange = { composeName = it },
                                onChooseFile = { openDocument.launch(arrayOf("*/*")) },
                                onReview = ::reviewComposer,
                                modifier = Modifier.fillMaxSize(),
                            )
                            selectedDestination == VaultDestination.SEND -> SendHomeScreen(
                                onText = { openComposer(ComposeKind.TEXT) },
                                onLink = { openComposer(ComposeKind.LINK) },
                                onFile = { openComposer(ComposeKind.FILE) },
                                serverOrigin = credentials.origin,
                                drafts = drafts,
                                liveProgress = liveProgress,
                                onReview = { reviewDraftId = it },
                                onRetry = { draft ->
                                    runCatching { draftQueue().send(draft.id, draft.retentionDays, draft.retentionHours) }
                                        .onSuccess {
                                            val message = if (isNetworkConnected(this@MainActivity)) {
                                                "Added to Vault. Upload will retry if the network drops."
                                            } else {
                                                "Queued for upload. Will send when connected."
                                            }
                                            showStatus(message, NoticeTone.SUCCESS)
                                            refreshCounter++
                                        }
                                        .onFailure { showStatus(it.message ?: "Could not queue this send.", NoticeTone.ERROR) }
                                },
                                onRemove = { draft ->
                                    draftQueue().cancel(draft.id)
                                    showStatus("Send removed.")
                                    refreshCounter++
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            selectedDestination == VaultDestination.INBOX -> InboxTab(
                                vaultApi(this@MainActivity), store,
                                "${credentials.origin}|${credentials.userId}|${credentials.deviceId}",
                                foregroundToken, Modifier.fillMaxSize(),
                                onCacheRefreshed = {
                                    scope.launch {
                                        val latency = withContext(Dispatchers.IO) { vaultApi(this@MainActivity).ping() }
                                        hubPingLatencyMs = latency
                                    }
                                },
                            )
                            else -> HubScreen(
                                api = vaultApi(this@MainActivity),
                                store = store,
                                credentials = credentials,
                                deviceName = deviceName,
                                pingLatencyMs = hubPingLatencyMs,
                                hasPinged = hubHasPinged,
                                onPingUpdate = { latency ->
                                    hubPingLatencyMs = latency
                                    hubHasPinged = true
                                },
                                onSignOutCurrentDevice = {
                                    userSignedOut = true
                                    scope.launch {
                                        showStatus("Signing out of this phone…")
                                        try {
                                            withContext(Dispatchers.IO) { vaultApi(this@MainActivity).revokeCurrentDevice() }
                                            reviewDraftId = null
                                            selectedDestination = VaultDestination.SEND
                                            hubPingLatencyMs = null
                                            hubHasPinged = false
                                            showStatus("Signed out of this phone.", NoticeTone.INFO)
                                        } catch (e: Exception) {
                                            showStatus(e.message ?: "Sign out failed.", NoticeTone.ERROR)
                                        } finally {
                                            refreshCounter++
                                        }
                                    }
                                },
                                onSignOutEverywhere = { signOutConfirmation = true },
                                onShowMessage = { showStatus(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            if (signOutConfirmation) {
                SignOutConfirmationDialog(
                    busy = signOutInProgress,
                    onDismiss = { signOutConfirmation = false },
                    onConfirm = {
                        signOutConfirmation = false
                        signOutInProgress = true
                        userSignedOut = true
                        scope.launch {
                            showStatus("Signing out everywhere…")
                            try {
                                withContext(Dispatchers.IO) { vaultApi(this@MainActivity).revokeAll() }
                                reviewDraftId = null; accountScreen = false; selectedDestination = VaultDestination.SEND
                                showStatus("All sessions were signed out.", NoticeTone.SUCCESS)
                            } catch (e: Exception) { showStatus(e.message ?: "Could not revoke sessions.", NoticeTone.ERROR) }
                            finally { signOutInProgress = false; refreshCounter++ }
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun AccountScreen(credentials: Credentials, deviceName: String, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Account and connection", style = MaterialTheme.typography.bodyLarge)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(credentials.origin, style = MaterialTheme.typography.bodyLarge)
                    Text("Phone: $deviceName", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Your phone is paired, but it does not keep a live connection while the app is closed. The Inbox refreshes when you open Vault or return to it. Uploads retry when Android allows background work.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign out everywhere") }
        }
    }

    @Composable
    private fun MissingDraftScreen(modifier: Modifier = Modifier) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This draft is no longer available.", style = MaterialTheme.typography.titleLarge)
            Text("Return to Activity and choose another send.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { reviewDraftId = null; selectedDestination = VaultDestination.SEND }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to Send") }
        }
    }

    private fun draftQueue() = DraftQueue(store,
        enqueue = { UploadWorker.enqueue(this, it, replace = true) },
        cancelWork = { UploadWorker.cancel(this, it) })

    companion object {
        private const val STATE_SELECTED_TAB = "vault.selectedTab"
        private const val STATE_REVIEW_DRAFT_ID = "vault.reviewDraftId"
        private const val STATE_ACCOUNT_SCREEN = "vault.accountScreen"
        private const val STATE_DEVICE_NAME = "vault.deviceName"
        private const val STATE_STATUS = "vault.status"
        private const val STATE_PAIRING_DEADLINE = "vault.pairingDeadline"
        private const val STATE_PAIRING_ORIGIN = "vault.pairingOrigin"
        private const val STATE_PAIRING_SESSION = "vault.pairingSession"
        private const val STATE_PAIRING_EXPIRY = "vault.pairingExpiry"
    }

}
