package app.vault.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.net.URI

internal enum class ComposeKind { TEXT, LINK, FILE }

@Composable
internal fun SendHomeScreen(
    onText: () -> Unit,
    onLink: () -> Unit,
    onFile: () -> Unit,
    serverOrigin: String? = null,
    drafts: List<Draft> = emptyList(),
    onReview: (String) -> Unit = {},
    onRetry: (Draft) -> Unit = {},
    onRemove: (Draft) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pendingDrafts = remember(drafts) {
        drafts.filter { it.state != DraftState.AVAILABLE }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (pendingDrafts.isNotEmpty()) {
            OutboxQueueCard(
                pendingDrafts = pendingDrafts,
                onReview = onReview,
                onRetry = onRetry,
                onDiscard = onRemove,
            )
        }

        Text("Move something between Vault on this phone and your PC.", style = MaterialTheme.typography.bodyLarge)
        serverOrigin?.let { origin ->
            val host = runCatching { URI(origin).host }.getOrNull() ?: origin
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Text("Paired with $host", Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        SendChoiceCard("Text", "Write or paste a note", Icons.Filled.Description, onText)
        SendChoiceCard("Link", "Send a web address", Icons.Filled.Link, onLink)
        SendChoiceCard("File", "Choose a photo or document", Icons.Filled.FolderOpen, onFile)
        Text("You’ll review each item before it is sent. Your paired devices use the same Vault inbox.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutboxQueueCard(
    pendingDrafts: List<Draft>,
    onReview: (String) -> Unit,
    onRetry: (Draft) -> Unit,
    onDiscard: (Draft) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Outbox Queue (${pendingDrafts.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            pendingDrafts.forEachIndexed { index, draft ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = draft.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )

                    when (draft.state) {
                        DraftState.QUEUED, DraftState.RETRYING -> {
                            Text(
                                text = "Waiting for network",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DraftState.UPLOADING -> {
                            val pct = percentSent(draft)
                            Text(
                                text = "Uploading · $pct%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            if (draft.sizeBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (draft.bytesSent.toFloat() / draft.sizeBytes).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )
                            }
                        }
                        DraftState.FAILED -> {
                            Text(
                                text = "Failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                            draft.safeError?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onRetry(draft) },
                                    modifier = Modifier.heightIn(min = 40.dp)
                                ) {
                                    Text("Retry")
                                }
                                OutlinedButton(
                                    onClick = { onDiscard(draft) },
                                    modifier = Modifier.heightIn(min = 40.dp)
                                ) {
                                    Text("Discard")
                                }
                            }
                        }
                        DraftState.PREPARED -> {
                            Text(
                                text = "Ready to review",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Button(
                                onClick = { onReview(draft.id) },
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                Text("Review send")
                            }
                        }
                        DraftState.AVAILABLE -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun SendChoiceCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
internal fun SendComposerScreen(
    kind: ComposeKind,
    text: String,
    displayName: String,
    selectedFile: Draft?,
    preparingFile: Boolean,
    notice: String?,
    onTextChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onChooseFile: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textValid = text.isNotBlank() && isShareTextWithinLimit(text)
    val urlValid = isSafeExternalHttpUrl(text.trim()) && isShareTextWithinLimit(text.trim())
    val ready = when (kind) {
        ComposeKind.TEXT -> textValid
        ComposeKind.LINK -> urlValid
        ComposeKind.FILE -> selectedFile != null && !preparingFile
    }
    Column(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (kind) {
                ComposeKind.TEXT -> {
                    Text("Text is sent exactly as entered.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(text, onTextChange, label = { Text("Text to send") }, modifier = Modifier.fillMaxWidth(), minLines = 6)
                    Text("${text.toByteArray(Charsets.UTF_8).size} / 262,144 bytes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ComposeKind.LINK -> {
                    Text("Vault shares the link; it does not fetch a preview.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(text, onTextChange, label = { Text("https://…") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    if (text.isNotBlank() && !urlValid) Text("Enter a complete HTTP or HTTPS address without spaces or credentials.", color = MaterialTheme.colorScheme.error)
                }
                ComposeKind.FILE -> {
                    Text("Choose one photo or document, up to 100 MB. The file is prepared in encrypted app storage before review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    selectedFile?.let { file ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(file.displayName, style = MaterialTheme.typography.titleMedium)
                                Text("${file.contentType} · ${formatBytes(file.sizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedButton(onClick = onChooseFile, enabled = !preparingFile, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (preparingFile) "Preparing file…" else if (selectedFile == null) "Choose a file" else "Choose a different file")
                    }
                }
            }
            OutlinedTextField(displayName, onNameChange, label = { Text("Name (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Surface(tonalElevation = 2.dp) {
            Button(onClick = onReview, enabled = ready, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp)) {
                Text("Review send")
            }
        }
    }
}

@Composable
internal fun ReviewSendScreen(
    draft: Draft,
    onSend: (retentionDays: Int, retentionHours: Int?) -> Unit,
    onEdit: (retentionDays: Int, retentionHours: Int?) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retentionDays by remember(draft.id) { mutableIntStateOf(draft.retentionDays) }
    var retentionHours by remember(draft.id) { mutableStateOf(draft.retentionHours) }
    var retentionExpanded by remember(draft.id) { mutableStateOf(false) }
    var discardConfirm by remember(draft.id) { mutableStateOf(false) }
    val kind = draft.contentKind.toComposeKind(draft.text)

    val retentionLabel = if (retentionHours != null) {
        "$retentionHours hour${if (retentionHours == 1) "" else "s"}"
    } else {
        "$retentionDays day${if (retentionDays == 1) "" else "s"}"
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Review send", style = MaterialTheme.typography.headlineSmall)
            Text(when (kind) { ComposeKind.TEXT -> "Text"; ComposeKind.LINK -> "Link"; ComposeKind.FILE -> "File" }, color = MaterialTheme.colorScheme.primary)
            Text(draft.displayName, style = MaterialTheme.typography.titleLarge)
            if (kind == ComposeKind.FILE) {
                Text("${draft.contentType} · ${formatBytes(draft.sizeBytes)}")
                Text("This file will be verified before it is available in Vault.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer { Text(draft.text.orEmpty(), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            Text("Destination: ${runCatching { URI(draft.serverOrigin).host }.getOrNull() ?: draft.serverOrigin}", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { retentionExpanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Keep for $retentionLabel · then delete")
                    }
                    DropdownMenu(expanded = retentionExpanded, onDismissRequest = { retentionExpanded = false }) {
                        listOf(1, 4, 12).forEach { hours ->
                            DropdownMenuItem(
                                text = { Text("$hours hour${if (hours == 1) "" else "s"}") },
                                onClick = {
                                    retentionHours = hours
                                    retentionExpanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        (1..7).forEach { days ->
                            DropdownMenuItem(
                                text = { Text("$days day${if (days == 1) "" else "s"}") },
                                onClick = {
                                    retentionDays = days
                                    retentionHours = null
                                    retentionExpanded = false
                                }
                            )
                        }
                    }
                }

                // Quick preset chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "1h" to (1 to true),
                        "4h" to (4 to true),
                        "12h" to (12 to true),
                        "1d" to (1 to false),
                        "3d" to (3 to false),
                        "7d" to (7 to false)
                    ).forEach { (label, config) ->
                        val (value, isHour) = config
                        val selected = if (isHour) retentionHours == value else (retentionHours == null && retentionDays == value)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (isHour) {
                                    retentionHours = value
                                } else {
                                    retentionDays = value
                                    retentionHours = null
                                }
                            },
                            label = { Text(label) },
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                                selectedBorderWidth = 1.5.dp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        val haptics = LocalHapticFeedback.current
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend(retentionDays, retentionHours)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Send to Vault") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(retentionDays, retentionHours) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit") }
                    TextButton(onClick = { discardConfirm = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Discard") }
                }
            }
        }
    }
    if (discardConfirm) {
        AlertDialog(
            onDismissRequest = { discardConfirm = false },
            title = { Text("Discard this send?") },
            text = { Text("The prepared item and any encrypted temporary file will be removed.") },
            confirmButton = { TextButton(onClick = { discardConfirm = false; onDiscard() }) { Text("Discard send") } },
            dismissButton = { TextButton(onClick = { discardConfirm = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
internal fun ReviewSendScreen(
    draft: Draft,
    onSend: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) = ReviewSendScreen(
    draft = draft,
    onSend = { days, hours -> onSend(hours ?: days) },
    onEdit = { days, _ -> onEdit(days) },
    onDiscard = onDiscard,
    modifier = modifier,
)

private fun percentSent(draft: Draft): Int = if (draft.sizeBytes <= 0) 0 else ((draft.bytesSent.coerceIn(0, draft.sizeBytes) * 100) / draft.sizeBytes).toInt()

internal fun DraftContentKind.toComposeKind(text: String?): ComposeKind = when (this) {
    DraftContentKind.TEXT -> ComposeKind.TEXT
    DraftContentKind.URL -> ComposeKind.LINK
    DraftContentKind.FILE -> ComposeKind.FILE
    DraftContentKind.AUTO -> if (text != null && isSafeExternalHttpUrl(text.trim())) ComposeKind.LINK else if (text != null) ComposeKind.TEXT else ComposeKind.FILE
}
