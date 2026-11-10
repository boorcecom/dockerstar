package com.boorce.solar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mêmes couleurs que le site web (une seule teinte pour la série, surfaces neutres).
private val LightColors = lightColorScheme(
    primary = Color(0xFF2A78D6),
    onPrimary = Color.White,
    background = Color(0xFFF4F3F0),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB),
    onSurface = Color(0xFF0B0B0B),
    surfaceVariant = Color(0xFFF1F0EC),
    onSurfaceVariant = Color(0xFF52514E),
    outline = Color(0xFFCFCEC8),
    outlineVariant = Color(0xFFE7E6E2),
    secondaryContainer = Color(0xFFDCE8F8), // pastille sélectionnée : teinte de la série, pas le violet par défaut
    onSecondaryContainer = Color(0xFF0B0B0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3987E5),
    onPrimary = Color.White,
    background = Color(0xFF111110),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19),
    onSurface = Color(0xFFF0EFEC),
    surfaceVariant = Color(0xFF242423),
    onSurfaceVariant = Color(0xFFC3C2B7),
    outline = Color(0xFF3A3A37),
    outlineVariant = Color(0xFF2B2B29),
    secondaryContainer = Color(0xFF1F3A5C),
    onSecondaryContainer = Color(0xFFF0EFEC),
)

@Composable
fun SolarTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/** Couleurs d'un avertissement (fond, texte) — jamais la couleur de la série. */
@Composable
fun warningColors(): Pair<Color, Color> =
    if (isSystemInDarkTheme()) Color(0xFF3A2C05) to Color(0xFFFFD98A) else Color(0xFFFFF4D6) to Color(0xFF5C3D00)
