package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode

/**
 * Check if the current theme is AMOLED dark mode.
 */
@Composable
internal fun isAmoledTheme(): Boolean = LocalAmoledMode.current
