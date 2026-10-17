package app.vault.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VaultBrandEmblem(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 88.dp,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
        )
        Icon(
            painter = painterResource(R.drawable.ic_vault_logo),
            contentDescription = "Vault Emblem",
            tint = Color.Unspecified,
            modifier = Modifier.size(size * 0.92f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeScreen(
    onScanQr: () -> Unit,
    onLogin: (serverOrigin: String, identifier: String, password: String, deviceName: String) -> Unit,
    defaultOrigin: String,
    defaultDeviceName: String,
    busy: Boolean,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    var showLoginSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Bespoke Vault Emblem
        VaultBrandEmblem(size = 96.dp)

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "VAULT",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            shape = RoundedCornerShape(99.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
        ) {
            Text(
                text = "PERSONAL DATA RELAY",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Seamlessly transfer files, links, and text between your phone and your personal cloud.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            listOf("Local-First", "Zero-Cloud", "E2E Encrypted").forEach { tag ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        if (errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Hero Action: Scan QR Code
        Button(
            onClick = onScanQr,
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Scan QR Code", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Alternative Action: Sign In with Credentials
        OutlinedButton(
            onClick = { showLoginSheet = true },
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign In with Credentials", style = MaterialTheme.typography.bodyLarge)
        }
    }

    if (showLoginSheet) {
        ModalBottomSheet(
            onDismissRequest = { if (!busy) showLoginSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SignInBottomSheetContent(
                defaultOrigin = defaultOrigin,
                defaultDeviceName = defaultDeviceName,
                busy = busy,
                onDismiss = { showLoginSheet = false },
                onLogin = { origin, identifier, password, deviceName ->
                    onLogin(origin, identifier, password, deviceName)
                },
            )
        }
    }
}

@Composable
private fun SignInBottomSheetContent(
    defaultOrigin: String,
    defaultDeviceName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLogin: (origin: String, identifier: String, password: String, deviceName: String) -> Unit,
) {
    var serverOrigin by remember { mutableStateOf(defaultOrigin) }
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showAdvancedServer by remember { mutableStateOf(false) }

    val defaultHost = runCatching { java.net.URI(defaultOrigin).host }.getOrNull() ?: defaultOrigin
    val currentHost = runCatching { java.net.URI(serverOrigin).host }.getOrNull() ?: serverOrigin

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 36.dp, top = 8.dp)
            .imePadding()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Sign In with Credentials",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            text = "Authenticate directly using your Vault account credentials.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = identifier,
            onValueChange = { identifier = it },
            label = { Text("Username or Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                onLogin(serverOrigin.trim(), identifier.trim(), password, deviceName.trim())
            },
            enabled = !busy && serverOrigin.isNotBlank() && identifier.isNotBlank() && password.isNotEmpty() && deviceName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Text("Sign In", style = MaterialTheme.typography.titleMedium)
            }
        }

        TextButton(
            onClick = { showAdvancedServer = !showAdvancedServer },
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = if (!showAdvancedServer) {
                if (serverOrigin == defaultOrigin) "Using default server: $defaultHost (Edit) ▼"
                else "Using custom server: $currentHost (Edit) ▼"
            } else {
                "Hide custom server settings ▲"
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }

        AnimatedVisibility(visible = showAdvancedServer) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = serverOrigin,
                    onValueChange = { serverOrigin = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://vault.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it.take(100) },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
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

@Deprecated("Replaced with WelcomeScreen in Vault v2", ReplaceWith("WelcomeScreen(...)"))
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
