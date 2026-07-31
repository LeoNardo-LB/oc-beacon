package dev.leonardo.ocbeacon.builder

import dev.leonardo.ocbeacon.domain.model.AppSettings

/**
 * Create AppSettings for tests.
 * chatDensity: "normal" (comfortable) or "compact".
 */
fun testSettings(
    chatDensity: String = "normal",
    collapseTools: Boolean = true,
    expandReasoning: Boolean = false,
    showTurnDividers: Boolean = true
): AppSettings = AppSettings(
    chatDensity = chatDensity,
    collapseTools = collapseTools,
    expandReasoning = expandReasoning,
    showTurnDividers = showTurnDividers
)
