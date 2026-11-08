package com.lucascanceco.pdfstudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightPalette = lightColorScheme(
    primary = NordicTeal, onPrimary = Color.White,
    primaryContainer = Color(0xFFD5ECF1), onPrimaryContainer = DeepInk,
    secondary = Color(0xFF226D63), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F2EF), onSecondaryContainer = DeepInk,
    tertiary = Color(0xFF805D77), onTertiary = Color.White,
    background = Cream, onBackground = DeepInk,
    surface = Color.White, onSurface = DeepInk,
    surfaceVariant = Color(0xFFECF1F2), onSurfaceVariant = Color(0xFF43545D),
    outlineVariant = Color(0xFFCBD7DB)
)

private val DarkPalette = darkColorScheme(
    primary = NordicTealLight, onPrimary = Color(0xFF103443),
    primaryContainer = Color(0xFF254A5A), onPrimaryContainer = Color(0xFFD5F4FB),
    secondary = Color(0xFF83E1D2), onSecondary = Color(0xFF173A35),
    secondaryContainer = Color(0xFF29534E), onSecondaryContainer = Color(0xFFD2F7EE),
    tertiary = Color(0xFFE2B6D2), onTertiary = Color(0xFF472B3E),
    background = NightBackground, onBackground = Color(0xFFEAF1F4),
    surface = NightSurface, onSurface = Color(0xFFEAF1F4),
    surfaceVariant = Color(0xFF2B3C49), onSurfaceVariant = Color(0xFFCFDDE3),
    outlineVariant = Color(0xFF526371)
)

@Composable
fun PdfStudioTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkPalette else LightPalette, content = content)
}
