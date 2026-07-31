package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.Part

/**
 * Resolver for tool-specific card composables.
 * Implementations map a tool name (lowercase) to its dedicated Compose card.
 */
interface ToolCardResolver {
    /**
     * Resolve a composable for the given tool part.
     * @return the composable lambda, or null if this resolver does not handle the tool.
     */
    fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        turnAgentName: String?,
        onOpenFile: ((filePath: String) -> Unit)? = null,
    ): (@Composable () -> Unit)?
}
