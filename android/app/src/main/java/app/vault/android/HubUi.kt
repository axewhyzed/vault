package app.vault.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HubScreen(
    api: VaultApi,
    store: EncryptedStore,
    credentials: Credentials,
    deviceName: String,
    onSignOutCurrentDevice: () -> Unit,
    onSignOutEverywhere: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var pingLatencyMs by remember { mutableStateOf<Long?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var spoolBytes by remember { mutableLongStateOf(store.spoolSize()) }

    val cached = remember { store.cachedInbox() }
    val items = cached?.items ?: emptyList()
    val quota = cached?.quota

    fun checkPing() {
        if (pinging) return
        pinging = true
        scope.launch {
            try {
                val latency = withContext(Dispatchers.IO) { api.ping() }
                pingLatencyMs = latency
            } finally {
                pinging = false
            }
        }
    }

    LaunchedEffect(credentials.origin) {
        checkPing()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Vault Hub",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        )

        // 1. Server Status Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Server Connection",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }

                    IconButton(onClick = ::checkPing, enabled = !pinging) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Test connection ping",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = credentials.origin,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val isConnected = pingLatencyMs != null
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Color(0xFF34A853) else MaterialTheme.colorScheme.error),
                    )
                    Text(
                        text = if (isConnected) "Connected · ${pingLatencyMs} ms latency" else if (pinging) "Pinging server…" else "Server unreachable",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isConnected) Color(0xFF34A853) else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // 2. Connected Devices List
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Connected Devices",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )

                // Current Phone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = "This phone · Native client",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Active", style = MaterialTheme.typography.labelSmall) },
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // Paired PC / Web
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Paired PC / Web Browser",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Text(
                            text = "Linked desktop workspace",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Paired", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        // 3. Visual Storage Quota Card (Segmented Multi-Color Bar)
        val fileBytes = items.filter { it.type == "file" }.sumOf { it.sizeBytes }
        val linkBytes = items.filter { it.type == "url" }.sumOf { it.sizeBytes }
        val textBytes = items.filter { it.type == "text" }.sumOf { it.sizeBytes }
        val totalUsedBytes = quota?.activeBytes ?: (fileBytes + linkBytes + textBytes)
        val maxQuotaBytes = quota?.maxActiveBytes ?: 2_147_483_648L

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Storage Breakdown",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    val percent = if (maxQuotaBytes > 0) ((totalUsedBytes.toFloat() / maxQuotaBytes) * 100).toInt() else 0
                    Text(
                        text = "${formatBytes(totalUsedBytes)} / ${formatBytes(maxQuotaBytes)} · $percent%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Segmented Multi-Color Bar
                SegmentedStorageBar(
                    fileBytes = fileBytes,
                    linkBytes = linkBytes,
                    textBytes = textBytes,
                    maxBytes = maxQuotaBytes,
                )

                // Legends
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val fileCount = items.count { it.type == "file" }
                    val linkCount = items.count { it.type == "url" }
                    val textCount = items.count { it.type == "text" }

                    StorageLegendItem(color = Color(0xFF2E7D32), label = "Files", detail = "${formatBytes(fileBytes)} ($fileCount)")
                    StorageLegendItem(color = Color(0xFF6750A4), label = "Links", detail = "${formatBytes(linkBytes)} ($linkCount)")
                    StorageLegendItem(color = Color(0xFF0288D1), label = "Text", detail = "${formatBytes(textBytes)} ($textCount)")
                }
            }
        }

        // 4. Local Device Cache Manager
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CleaningServices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Local Device Cache",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }

                Text(
                    text = "Temporary upload spools and decrypted item cache: ${formatBytes(spoolBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = {
                        store.clearLocalCache()
                        spoolBytes = store.spoolSize()
                        onShowMessage("Local cache cleared.")
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Local Cache")
                }
            }
        }

        // 5. Account Controls
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Standard Single-Device Sign Out (Phone only)
            OutlinedButton(
                onClick = onSignOutCurrentDevice,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out (This Phone Only)")
            }

            // Danger Zone: Sign Out Everywhere
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Danger Zone",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Text(
                        text = "Revoking all sessions immediately disconnects this phone and every other active Vault session on desktop and mobile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onSignOutEverywhere,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Sign Out Everywhere")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SegmentedStorageBar(
    fileBytes: Long,
    linkBytes: Long,
    textBytes: Long,
    maxBytes: Long,
) {
    val totalBytes = maxOf(maxBytes, fileBytes + linkBytes + textBytes, 1L)
    val fileWeight = (fileBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val linkWeight = (linkBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val textWeight = (textBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val remainingWeight = (1f - fileWeight - linkWeight - textWeight).coerceAtLeast(0.001f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (fileWeight > 0f) {
            Box(
                modifier = Modifier
                    .weight(fileWeight)
                    .fillMaxHeight()
                    .background(Color(0xFF2E7D32)),
            )
        }
        if (linkWeight > 0f) {
            Box(
                modifier = Modifier
                    .weight(linkWeight)
                    .fillMaxHeight()
                    .background(Color(0xFF6750A4)),
            )
        }
        if (textWeight > 0f) {
            Box(
                modifier = Modifier
                    .weight(textWeight)
                    .fillMaxHeight()
                    .background(Color(0xFF0288D1)),
            )
        }
        Box(
            modifier = Modifier
                .weight(remainingWeight)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun StorageLegendItem(
    color: Color,
    label: String,
    detail: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
