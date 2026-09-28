package dev.gab8bit.madovai.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Brand seed — the iOS app's accent color. */
val MadovaiBlue = Color(0xFF1E5EA8)

/** Fixed data-source colors (never themed: they carry meaning on the map / legend). */
object TransitColors {
    val CotralOrange = Color(0xFFF08C00)
    val AtacTeal = Color(0xFF14A3B8)
    val CotralPoleBus = Color(0xFF1E5EA8)
    val CotralPoleRail = Color(0xFF5B4FCF)
    val Favorite = Color(0xFFF5B800)
    val AtacStop = Color(0xFFF08C00)
    val Metro = Color(0xFFE53935)
    val Tram = Color(0xFFF57C00)
    val Treno = Color(0xFF8E44AD)
    val BusLine = Color(0xFF1E6FD9)
    val RomaTpl = Color(0xFF2E9D4A)
    val Live = Color(0xFF2E9D4A)
    val TrackedOffline = Color(0xFFF2B600)
    val Scheduled = Color(0xFF9E9E9E)
    val Late = Color(0xFFD32F2F)
    val Early = Color(0xFF2E7D32)
}

private val LightColors = lightColorScheme(
    primary = MadovaiBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF555F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF006876),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA2EEFF),
    onTertiaryContainer = Color(0xFF001F25),
    background = Color(0xFFFAF9FF),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFAF9FF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3D4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFF83D2E3),
    onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF004E59),
    onTertiaryContainer = Color(0xFFA2EEFF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1C20),
    surfaceContainer = Color(0xFF1E2025),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
)

/**
 * Material 3 / Material You: dynamic (wallpaper) color on Android 12+, otherwise a
 * brand scheme seeded from the iOS primary blue #1E5EA8.
 */
@Composable
fun MadovaiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
