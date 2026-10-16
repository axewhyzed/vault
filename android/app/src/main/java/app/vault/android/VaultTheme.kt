package app.vault.android

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightVaultColors = lightColorScheme(
    primary = Color(0xFF2457C5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE8FF),
    onPrimaryContainer = Color(0xFF102B64),
    secondary = Color(0xFF476178),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF171B22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171B22),
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = Color(0xFF46515F),
    outline = Color(0xFF747E8B),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkVaultColors = darkColorScheme(
    primary = Color(0xFFB7C8FF),
    onPrimary = Color(0xFF102B64),
    primaryContainer = Color(0xFF25458C),
    onPrimaryContainer = Color(0xFFE0E8FF),
    secondary = Color(0xFFB7CADA),
    onSecondary = Color(0xFF233746),
    background = Color(0xFF111419),
    onBackground = Color(0xFFE4E8EF),
    surface = Color(0xFF191E25),
    onSurface = Color(0xFFE4E8EF),
    surfaceVariant = Color(0xFF29313C),
    onSurfaceVariant = Color(0xFFC2CBD7),
    outline = Color(0xFF8D98A6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
internal fun VaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkVaultColors else LightVaultColors, content = content)
}
