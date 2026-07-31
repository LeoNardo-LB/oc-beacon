package dev.leonardo.octether.ui.screens.chat.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.leonardo.octether.domain.model.AgentInfo
import dev.leonardo.octether.ui.theme.AgentAccent
import dev.leonardo.octether.ui.theme.AgentError
import dev.leonardo.octether.ui.theme.AgentInfo
import dev.leonardo.octether.ui.theme.AgentPrimary
import dev.leonardo.octether.ui.theme.AgentSecondary
import dev.leonardo.octether.ui.theme.AgentSuccess
import dev.leonardo.octether.ui.theme.AgentWarning
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.theme.LocalAmoledMode

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
 * Agent color cycle matching the TUI's opencode theme (local.tsx).
 * Colors are defined in [dev.leonardo.octether.ui.theme] (Color.kt) so they
 * stay stable across sessions and themes — each agent must remain
 * identifiable by its color.
 */
internal val agentColorCycle = listOf(
    AgentSecondary, // build (blue)
    AgentAccent,    // plan (purple)
    AgentSuccess,   // green
    AgentWarning,   // orange
    AgentPrimary,   // peach
    AgentError,     // red
    AgentInfo       // cyan
)

internal fun agentColor(agentName: String, agents: List<AgentInfo> = emptyList()): Color {
    val index = agents.indexOfFirst { it.name == agentName }
    return if (index >= 0) {
        agentColorCycle[index % agentColorCycle.size]
    } else {
        agentColorCycle[0]
    }
}
