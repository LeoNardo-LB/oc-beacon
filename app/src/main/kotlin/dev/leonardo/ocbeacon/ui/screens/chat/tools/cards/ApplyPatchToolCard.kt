package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.tools.SimpleDiffView
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractFileName
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * ApplyPatch 工具卡片 —— 显示文件路径 + diff 预览。
 * 使用现有的 [DiffView] 组件进行渲染。
 */
@Composable
internal fun ApplyPatchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFile: ((filePath: String) -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // 从元数据或输入中提取 diff 内容
    val diffContent = remember(tool.state) {
        val completed = tool.state as? ToolState.Completed
        val meta = completed?.metadata
        meta?.get("patch")?.jsonPrimitive?.contentOrNull
            ?: input["patch"]?.jsonPrimitive?.contentOrNull
            ?: output
    }

    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""

    val title = if (filePath.isNotBlank()) {
        "${stringResource(R.string.tool_apply_patch)} · ${extractFileName(filePath)}"
    } else {
        stringResource(R.string.tool_apply_patch)
    }

    ToolCardScaffold(
        icon = Icons.Default.Build,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = diffContent,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = diffContent.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        trailingExtras = {
            if (filePath.isNotBlank() && onOpenFile != null) {
                OpenFileIconButton(onClick = { onOpenFile.invoke(filePath) })
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (diffContent.isNotBlank()) {
                SimpleDiffView(
                    before = "",
                    after = diffContent
                )
            }
        }
    }
}
