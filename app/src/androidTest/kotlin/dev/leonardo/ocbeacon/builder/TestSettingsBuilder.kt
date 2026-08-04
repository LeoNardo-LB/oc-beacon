package dev.leonardo.ocbeacon.builder

import dev.leonardo.ocbeacon.domain.model.AppSettings

/**
 * 为测试创建 AppSettings。
 * chatDensity："normal"（舒适）或 "compact"（紧凑）。
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
