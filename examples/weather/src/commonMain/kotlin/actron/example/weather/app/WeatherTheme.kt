package actron.example.weather.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WeatherColors =
    lightColorScheme(
        primary = Color(0xFF2457A7),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEEF4FC),
        onPrimaryContainer = Color(0xFF16345F),
        secondary = Color(0xFF4B5563),
        secondaryContainer = Color(0xFFEDF2F8),
        onSecondaryContainer = Color(0xFF202B3B),
        background = Color.White,
        onBackground = Color(0xFF202124),
        surface = Color.White,
        onSurface = Color(0xFF202124),
        surfaceTint = Color.Transparent,
        surfaceVariant = Color(0xFFF3F4F6),
        onSurfaceVariant = Color(0xFF555B65),
        surfaceDim = Color(0xFFE5E7EB),
        surfaceBright = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F9FA),
        surfaceContainer = Color(0xFFF3F4F6),
        surfaceContainerHigh = Color(0xFFEEF0F2),
        surfaceContainerHighest = Color(0xFFE5E7EB),
        outline = Color(0xFF8C929A),
        outlineVariant = Color(0xFFDDE0E4),
    )

@Composable
fun WeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = WeatherColors, content = content)
}
