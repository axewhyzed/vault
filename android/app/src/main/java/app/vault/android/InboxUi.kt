package app.vault.android

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

internal fun saveToTreeUri(context: android.content.Context, treeUri: Uri, fileName: String, mimeType: String, sourceFile: File): Boolean {
    return try {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        if (!root.canWrite()) return false
        val doc = root.createFile(mimeType, fileName) ?: return false
        context.contentResolver.openOutputStream(doc.uri, "w")?.use { out ->
            sourceFile.inputStream().use { input -> input.copyTo(out) }
        } ?: return false
        true
    } catch (_: Exception) {
        false
    }
}


@Composable
internal fun InboxSkeletonCard(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeletonTransition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    val shimmerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(shimmerColor)
                )
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(shimmerColor)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.35f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerColor)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerColor)
            )
        }
    }
}

@Composable
internal fun QuotaCard(quota: InboxQuota, modifier: Modifier = Modifier) {
    val progress = if (quota.maxActiveBytes > 0) {
        (quota.activeBytes.toFloat() / quota.maxActiveBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 500),
        label = "quotaProgress"
    )
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Vault storage",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${formatBytes(quota.activeBytes)} of ${formatBytes(quota.maxActiveBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Text(
                "${quota.activeItems} of ${quota.maxActiveItems} items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun TypeBadge(type: String, sizeBytes: Long = 0L) {
    val isDark = isSystemInDarkTheme()
    val (icon, label, tintColor) = when (type.lowercase()) {
        "file" -> Triple(
            Icons.Filled.Description,
            formatBytes(sizeBytes),
            if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2)
        )
        "url", "link" -> Triple(
            Icons.Filled.Link,
            "Link",
            if (isDark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)
        )
        else -> Triple(
            Icons.Filled.FormatQuote,
            "Text",
            if (isDark) Color(0xFFA5D6A7) else Color(0xFF2E7D32)
        )
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tintColor.copy(alpha = if (isDark) 0.22f else 0.12f),
        contentColor = tintColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxTab(
    api: VaultApi,
    store: EncryptedStore,
    ownerKey: String,
    foregroundToken: Int,
    modifier: Modifier = Modifier,
    onCacheRefreshed: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember(ownerKey) { mutableStateOf<List<InboxSummary>>(emptyList()) }
    var nextCursor by remember(ownerKey) { mutableStateOf<String?>(null) }
    var quota by remember(ownerKey) { mutableStateOf<InboxQuota?>(null) }
    var hasLoaded by remember(ownerKey) { mutableStateOf(false) }
    var pageLoading by remember(ownerKey) { mutableStateOf(false) }
    var pageError by remember(ownerKey) { mutableStateOf<String?>(null) }
    var pageRetryReset by remember(ownerKey) { mutableStateOf(true) }
    var selected by remember(ownerKey) { mutableStateOf<InboxDetail?>(null) }
    var detailLoading by remember(ownerKey) { mutableStateOf(false) }
    var detailError by remember(ownerKey) { mutableStateOf<String?>(null) }
    var detailId by remember(ownerKey) { mutableStateOf<String?>(null) }
    var operationMessage by remember(ownerKey) { mutableStateOf<String?>(null) }
    var operationMessageIsError by remember(ownerKey) { mutableStateOf(false) }
    var operationBusy by remember(ownerKey) { mutableStateOf(false) }
    var downloadFile by remember(ownerKey) { mutableStateOf<File?>(null) }
    var downloadName by remember(ownerKey) { mutableStateOf("vault-attachment") }
    var downloadProgress by remember(ownerKey) { mutableLongStateOf(0L) }
    var requestEpoch by remember(ownerKey) { mutableIntStateOf(0) }
    var detailEpoch by remember(ownerKey) { mutableIntStateOf(0) }
    var lastForeground by remember(ownerKey) { mutableIntStateOf(Int.MIN_VALUE) }
    var refreshQueued by remember(ownerKey) { mutableStateOf(false) }
    var isBackgroundSyncing by remember(ownerKey) { mutableStateOf(false) }
    var searchQuery by remember(ownerKey) { mutableStateOf("") }
    var filterType by remember(ownerKey) { mutableStateOf("all") }

    val filteredRows = remember(rows, searchQuery, filterType) {
        val q = searchQuery.trim()
        rows.filter { item ->
            val typeMatches = when (filterType) {
                "file" -> item.type.equals("file", ignoreCase = true)
                "url" -> item.type.equals("url", ignoreCase = true) || item.type.equals("link", ignoreCase = true)
                "text" -> item.type.equals("text", ignoreCase = true)
                else -> true
            }
            if (!typeMatches) false
            else if (q.isBlank()) true
            else {
                val matchesContent = when (item.type) {
                    "text", "url" -> item.textContent?.contains(q, ignoreCase = true) == true || item.displayName.contains(q, ignoreCase = true)
                    "file" -> item.displayName.contains(q, ignoreCase = true)
                    else -> item.displayName.contains(q, ignoreCase = true) || item.textContent?.contains(q, ignoreCase = true) == true
                }
                matchesContent ||
                item.type.contains(q, ignoreCase = true) ||
                item.sourceDevice.name.contains(q, ignoreCase = true)
            }
        }
    }

    fun ownerStillCurrent(): Boolean {
        val current = store.credentials() ?: return false
        return "${current.origin}|${current.userId}|${current.deviceId}" == ownerKey
    }

    fun showOperationMessage(message: String?, isError: Boolean = false) {
        operationMessage = message
        operationMessageIsError = isError
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
                quota = page.quota
                hasLoaded = true
                if (reset) {
                    store.saveInboxCache(CachedInbox(ownerKey, System.currentTimeMillis(), page.items, page.nextCursor, page.quota))
                    onCacheRefreshed?.invoke()
                }
            }
        } catch (e: Exception) {
            if (e is ApiFailure && (e.status == 401 || e.status == 403)) {
                store.clearCredentials()
            }
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
        showOperationMessage(null)
        val epoch = ++detailEpoch
        try {
            val value = withContext(Dispatchers.IO) { api.getItem(id) }
            if (epoch == detailEpoch && ownerStillCurrent()) selected = value
        } catch (e: Exception) {
            if (e is ApiFailure && (e.status == 401 || e.status == 403)) {
                store.clearCredentials()
            }
            if (epoch == detailEpoch && ownerStillCurrent()) detailError = e.safeInboxMessage()
        } finally {
            if (epoch == detailEpoch) detailLoading = false
        }
    }

    LaunchedEffect(ownerKey) {
        requestEpoch++
        selected = null; detailId = null; pageError = null; detailError = null; showOperationMessage(null)
        val cached = store.cachedInbox()
        if (cached != null && cached.ownerKey == ownerKey) {
            rows = cached.items
            nextCursor = cached.nextCursor
            quota = cached.quota
            hasLoaded = true
            val isFresh = (System.currentTimeMillis() - cached.cachedAtEpochMillis) < 10 * 60 * 1000L
            if (!isFresh) {
                isBackgroundSyncing = true
                try {
                    fetchPage(reset = true)
                } finally {
                    isBackgroundSyncing = false
                }
            }
        } else {
            rows = emptyList(); nextCursor = null; quota = null; hasLoaded = false
            fetchPage(reset = true)
        }
    }
    LaunchedEffect(ownerKey, foregroundToken) {
        if (lastForeground != foregroundToken) {
            lastForeground = foregroundToken
            if (hasLoaded) {
                val cached = store.cachedInbox()
                val isFresh = cached != null && cached.ownerKey == ownerKey && (System.currentTimeMillis() - cached.cachedAtEpochMillis) < 10 * 60 * 1000L
                if (!isFresh) {
                    isBackgroundSyncing = true
                    try {
                        fetchPage(reset = true)
                    } finally {
                        isBackgroundSyncing = false
                    }
                }
            }
        }
    }

    BackHandler(selected != null || detailLoading || detailId != null) {
        detailEpoch++; detailLoading = false
        downloadFile?.delete(); downloadFile = null
        selected = null; detailId = null; detailError = null; showOperationMessage(null)
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = downloadFile
        val filename = downloadName
        downloadFile = null
        if (uri == null || file == null) {
            file?.delete()
            showOperationMessage(if (uri == null) "Download cancelled." else "Downloaded file is unavailable.", isError = file == null && uri != null)
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
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Downloaded $filename", Toast.LENGTH_SHORT).show()
                    }
                    showOperationMessage("Attachment saved.")
                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    showOperationMessage(e.safeInboxMessage(), isError = true)
                }
                finally { file.delete(); operationBusy = false }
            }
        }
    }

    val detail = selected
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (detail != null || detailLoading || detailId != null) {
            TextButton(onClick = { detailEpoch++; detailLoading = false; downloadFile?.delete(); downloadFile = null; selected = null; detailId = null; detailError = null; showOperationMessage(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹  Inbox") }
            if (detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            detailError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (detail == null && !detailLoading) detailError?.let { TextButton(onClick = { detailId?.let { id -> scope.launch { openDetail(id) } } }) { Text("Retry") } }
            detail?.let { item ->
                DetailContent(
                    item = item,
                    busy = operationBusy,
                    progressBytes = downloadProgress,
                    message = operationMessage,
                    messageIsError = operationMessageIsError,
                    onCopy = { value ->
                        if (isInboxItemExpired(item.expiresAt)) showOperationMessage("This item has expired.", isError = true)
                        else {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Vault item", value))
                            showOperationMessage("Copied to clipboard.")
                        }
                    },
                    onOpenUrl = {
                        val url = item.text.orEmpty()
                        if (isInboxItemExpired(item.expiresAt)) showOperationMessage("This item has expired.", isError = true)
                        else if (!isSafeExternalHttpUrl(url)) showOperationMessage("This link cannot be opened safely.", isError = true)
                        else try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            showOperationMessage("Opened link in your browser.")
                        } catch (_: Exception) { showOperationMessage("No browser is available to open this link.", isError = true) }
                    },
                    onDownload = {
                        if (isInboxItemExpired(item.expiresAt)) showOperationMessage("This item has expired.", isError = true)
                        else {
                            operationBusy = true; showOperationMessage("Downloading and verifying…"); downloadProgress = 0
                            val expectedDetailEpoch = detailEpoch
                            scope.launch {
                                var temp: File? = null
                                try {
                                    temp = withContext(Dispatchers.IO) {
                                        api.downloadToTemp(item) { bytes -> scope.launch(Dispatchers.Main.immediate) { downloadProgress = bytes } }
                                    }
                                    if (!ownerStillCurrent() || isInboxItemExpired(item.expiresAt) || selected?.id != item.id || expectedDetailEpoch != detailEpoch) {
                                        throw IllegalStateException("This item expired or the session changed before download.")
                                    }
                                    val folderUriStr = store.defaultDownloadUri()
                                    val folderDoc = folderUriStr?.let { Uri.parse(it) }?.let { DocumentFile.fromTreeUri(context, it) }
                                    if (folderDoc != null && folderDoc.canWrite()) {
                                        val filename = safeDocumentSuggestion(item.displayName)
                                        val mime = item.contentType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                                        val folderName = folderDoc.name ?: "downloads folder"
                                        withContext(Dispatchers.IO) {
                                            val newDoc = folderDoc.createFile(mime, filename)
                                                ?: throw IllegalStateException("Could not create file in default download folder.")
                                            context.contentResolver.openOutputStream(newDoc.uri, "w")?.use { out ->
                                                temp.inputStream().use { input -> input.copyTo(out) }
                                            } ?: throw IllegalStateException("Could not write to default download folder.")
                                        }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "Downloaded $filename to $folderName", Toast.LENGTH_SHORT).show()
                                        }
                                        showOperationMessage("Downloaded $filename to $folderName.")
                                        temp.delete()
                                    } else {
                                        downloadFile = temp; temp = null
                                        downloadName = safeDocumentSuggestion(item.displayName)
                                        showOperationMessage("Choose where to save the attachment.")
                                        createDocument.launch(downloadName)
                                    }
                                } catch (e: Exception) {
                                    temp?.delete()
                                    showOperationMessage(e.safeInboxMessage(), isError = true)
                                } finally {
                                    operationBusy = false
                                }
                            }
                        }
                    },
                    onOpenFile = {
                        if (isInboxItemExpired(item.expiresAt)) showOperationMessage("This item has expired.", isError = true)
                        else {
                            operationBusy = true; showOperationMessage("Opening file…"); downloadProgress = 0
                            val expectedDetailEpoch = detailEpoch
                            scope.launch {
                                try {
                                    val openedDir = File(context.cacheDir, "opened_files").apply { mkdirs() }
                                    val targetFile = File(openedDir, "${item.id}_${safeDocumentSuggestion(item.displayName)}")
                                    if (!targetFile.exists() || targetFile.length() != item.sizeBytes) {
                                        showOperationMessage("Downloading and verifying…")
                                        val temp = withContext(Dispatchers.IO) {
                                            api.downloadToTemp(item) { bytes -> scope.launch(Dispatchers.Main.immediate) { downloadProgress = bytes } }
                                        }
                                        if (!ownerStillCurrent() || isInboxItemExpired(item.expiresAt) || selected?.id != item.id || expectedDetailEpoch != detailEpoch) {
                                            temp.delete()
                                            throw IllegalStateException("This item expired or the session changed.")
                                        }
                                        withContext(Dispatchers.IO) {
                                            temp.copyTo(targetFile, overwrite = true)
                                            temp.delete()
                                        }
                                    }
                                    val contentUri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        targetFile
                                    )
                                    val mimeType = item.contentType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                                        ?: context.contentResolver.getType(contentUri)
                                        ?: "application/octet-stream"
                                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(contentUri, mimeType)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    try {
                                        context.startActivity(Intent.createChooser(viewIntent, "Open with"))
                                        showOperationMessage("Opening ${item.displayName}…")
                                    } catch (_: Exception) {
                                        showOperationMessage("No app found to open this file.", isError = true)
                                    }
                                } catch (e: Exception) {
                                    showOperationMessage(e.safeInboxMessage(), isError = true)
                                } finally {
                                    operationBusy = false
                                }
                            }
                        }
                    },
                    onDelete = {
                        operationBusy = true
                        showOperationMessage("Deleting item…")
                        scope.launch {
                            val res = api.deleteItem(item.id)
                            operationBusy = false
                            res.onSuccess {
                                rows = rows.filter { it.id != item.id }
                                showOperationMessage("Item deleted.")
                                selected = null
                                detailId = null
                                detailError = null
                                downloadFile?.delete()
                                downloadFile = null
                            }.onFailure { e ->
                                if (e is ApiFailure && (e.status == 401 || e.status == 403)) {
                                    store.clearCredentials()
                                }
                                showOperationMessage((e as? Exception)?.safeInboxMessage() ?: "Could not delete item.", isError = true)
                            }
                        }
                    }
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Inbox", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { scope.launch { fetchPage(reset = true) } }, enabled = !pageLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh") }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search files, links, or devices…") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = "Search inbox")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "all" to "All",
                    "file" to "Files",
                    "url" to "Links",
                    "text" to "Text"
                ).forEach { (typeKey, label) ->
                    val isSelected = filterType == typeKey
                    FilterChip(
                        selected = isSelected,
                        onClick = { filterType = typeKey },
                        label = { Text(label) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Filled.Done, contentDescription = null, Modifier.size(16.dp)) }
                        } else null,
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            selectedBorderWidth = 1.5.dp
                        )
                    )
                }
            }

            if (searchQuery.isNotBlank()) {
                val count = filteredRows.size
                Text(
                    text = "$count result${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (isBackgroundSyncing || (hasLoaded && pageLoading)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            operationMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (operationMessageIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            msg,
                            color = if (operationMessageIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showOperationMessage(null) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss notice", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (pageLoading && !hasLoaded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(3) {
                        InboxSkeletonCard()
                    }
                }
            }
            pageError?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { scope.launch { fetchPage(reset = pageRetryReset) } }, enabled = !pageLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
            }
            if (hasLoaded) {
                PullToRefreshBox(
                    isRefreshing = pageLoading,
                    onRefresh = { scope.launch { fetchPage(reset = true) } },
                    modifier = Modifier.weight(1f)
                ) {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (searchQuery.isBlank()) {
                            quota?.let { q ->
                                item(key = "inbox-quota-card") {
                                    QuotaCard(q)
                                }
                            }
                        }
                        if (filteredRows.isEmpty() && pageError == null) {
                            item(key = "inbox-empty-message") {
                                InboxEmptyCard(
                                    searchQuery = searchQuery,
                                    filterType = filterType,
                                    onRefresh = { scope.launch { fetchPage(reset = true) } }
                                )
                            }
                        }
                        items(filteredRows, key = { it.id }) { item ->
                            InboxRow(
                                item = item,
                                modifier = Modifier.animateItem(),
                                onClick = { scope.launch { openDetail(item.id) } },
                                onDelete = {
                                    scope.launch {
                                        val res = api.deleteItem(item.id)
                                        res.onSuccess {
                                            rows = rows.filter { it.id != item.id }
                                            val updatedQuota = quota?.let { q ->
                                                q.copy(
                                                    activeItems = maxOf(0L, q.activeItems - 1),
                                                    activeBytes = maxOf(0L, q.activeBytes - item.sizeBytes)
                                                )
                                            }
                                            quota = updatedQuota
                                            store.saveInboxCache(CachedInbox(ownerKey, System.currentTimeMillis(), rows, nextCursor, updatedQuota))
                                            showOperationMessage("Item deleted.")
                                        }.onFailure { e ->
                                            if (e is ApiFailure && (e.status == 401 || e.status == 403)) {
                                                store.clearCredentials()
                                            }
                                            showOperationMessage((e as? Exception)?.safeInboxMessage() ?: "Could not delete item.", isError = true)
                                        }
                                    }
                                }
                            )
                        }
                        if (searchQuery.isBlank() && rows.isNotEmpty() && nextCursor != null) item(key = "inbox-load-more") {
                            OutlinedButton(onClick = { scope.launch { fetchPage(reset = false) } }, enabled = !pageLoading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (pageLoading) "Loading more…" else "Load more")
                            }
                        }
                        if (pageLoading) item(key = "inbox-more-progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxRow(
    item: InboxSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showConfirmDelete by remember { mutableStateOf(false) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                showConfirmDelete = true
            }
            false
        }
    )

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete item") },
            text = { Text("Delete this item from Vault? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CardDefaults.shape)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete item",
                    tint = MaterialTheme.colorScheme.onError
                )
            }
        }
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = "${item.displayName}, ${item.type}, ${formatBytes(item.sizeBytes)}, from ${item.sourceDevice.name}, expires ${formatInstant(item.expiresAt)}. Open details."
                }
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.width(8.dp))
                    TypeBadge(type = item.type, sizeBytes = item.sizeBytes)
                }
                Text("${item.type} · ${formatBytes(item.sizeBytes)} · ${item.state.lowercase()}")
                Text("From ${item.sourceDevice.name} · expires ${formatInstant(item.expiresAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DetailContent(
    item: InboxDetail,
    busy: Boolean,
    progressBytes: Long,
    message: String?,
    messageIsError: Boolean,
    onCopy: (String) -> Unit,
    onOpenUrl: () -> Unit,
    onDownload: () -> Unit,
    onOpenFile: () -> Unit,
    onDelete: () -> Unit,
) {
    var showConfirmDelete by remember { mutableStateOf(false) }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete item") },
            text = { Text("Delete this item from Vault? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("${item.type} · ${item.contentType ?: "text/plain"} · ${formatBytes(item.sizeBytes)}")
        Text("From ${item.sourceDevice.name} · expires ${formatInstant(item.expiresAt)}")
        when (item.type.lowercase()) {
            "url" -> {
                val exactValue = item.text.orEmpty()
                Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer { Text(AnnotatedString(exactValue), Modifier.padding(12.dp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenUrl,
                        enabled = !busy && isSafeExternalHttpUrl(exactValue),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open in Browser")
                    }
                    OutlinedButton(
                        onClick = { onCopy(exactValue) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy link")
                    }
                }
            }
            "text" -> {
                val exactValue = item.text.orEmpty()
                Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer { Text(AnnotatedString(exactValue), Modifier.padding(12.dp)) }
                }
                Button(
                    onClick = { onCopy(exactValue) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy text")
                }
            }
            else -> {
                Text("SHA-256: ${item.sha256 ?: "Unavailable"}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDownload,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download")
                    }
                    OutlinedButton(
                        onClick = onOpenFile,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open file")
                    }
                }
            }
        }
        Button(
            onClick = { showConfirmDelete = true },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Delete item")
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (progressBytes > 0 && item.sizeBytes > 0) Text("Receiving and verifying: ${formatBytes(progressBytes)} of ${formatBytes(item.sizeBytes)}.")
        }
        message?.let { Text(it, color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun InboxEmptyCard(
    searchQuery: String,
    filterType: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDefaultEmpty = searchQuery.isBlank() && filterType == "all"
    val headingText = if (isDefaultEmpty) {
        "Your inbox is empty."
    } else if (searchQuery.isNotBlank()) {
        "No items match \"$searchQuery\"."
    } else {
        "No ${filterType} items in your inbox."
    }

    val subtitleText = if (isDefaultEmpty) {
        "Items sent from your paired devices or web app will appear here instantly."
    } else {
        "Try adjusting your search terms or filter selection to find what you're looking for."
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (searchQuery.isNotBlank()) Icons.Filled.Search else Icons.Filled.Inbox,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = headingText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Text("Check for updates")
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024 * 1024)} MB"
}

internal fun formatInstant(value: String): String = runCatching {
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
