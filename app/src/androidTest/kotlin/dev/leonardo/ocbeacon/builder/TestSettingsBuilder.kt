package dev.leonardo.ocbeacon.builder

import dev.leonardo.ocbeacon.domain.model.AppSettings

/**
 * 为测试创建 AppSettings。
 * chatDensity："normal"（舒适）或 "compact"（紧凑）。
 */
fun testSettings(
    chatDensity: String = "normal",
    autoExpandTools: Boolean = true,
    expandReasoning: Boolean = false,
    showTurnDividers: Boolean = true
): AppSettings = AppSettings(
    chatDensity = chatDensity,
    autoExpandTools = autoExpandTools,
    expandReasoning = expandReasoning,
    showTurnDividers = showTurnDividers
)
