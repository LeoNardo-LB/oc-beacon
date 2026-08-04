package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.ui.theme.AgentAccent
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.AgentInfo
import dev.leonardo.ocbeacon.ui.theme.AgentPrimary
import dev.leonardo.ocbeacon.ui.theme.AgentSecondary
import dev.leonardo.ocbeacon.ui.theme.AgentSuccess
import dev.leonardo.ocbeacon.ui.theme.AgentWarning
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.LocalAmoledMode

@Composable
internal fun isAmoledTheme(): Boolean = LocalAmoledMode.current

@Composable
internal fun toolOutputContainerColor(): Color {
    return if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = AlphaTokens.AMOLED)
    }
}

/**
 * Agent 颜色循环，匹配 TUI 的 opencode 主题（local.tsx）。
 * 颜色定义在 [dev.leonardo.ocbeacon.ui.theme]（Color.kt）中，使它们
 * 跨会话和主题保持稳定 —— 每个 agent 必须保持
 * 可通过其颜色识别。
 */
internal val agentColorCycle = listOf(
    AgentSecondary, // build（蓝色）
    AgentAccent,    // plan（紫色）
    AgentSuccess,   // 绿色
    AgentWarning,   // 橙色
    AgentPrimary,   // 桃色
    AgentError,     // 红色
    AgentInfo       // 青色
)

internal fun agentColor(agentName: String, agents: List<AgentInfo> = emptyList()): Color {
    val index = agents.indexOfFirst { it.name == agentName }
    return if (index >= 0) {
        agentColorCycle[index % agentColorCycle.size]
    } else {
        agentColorCycle[0]
    }
}
