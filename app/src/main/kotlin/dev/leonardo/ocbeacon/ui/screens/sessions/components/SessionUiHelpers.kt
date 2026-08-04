package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode

/**
 * 检查当前主题是否为 AMOLED 深色模式。
 */
@Composable
internal fun isAmoledTheme(): Boolean = LocalAmoledMode.current
