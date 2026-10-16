package app.vault.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PairingCodeEntry(
    value: String,
    onValueChange: (String) -> Unit,
    onContinue: () -> Unit,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paste the pairing QR data or link from Vault, or scan the QR code.")
        OutlinedTextField(value, onValueChange, label = { Text("Pairing QR data or vault://pair link") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onContinue, enabled = value.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Continue") }
            OutlinedButton(onClick = onScan, modifier = Modifier.heightIn(min = 48.dp)) { Text("Scan QR") }
        }
    }
}

@Composable
internal fun SignOutConfirmationDialog(
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sign out everywhere?") },
        text = { Text("This revokes this phone and every other active Vault session. You will need to pair devices again.") },
        confirmButton = { TextButton(enabled = !busy, onClick = onConfirm) { Text("Sign out everywhere") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
