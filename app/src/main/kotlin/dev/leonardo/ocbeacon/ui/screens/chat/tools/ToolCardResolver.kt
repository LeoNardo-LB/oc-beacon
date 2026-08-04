package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.runtime.Composable
import dev.leonardo.ocbeacon.domain.model.Part

/**
 * 工具专属卡片 composable 的解析器。
 * 实现将工具名（小写）映射到其专属的 Compose 卡片。
 */
interface ToolCardResolver {
    /**
     * 为给定工具 part 解析 composable。
     * @return composable lambda；若该解析器不处理此工具则返回 null。
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
