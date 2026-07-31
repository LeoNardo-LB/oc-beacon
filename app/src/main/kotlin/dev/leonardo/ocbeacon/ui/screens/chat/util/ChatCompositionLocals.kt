package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCardResolver

// ============ Chat Settings via CompositionLocal ============

/** Whether tool cards are collapsed by default. */
val LocalCollapseTools = compositionLocalOf { false }

/** Whether reasoning blocks are expanded by default. */
val LocalExpandReasoning = compositionLocalOf { false }

/** Whether to show dividers between messages in the same turn. */
val LocalShowTurnDividers = compositionLocalOf { true }

/** Whether haptic feedback is enabled. */
val LocalHapticFeedbackEnabled = compositionLocalOf { true }

/**
 * Whether the current session is actively streaming (FSM activity = Streaming).
 * Authoritative gate for the reasoning timer; combined with per-part `time.end == null`
 * so only the current reasoning part shows the timer (approach B).
 */
val LocalSessionStreaming = staticCompositionLocalOf { false }

/** Image save request callback available to image preview composables. */
val LocalImageSaveRequest = compositionLocalOf<(ByteArray, String, String?) -> Unit> { { _, _, _ -> } }

/** Persisted expand/collapse state for tool cards, keyed by Part.Tool.id or Part.Patch.id. */
val LocalToolExpandedStates = compositionLocalOf<Map<String, Boolean>> { emptyMap() }

/** Callback to toggle a tool card's expanded state by its part id. */
val LocalOnToggleToolExpanded = compositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

/** Resolver for tool-specific card composables. */
val LocalToolCardResolver = compositionLocalOf<ToolCardResolver> {
    DefaultToolCardResolver()
}

/** File diffs keyed by sessionId. Backs [dev.leonardo.ocbeacon.domain.model.Part.Patch] line counts. */
val LocalSessionDiffs = compositionLocalOf<Map<String, List<FileDiff>>> { emptyMap() }
