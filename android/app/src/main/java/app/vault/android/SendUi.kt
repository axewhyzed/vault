package app.vault.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PreparedSendCard(draft: Draft, onSend: (Int) -> Unit, onCancel: () -> Unit) {
    var retention by remember(draft.id) { mutableIntStateOf(draft.retentionDays) }
    var expanded by remember(draft.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ready to send", style = MaterialTheme.typography.titleMedium)
            Text(draft.displayName)
            if (draft.text != null) Text(draft.text.take(240), maxLines = 3)
            else Text("${draft.contentType} · ${draft.sizeBytes} bytes")
            Text("Destination: Vault inbox")
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep for $retention day${if (retention == 1) "" else "s"} ▾") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    (1..7).forEach { days -> DropdownMenuItem(text = { Text("$days day${if (days == 1) "" else "s"}") }, onClick = { retention = days; expanded = false }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSend(retention) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Send") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}
