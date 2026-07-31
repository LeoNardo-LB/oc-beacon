package dev.leonardo.ocbeacon.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat

val LocalAmoledMode = staticCompositionLocalOf { false }

/**
 * Dark color scheme — only brand-differentiated tokens are overridden.
 * All other tokens fall back to Material3 [darkColorScheme] defaults,
 * which are designed and tested for correct contrast and elevation semantics.
 */
private val DarkColorScheme = darkColorScheme(
    // Brand colors: Indigo primary + Cyan tertiary (OpenCode identity)
    primary = Color(0xFF9DA3FF),
    onPrimary = Color(0xFF1A1B4B),
    primaryContainer = Color(0xFF2D2F6E),
    onPrimaryContainer = Color(0xFFDEE0FF),
    tertiary = Color(0xFF7DD0E1),
    onTertiary = Color(0xFF003640),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF4F52B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF0C0F6A),
    secondary = Color(0xFF5D5B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3DFF9),
    onSecondaryContainer = Color(0xFF1A182C),
    tertiary = Color(0xFF006879),
    onTertiary = Color(0xFFFFFFFF),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE5E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainer = Color(0xFFF3EFF7),
    surfaceContainerHigh = Color(0xFFECE8F1),
    surfaceContainerHighest = Color(0xFFE6E2EB),
    outline = Color(0xFF787680),
    outlineVariant = Color(0xFFC9C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

/**
 * AMOLED dark color scheme — pure black surfaces for OLED battery savings.
 * Uses true black (#000000) for the main surface and very dark tones for containers,
 * ensuring cards/sheets are still visually distinguishable from the background.
 */
private val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1A1A22),
    surfaceContainer = Color(0xFF0D0D12),
    surfaceContainerLow = Color(0xFF080810),
    surfaceContainerLowest = Color.Black,
    surfaceContainerHigh = Color(0xFF141419),
    surfaceContainerHighest = Color(0xFF2A2A36)
)

/**
 * OpenCode Material 3 Theme
 * 
 * Supports:
 * - Light/Dark theme based on system settings
 * - Dynamic color on Android 12+ (Material You)
 * - AMOLED dark mode with pure black surfaces
 * - Edge-to-edge display
 */
@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoledDark: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val scheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && amoledDark) {
                // Only override surface/container tokens for AMOLED pure-black effect.
                // Preserve dynamic color's onSurface/onSurfaceVariant so the
                // wallpaper-generated palette remains consistent.
                scheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF1A1A22),
                    surfaceContainer = Color(0xFF0D0D12),
                    surfaceContainerLow = Color(0xFF080810),
                    surfaceContainerLowest = Color.Black,
                    surfaceContainerHigh = Color(0xFF141419),
                    surfaceContainerHighest = Color(0xFF2A2A36)
                )
            } else {
                scheme
            }
        }
        darkTheme && amoledDark -> AmoledDarkColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge (enableEdgeToEdge in MainActivity) keeps the status bar transparent;
            // content draws behind it. Only the icon appearance needs manual control here because
            // the app supports a user-chosen theme that may differ from the system setting.
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAmoledMode provides amoledDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = if (amoledDark) AmoledShapes else AppShapes
        ) {
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                content()
            }
        }
    }
}
