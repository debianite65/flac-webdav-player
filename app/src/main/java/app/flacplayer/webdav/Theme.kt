package app.flacplayer.webdav

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FlacGreen = Color(0xFF00E61A)
val FlacYellow = Color(0xFFFFE600)
val FlacRed = Color(0xFFFF2A1A)

private val Colors = darkColorScheme(
    primary = FlacGreen,
    onPrimary = Color.Black,
    secondary = FlacYellow,
    onSecondary = Color.Black,
    tertiary = FlacRed,
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1B1B1B),
    onSurfaceVariant = Color(0xFFB4B4B4),
    error = Color(0xFFFF6B5E),
)

@Composable
fun FlacTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
