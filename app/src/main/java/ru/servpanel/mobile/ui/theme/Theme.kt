package ru.servpanel.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра повторяет оформление веб-панели Servpanel.
val PanelBg = Color(0xFF070B12)
val PanelSurface = Color(0xFF111827)
val PanelElevated = Color(0xFF1A2740)
val PanelBorder = Color(0xFF22304A)
val PanelText = Color(0xFFE8EEFC)
val PanelMuted = Color(0xFF8FA0BD)
val PanelDim = Color(0xFF66758F)
val PanelAccent = Color(0xFF5B8CFF)
val PanelViolet = Color(0xFF8B5CF6)
val PanelGreen = Color(0xFF2DD4A7)
val PanelAmber = Color(0xFFF5B84B)
val PanelRed = Color(0xFFFF6B6B)

private val servpanelColors = darkColorScheme(
    primary = PanelAccent,
    onPrimary = Color.White,
    primaryContainer = PanelElevated,
    onPrimaryContainer = PanelText,
    secondary = PanelViolet,
    onSecondary = Color.White,
    secondaryContainer = PanelElevated,
    onSecondaryContainer = PanelText,
    tertiary = PanelGreen,
    onTertiary = Color(0xFF04120D),
    background = PanelBg,
    onBackground = PanelText,
    surface = PanelSurface,
    onSurface = PanelText,
    surfaceVariant = PanelElevated,
    onSurfaceVariant = PanelMuted,
    surfaceContainer = PanelSurface,
    surfaceContainerHigh = PanelElevated,
    surfaceContainerHighest = PanelElevated,
    outline = PanelBorder,
    outlineVariant = PanelBorder,
    error = PanelRed,
    onError = Color.White
)

@Composable
fun ServpanelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = servpanelColors,
        content = content
    )
}
