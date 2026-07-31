package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ApplyPatchToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.BashToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.EditToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.GlobToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ReadToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.SearchToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.TaskToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.WebFetchToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.WebSearchToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.WriteToolCard
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default tool card resolver that maps tool names to their card composables.
 * Tool names are matched case-insensitively.
 */
@Singleton
class DefaultToolCardResolver @Inject constructor() : ToolCardResolver {

    private val cardMap: Map<String, (
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?,
        onOpenFile: ((filePath: String) -> Unit)?
    ) -> @Composable () -> Unit> = mapOf(
        "bash" to { tool, expanded, toggle, _, _, _ ->
            { BashToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "edit" to { tool, expanded, toggle, _, _, openFile ->
            { EditToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle, onOpenFile = openFile) }
        },
        "multiedit" to { tool, expanded, toggle, _, _, openFile ->
            { EditToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle, onOpenFile = openFile) }
        },
        "read" to { tool, expanded, toggle, _, _, openFile ->
            { ReadToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle, onOpenFile = openFile) }
        },
        "write" to { tool, expanded, toggle, _, _, openFile ->
            { WriteToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle, onOpenFile = openFile) }
        },
        "glob" to { tool, expanded, toggle, _, _, _ ->
            { GlobToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "grep" to { tool, expanded, toggle, _, _, _ ->
            { SearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "search" to { tool, expanded, toggle, _, _, _ ->
            { SearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "task" to { tool, expanded, toggle, viewSub, agentName, _ ->
            { TaskToolCard(tool = tool, onViewSubSession = viewSub, turnAgentName = agentName, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "webfetch" to { tool, expanded, toggle, _, _, _ ->
            { WebFetchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "web_fetch" to { tool, expanded, toggle, _, _, _ ->
            { WebFetchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "websearch" to { tool, expanded, toggle, _, _, _ ->
            { WebSearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "web_search" to { tool, expanded, toggle, _, _, _ ->
            { WebSearchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle) }
        },
        "apply_patch" to { tool, expanded, toggle, _, _, openFile ->
            { ApplyPatchToolCard(tool = tool, isExpanded = expanded, onToggleExpand = toggle, onOpenFile = openFile) }
        },
    )

    override fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?,
        onOpenFile: ((filePath: String) -> Unit)?
    ): (@Composable () -> Unit)? {
        val factory = cardMap[tool.tool.lowercase()] ?: return null
        return factory(tool, isExpanded, onToggleExpand, onViewSubSession, turnAgentName, onOpenFile)
    }
}
