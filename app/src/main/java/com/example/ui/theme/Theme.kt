package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RenderaColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003844),
    onPrimaryContainer = NeonCyan,
    secondary = ElectricViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF381463),
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = SafeGreen,
    onTertiary = Color.Black,
    error = ThreatRed,
    onError = Color.White,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = BorderCyan
)

@Composable
fun RenderaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RenderaColorScheme,
        typography = Typography,
        content = content
    )
}
