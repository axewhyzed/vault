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
import androidx.compose.material.icons.filled.ArrowDropDown
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
    liveProgress: Map<String, Int> = emptyMap(),
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
                liveProgress = liveProgress,
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
    liveProgress: Map<String, Int> = emptyMap(),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = draft.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        if (draft.state == DraftState.QUEUED || draft.state == DraftState.UPLOADING) {
                            TextButton(
                                onClick = { onDiscard(draft) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    when (draft.state) {
                        DraftState.QUEUED -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Queued for upload",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        DraftState.RETRYING -> {
                            Text(
                                text = draft.safeError ?: "Waiting for network connection…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onRetry(draft) },
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text("Retry now")
                                }
                                OutlinedButton(
                                    onClick = { onDiscard(draft) },
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                        DraftState.UPLOADING -> {
                            val livePct = liveProgress[draft.id]
                            val pct = livePct ?: percentSent(draft)
                            Text(
                                text = "Uploading · $pct%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            if (draft.sizeBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { (pct / 100f).coerceIn(0f, 1f) },
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
    var retentionUnit by remember(draft.id) {
        mutableStateOf(if (draft.retentionHours != null) "hours" else "days")
    }
    var retentionAmountText by remember(draft.id) {
        mutableStateOf((draft.retentionHours ?: draft.retentionDays).toString())
    }
    var unitDropdownExpanded by remember(draft.id) { mutableStateOf(false) }
    var discardConfirm by remember(draft.id) { mutableStateOf(false) }
    val kind = draft.contentKind.toComposeKind(draft.text)

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
                Text(
                    text = "Validity (Retention)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    OutlinedTextField(
                        value = retentionAmountText,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }.take(3)
                            val max = if (retentionUnit == "hours") 168 else 7
                            val parsed = digits.toIntOrNull()
                            retentionAmountText = when {
                                digits.isEmpty() -> ""
                                parsed != null -> parsed.coerceIn(1, max).toString()
                                else -> digits
                            }
                        },
                        label = { Text("Keep for") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = {
                            Text(if (retentionUnit == "hours") "1-168 hours" else "1-7 days")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.1f)
                    )

                    Box(modifier = Modifier.weight(0.9f)) {
                        OutlinedTextField(
                            value = if (retentionUnit == "hours") "Hours" else "Days",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Unit") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "Select unit"
                                )
                            },
                            supportingText = {
                                Text("Time unit")
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { unitDropdownExpanded = true }
                        )
                        DropdownMenu(
                            expanded = unitDropdownExpanded,
                            onDismissRequest = { unitDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Days (1-7)") },
                                onClick = {
                                    if (retentionUnit != "days") {
                                        val currentVal = retentionAmountText.toIntOrNull() ?: 24
                                        val newDays = kotlin.math.round(currentVal / 24.0).toInt().coerceIn(1, 7)
                                        retentionUnit = "days"
                                        retentionAmountText = newDays.toString()
                                    }
                                    unitDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Hours (1-168)") },
                                onClick = {
                                    if (retentionUnit != "hours") {
                                        val currentVal = retentionAmountText.toIntOrNull() ?: 1
                                        val newHours = (currentVal * 24).coerceIn(1, 168)
                                        retentionUnit = "hours"
                                        retentionAmountText = newHours.toString()
                                    }
                                    unitDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                val currentAmount = retentionAmountText.toIntOrNull() ?: (if (retentionUnit == "hours") 24 else 7)
                val summaryLabel = if (retentionUnit == "hours") {
                    "$currentAmount hour${if (currentAmount == 1) "" else "s"}"
                } else {
                    "$currentAmount day${if (currentAmount == 1) "" else "s"}"
                }
                Text(
                    text = "Items automatically delete from Vault after $summaryLabel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val haptics = LocalHapticFeedback.current
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val finalAmount = retentionAmountText.toIntOrNull()
                    ?.coerceIn(1, if (retentionUnit == "hours") 168 else 7)
                    ?: (if (retentionUnit == "hours") 24 else 7)
                val finalDays = if (retentionUnit == "days") finalAmount else 1
                val finalHours = if (retentionUnit == "hours") finalAmount else null

                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSend(finalDays, finalHours)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("Send to Vault") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onEdit(finalDays, finalHours) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Edit") }
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
