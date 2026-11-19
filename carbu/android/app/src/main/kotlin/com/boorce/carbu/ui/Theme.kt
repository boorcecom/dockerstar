package com.boorce.carbu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mêmes teintes que le site web : vert sapin, surfaces neutres.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6E56), onPrimary = Color.White,
    background = Color(0xFFF4F3F0), onBackground = Color(0xFF1D2521),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1D2521),
    surfaceVariant = Color(0xFFEFEEEA), onSurfaceVariant = Color(0xFF5D6A63),
    outline = Color(0xFFCFCEC8), outlineVariant = Color(0xFFE2E0DA),
    secondaryContainer = Color(0xFFDFF1EA), onSecondaryContainer = Color(0xFF0B3D31),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3FBF98), onPrimary = Color(0xFF06130E),
    background = Color(0xFF101513), onBackground = Color(0xFFE9EEEB),
    surface = Color(0xFF19201D), onSurface = Color(0xFFE9EEEB),
    surfaceVariant = Color(0xFF222B27), onSurfaceVariant = Color(0xFF9AA8A0),
    outline = Color(0xFF3A4741), outlineVariant = Color(0xFF2B3631),
    secondaryContainer = Color(0xFF17352B), onSecondaryContainer = Color(0xFFE9EEEB),
)

@Composable
fun CarbuTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/** Couleur d'un niveau de fiabilité (good | medium | low). */
@Composable
fun levelColor(level: String): Color {
    val dark = isSystemInDarkTheme()
    return when (level) {
        "good" -> if (dark) Color(0xFF4CC387) else Color(0xFF1A7F4B)
        "medium" -> if (dark) Color(0xFFE0A94A) else Color(0xFFB7791F)
        else -> if (dark) Color(0xFFEE7B6F) else Color(0xFFB3392F)
    }
}

@Composable
fun warningColors(): Pair<Color, Color> =
    if (isSystemInDarkTheme()) Color(0xFF3A2F10) to Color(0xFFF0D58C) else Color(0xFFFFF3CD) to Color(0xFF5C4400)
