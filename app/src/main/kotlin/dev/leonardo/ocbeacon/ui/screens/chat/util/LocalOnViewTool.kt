package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.staticCompositionLocalOf
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ViewToolRequest

/**
 * CompositionLocal for the "view tool snapshot" callback (spec §5.1-5.4 entry1).
 * Provided by ChatScreen, consumed by PartContent to intercept tool card ↗ taps.
 */
val LocalOnViewTool = staticCompositionLocalOf<((ViewToolRequest) -> Unit)?> { null }
