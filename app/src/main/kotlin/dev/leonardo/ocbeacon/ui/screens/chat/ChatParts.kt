package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.Part

/**
 * Utility functions for filtering and categorizing chat parts.
 */

/**
 * Determines whether a Part should be rendered inside a chat bubble.
 * Non-renderable parts (StepStart, StepFinish, Snapshot, Subtask, Compaction,
 * Agent, SessionTurn, Unknown) are filtered out before display.
 */
internal fun isBubbleRenderablePart(part: Part): Boolean {
    return when (part) {
        is Part.Text,
        is Part.Reasoning,
        is Part.Patch,
        is Part.File,
        is Part.Permission,
        is Part.Question,
        is Part.Abort,
        is Part.Retry,
        is Part.Tool -> true
        else -> false
    }
}

/**
 * Filters a list of Parts to only include renderable ones,
 * preserving the original order from the server.
 *
 * This is the core logic that was fixed: previously parts were split into
 * contentParts and stepParts groups and rendered out of order. Now the
 * original interleaving (Text → Tool → Reasoning → Tool → Text) is preserved.
 */
internal fun filterRenderableParts(parts: List<Part>): List<Part> {
    return parts.filter(::isBubbleRenderablePart)
}
