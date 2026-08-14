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
 * 深色配色方案 — 仅覆盖品牌差异化 token。
 * 其他 token 回退到 Material3 [darkColorScheme] 默认值，
 * 这些默认值经过设计与测试，对比度和高度语义正确。
 */
private val DarkColorScheme = darkColorScheme(
    // 品牌色：Indigo 主色 + Cyan 第三色（OpenCode 视觉标识）
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
 * AMOLED 覆盖的 8 个 surface 系色值（D2-L18：动态取色分支与静态 AMOLED 深色共用，消除复制粘贴）。
 */
private data class AmoledSurfaces(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainer: Color,
    val surfaceContainerLow: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
)

private val AmoledSurfaceOverrides = AmoledSurfaces(
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
 * AMOLED 深色配色方案 — 纯黑表面以节省 OLED 电量。
 * 主表面使用真黑色（#000000），容器使用极深色调，
 * 确保卡片/底部弹层仍可与背景视觉区分。
 */
private val AmoledDarkColorScheme = DarkColorScheme.copy(
    background = AmoledSurfaceOverrides.background,
    surface = AmoledSurfaceOverrides.surface,
    surfaceVariant = AmoledSurfaceOverrides.surfaceVariant,
    surfaceContainer = AmoledSurfaceOverrides.surfaceContainer,
    surfaceContainerLow = AmoledSurfaceOverrides.surfaceContainerLow,
    surfaceContainerLowest = AmoledSurfaceOverrides.surfaceContainerLowest,
    surfaceContainerHigh = AmoledSurfaceOverrides.surfaceContainerHigh,
    surfaceContainerHighest = AmoledSurfaceOverrides.surfaceContainerHighest
)

/**
 * OpenCode Material 3 主题
 *
 * 支持：
 * - 基于系统设置的浅色/深色主题
 * - Android 12+ 动态取色（Material You）
 * - AMOLED 深色模式（纯黑表面）
 * - 边到边显示
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
                // 仅覆盖 surface/container token 以实现 AMOLED 纯黑效果。
                // 保留动态取色的 onSurface/onSurfaceVariant，
                // 使壁纸生成的调色板保持一致。
                // D2-L18：色值与 AmoledDarkColorScheme 共用同一数据源。
                scheme.copy(
                    background = AmoledSurfaceOverrides.background,
                    surface = AmoledSurfaceOverrides.surface,
                    surfaceVariant = AmoledSurfaceOverrides.surfaceVariant,
                    surfaceContainer = AmoledSurfaceOverrides.surfaceContainer,
                    surfaceContainerLow = AmoledSurfaceOverrides.surfaceContainerLow,
                    surfaceContainerLowest = AmoledSurfaceOverrides.surfaceContainerLowest,
                    surfaceContainerHigh = AmoledSurfaceOverrides.surfaceContainerHigh,
                    surfaceContainerHighest = AmoledSurfaceOverrides.surfaceContainerHighest
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
            // 边到边（MainActivity 中的 enableEdgeToEdge）保持状态栏透明；
            // 内容绘制在状态栏下方。此处仅需手动控制图标外观，
            // 因为应用支持用户自选主题，可能与系统设置不同。
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
