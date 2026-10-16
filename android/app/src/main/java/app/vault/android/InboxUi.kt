package app.vault.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun isSafeExternalHttpUrl(value: String): Boolean {
    if (value.isBlank() || value.any { it.isWhitespace() || Character.isISOControl(it) }) return false
    return runCatching {
        val uri = URI(value)
        uri.isAbsolute && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}

internal fun isInboxItemExpired(expiresAt: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
    runCatching { !Instant.parse(expiresAt).isAfter(Instant.ofEpochMilli(nowMillis)) }.getOrDefault(true)

internal fun safeDocumentSuggestion(name: String, fallback: String = "vault-attachment"): String {
    val sanitized = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { Character.isISOControl(it) || it == '/' || it == '\\' }
        .trim().take(120)
    return sanitized.ifBlank { fallback }
}

@Composable
internal fun InboxTab(
    api: VaultApi,
    store: EncryptedStore,
    ownerKey: String,
    foregroundToken: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember(ownerKey) { mutableStateOf<List<InboxSummary>>(emptyList()) }
    var nextCursor by remember(ownerKey) { mutableStateOf<String?>(null) }
    var hasLoaded by remember(ownerKey) { mutableStateOf(false) }
    var pageLoading by remember(ownerKey) { mutableStateOf(false) }
    var pageError by remember(ownerKey) { mutableStateOf<String?>(null) }
    var pageRetryReset by remember(ownerKey) { mutableStateOf(true) }
    var selected by remember(ownerKey) { mutableStateOf<InboxDetail?>(null) }
    var detailLoading by remember(ownerKey) { mutableStateOf(false) }
    var detailError by remember(ownerKey) { mutableStateOf<String?>(null) }
    var detailId by remember(ownerKey) { mutableStateOf<String?>(null) }
    var operationMessage by remember(ownerKey) { mutableStateOf<String?>(null) }
    var operationBusy by remember(ownerKey) { mutableStateOf(false) }
    var downloadFile by remember(ownerKey) { mutableStateOf<File?>(null) }
    var downloadName by remember(ownerKey) { mutableStateOf("vault-attachment") }
    var downloadProgress by remember(ownerKey) { mutableLongStateOf(0L) }
    var requestEpoch by remember(ownerKey) { mutableIntStateOf(0) }
    var detailEpoch by remember(ownerKey) { mutableIntStateOf(0) }
    var lastForeground by remember(ownerKey) { mutableIntStateOf(Int.MIN_VALUE) }
    var refreshQueued by remember(ownerKey) { mutableStateOf(false) }

    fun ownerStillCurrent(): Boolean {
        val current = store.credentials() ?: return false
        return "${current.origin}|${current.userId}|${current.deviceId}" == ownerKey
    }

    suspend fun fetchPage(reset: Boolean) {
        if (pageLoading) {
            if (reset) refreshQueued = true
            return
        }
        val cursor = if (reset) null else nextCursor ?: return
        pageLoading = true
        pageError = null
        val epoch = requestEpoch
        try {
            val page = withContext(Dispatchers.IO) { api.listItems(cursor = cursor, limit = 30) }
            if (epoch == requestEpoch && ownerStillCurrent()) {
                rows = if (reset) page.items else (rows + page.items).distinctBy { it.id }
                nextCursor = page.nextCursor
                hasLoaded = true
            }
        } catch (e: Exception) {
            if (epoch == requestEpoch && ownerStillCurrent()) {
                pageError = e.safeInboxMessage()
                pageRetryReset = reset
            }
        } finally {
            if (epoch == requestEpoch) {
                pageLoading = false
                if (refreshQueued) {
                    refreshQueued = false
                    scope.launch { fetchPage(reset = true) }
                }
            }
        }
    }

    suspend fun openDetail(id: String) {
        if (detailLoading) return
        detailId = id
        detailLoading = true
        detailError = null
        selected = null
        operationMessage = null
        val epoch = ++detailEpoch
        try {
            val value = withContext(Dispatchers.IO) { api.getItem(id) }
            if (epoch == detailEpoch && ownerStillCurrent()) selected = value
        } catch (e: Exception) {
            if (epoch == detailEpoch && ownerStillCurrent()) detailError = e.safeInboxMessage()
        } finally {
            if (epoch == detailEpoch) detailLoading = false
        }
    }

    LaunchedEffect(ownerKey) {
        requestEpoch++
        rows = emptyList(); nextCursor = null; hasLoaded = false; selected = null
        detailId = null; pageError = null; detailError = null; operationMessage = null
        fetchPage(reset = true)
    }
    LaunchedEffect(ownerKey, foregroundToken) {
        if (lastForeground != foregroundToken) {
            lastForeground = foregroundToken
            if (hasLoaded) fetchPage(reset = true)
        }
    }

    BackHandler(selected != null || detailLoading || detailId != null) {
        detailEpoch++; detailLoading = false
        downloadFile?.delete(); downloadFile = null
        selected = null; detailId = null; detailError = null; operationMessage = null
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = downloadFile
        downloadFile = null
        if (uri == null || file == null) {
            file?.delete()
            operationMessage = if (uri == null) "Download cancelled." else "Downloaded file is unavailable."
        } else {
            operationBusy = true
            scope.launch {
                try {
                    if (!ownerStillCurrent()) throw IllegalStateException("Session changed. Pair this device again.")
                    if (selected?.let { isInboxItemExpired(it.expiresAt) } == true) throw IllegalStateException("This inbox item has expired.")
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
                            ?: throw IllegalStateException("Could not write to the selected location.")
                    }
                    operationMessage = "Attachment saved."
                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    operationMessage = e.safeInboxMessage()
                }
                finally { file.delete(); operationBusy = false }
            }
        }
    }

    val detail = selected
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (detail != null || detailLoading || detailId != null) {
            TextButton(onClick = { detailEpoch++; detailLoading = false; downloadFile?.delete(); downloadFile = null; selected = null; detailId = null; detailError = null; operationMessage = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹  Inbox") }
            if (detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (detail == null && !detailLoading) detailError?.let { TextButton(onClick = { detailId?.let { id -> scope.launch { openDetail(id) } } }) { Text("Retry") } }
            detail?.let { item ->
                DetailContent(item, operationBusy, downloadProgress, operationMessage,
                    onCopy = { value ->
                        if (isInboxItemExpired(item.expiresAt)) operationMessage = "This item has expired."
                        else {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Vault item", value))
                            operationMessage = "Copied to clipboard."
                        }
                    },
                    onOpen = {
                        val url = item.text.orEmpty()
                        if (isInboxItemExpired(item.expiresAt)) operationMessage = "This item has expired."
                        else if (!isSafeExternalHttpUrl(url)) operationMessage = "This link cannot be opened safely."
                        else try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            operationMessage = "Opened link in your browser."
                        } catch (_: Exception) { operationMessage = "No browser is available to open this link." }
                    },
                    onDownload = {
                        if (isInboxItemExpired(item.expiresAt)) operationMessage = "This item has expired."
                        else {
                            operationBusy = true; operationMessage = "Preparing verified download…"; downloadProgress = 0
                            val expectedDetailEpoch = detailEpoch
                            scope.launch {
                                var temp: File? = null
                                try {
                                    temp = withContext(Dispatchers.IO) {
                                        api.downloadToTemp(item) { bytes -> scope.launch(Dispatchers.Main.immediate) { downloadProgress = bytes } }
                                    }
                                    if (!ownerStillCurrent() || isInboxItemExpired(item.expiresAt) || selected?.id != item.id || expectedDetailEpoch != detailEpoch) throw IllegalStateException("This item expired or the session changed before download.")
                                    downloadFile = temp; temp = null
                                    downloadName = safeDocumentSuggestion(item.displayName)
                                    operationMessage = "Choose where to save the attachment."
                                    createDocument.launch(downloadName)
                                } catch (e: Exception) { temp?.delete(); operationMessage = e.safeInboxMessage() }
                                finally { operationBusy = false }
                            }
                        }
                    })
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Inbox", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { scope.launch { fetchPage(reset = true) } }, enabled = !pageLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
            }
            if (pageLoading && !hasLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            pageError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { fetchPage(reset = pageRetryReset) } }, enabled = !pageLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            }
            if (hasLoaded && rows.isEmpty() && pageError == null) Text("Your inbox is empty.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.id }) { item -> InboxRow(item) { scope.launch { openDetail(item.id) } } }
                if (rows.isNotEmpty() && nextCursor != null) item(key = "inbox-load-more") {
                    OutlinedButton(onClick = { scope.launch { fetchPage(reset = false) } }, enabled = !pageLoading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (pageLoading) "Loading more…" else "Load more")
                    }
                }
                if (pageLoading && hasLoaded) item(key = "inbox-more-progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }

}

@Composable
private fun InboxRow(item: InboxSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "${item.displayName}, ${item.type}, ${item.sizeBytes} bytes, from ${item.sourceDevice.name}, expires ${item.expiresAt}. Open details." }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${item.type} · ${formatBytes(item.sizeBytes)} · ${item.state.lowercase()}")
            Text("From ${item.sourceDevice.name} · expires ${formatInstant(item.expiresAt)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DetailContent(item: InboxDetail, busy: Boolean, progressBytes: Long, message: String?, onCopy: (String) -> Unit, onOpen: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("${item.type} · ${item.contentType ?: "text/plain"} · ${formatBytes(item.sizeBytes)}")
        Text("From ${item.sourceDevice.name} · expires ${formatInstant(item.expiresAt)}")
        when (item.type.lowercase()) {
            "text", "url" -> {
                val exactValue = item.text.orEmpty()
                Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer { Text(AnnotatedString(exactValue), Modifier.padding(12.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCopy(exactValue) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
                    if (item.type.equals("url", true)) OutlinedButton(onClick = onOpen, enabled = !busy && isSafeExternalHttpUrl(exactValue), modifier = Modifier.heightIn(min = 48.dp)) { Text("Open link") }
                }
            }
            else -> {
                Text("SHA-256: ${item.sha256 ?: "Unavailable"}")
                Button(onClick = onDownload, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download attachment") }
            }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (progressBytes > 0) Text("Verified download: ${formatBytes(progressBytes)} of ${formatBytes(item.sizeBytes)} received.")
        }
        message?.let { Text(it, color = if (it.contains("could not", true) || it.contains("expired", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun formatInstant(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

private fun Exception.safeInboxMessage(): String = when (this) {
    is ApiFailure -> when (status) {
        401, 403 -> "Session expired or revoked. Pair this device again."
        404 -> "This inbox item could not be found. Refresh the inbox."
        410 -> "This inbox item has expired."
        503 -> "Vault is temporarily unavailable. Try again shortly."
        else -> message ?: "Could not reach the Vault inbox. Check your connection and retry."
    }
    else -> message ?: "Could not reach the Vault inbox. Check your connection and retry."
}
