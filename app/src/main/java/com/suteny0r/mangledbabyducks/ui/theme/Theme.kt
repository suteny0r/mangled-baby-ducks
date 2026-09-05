package com.suteny0r.mangledbabyducks.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The iOS app pins one AccentColor asset (sRGB 0.157, 0.333, 0.659) for light and dark;
 * mirror that here instead of Material You dynamic color so both ports read the same.
 */
private val AccentBlue = Color(0xFF2855A8)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentBlue,
)

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    secondary = AccentBlue,
)

@Composable
fun MeshtasticTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
