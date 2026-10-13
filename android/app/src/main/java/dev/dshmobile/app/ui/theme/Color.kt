package dev.dshmobile.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Monochrome instrument: neutral ink on near-black or white. The primary action is the
 * inverse of the ground (white on dark, black on light). The only hues are functional:
 * success (connection online), warning (waiting / uncertain delivery), error.
 */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F3), onPrimary = Color(0xFF0B0B0C),
    primaryContainer = Color(0xFF2A2A2E), onPrimaryContainer = Color(0xFFF2F2F3),
    secondary = Color(0xFFBDBDC3), onSecondary = Color(0xFF0B0B0C),
    secondaryContainer = Color(0xFF3A3A40), onSecondaryContainer = Color(0xFFF2F2F3),
    tertiary = Color(0xFFE3B566), onTertiary = Color(0xFF2B2108),
    background = Color(0xFF0B0B0C), onBackground = Color(0xFFF2F2F3),
    surface = Color(0xFF0B0B0C), onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Color(0xFF1F1F22), onSurfaceVariant = Color(0xFF9C9CA3),
    surfaceContainerLowest = Color(0xFF0B0B0C), surfaceContainerLow = Color(0xFF131315),
    surfaceContainer = Color(0xFF18181B), surfaceContainerHigh = Color(0xFF202023), surfaceContainerHighest = Color(0xFF2A2A2E),
    outline = Color(0xFF5E5E66), outlineVariant = Color(0xFF26262A),
    error = Color(0xFFFF7B72), onError = Color(0xFF3D0B08), errorContainer = Color(0xFF3B1714), onErrorContainer = Color(0xFFFFDAD5),
    inverseSurface = Color(0xFFF2F2F3), inverseOnSurface = Color(0xFF18181B), inversePrimary = Color(0xFF0B0B0C),
    surfaceTint = Color.Transparent, scrim = Color(0xFF000000),
)
internal val LightColors = lightColorScheme(
    primary = Color(0xFF0D0D0F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E6E9), onPrimaryContainer = Color(0xFF0D0D0F),
    secondary = Color(0xFF4A4A50), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCDCE0), onSecondaryContainer = Color(0xFF0D0D0F),
    tertiary = Color(0xFF8A5A00), onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF0D0D0F),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0D0D0F),
    surfaceVariant = Color(0xFFF0F0F2), onSurfaceVariant = Color(0xFF65656C),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF2F2F4), surfaceContainerHigh = Color(0xFFECECEF), surfaceContainerHighest = Color(0xFFE4E4E8),
    outline = Color(0xFF8C8C94), outlineVariant = Color(0xFFE4E4E8),
    error = Color(0xFFC0362C), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFDE4E1), onErrorContainer = Color(0xFF5A110C),
    inverseSurface = Color(0xFF18181B), inverseOnSurface = Color(0xFFF2F2F3), inversePrimary = Color(0xFFF2F2F3),
    surfaceTint = Color.Transparent, scrim = Color(0xFF000000),
)
internal data class MobileColors(
    val action: Color = Color(0xFFF2F2F3), val onAction: Color = Color(0xFF0B0B0C),
    val warning: Color = Color(0xFFE3B566), val warningContainer: Color = Color(0xFF2A2417),
    val success: Color = Color(0xFF4CC38A),
)
internal val LightMobileColors = MobileColors(action = Color(0xFF0D0D0F), onAction = Color(0xFFFFFFFF),
    warning = Color(0xFF8A5A00), warningContainer = Color(0xFFFBF0DA), success = Color(0xFF1F8A55))
internal val LocalMobileColors = staticCompositionLocalOf { MobileColors() }
