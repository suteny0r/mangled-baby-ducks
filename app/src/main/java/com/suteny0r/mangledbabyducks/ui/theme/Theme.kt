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
 *
 * The rest of the palette is the iOS grouped-list look (UIColor.systemGroupedBackground
 * and friends): neutral gray page, white cards, gray secondary text, hairline dividers.
 * Material's default tonal surfaces tint everything toward the primary, which is why the
 * app used to render on a pink page.
 */
private val AccentBlue = Color(0xFF2855A8)
private val AccentBlueDark = Color(0xFF6B93E6)
val IosGreen = Color(0xFF34C759)
val IosRed = Color(0xFFFF3B30)
val IosOrange = Color(0xFFFF9500)
val IosGray = Color(0xFF8E8E93)

private val LightColors = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F5),
    onPrimaryContainer = AccentBlue,
    secondary = AccentBlue,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = AccentBlue,
    tertiary = IosGreen,
    error = IosRed,
    background = Color(0xFFF2F2F7),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFD1D1D6),
)

private val DarkColors = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1F3561),
    onPrimaryContainer = AccentBlueDark,
    secondary = AccentBlueDark,
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = AccentBlueDark,
    tertiary = IosGreen,
    error = IosRed,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = IosGray,
    surfaceContainerLowest = Color(0xFF1C1C1E),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0xFF3A3A3C),
)

@Composable
fun MeshtasticTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
