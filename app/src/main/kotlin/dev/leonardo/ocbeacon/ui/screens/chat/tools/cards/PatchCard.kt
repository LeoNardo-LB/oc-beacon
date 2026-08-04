package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DiffChangesInline
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolGroupList
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolGroupListItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalSessionDiffs
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.util.PathUtils

/**
 * 在 agent turn 结束时显示已修改文件的摘要。
 * 通过 [ToolCardScaffold] 使用标准单行标题；展开列表显示
 * 每个文件的 +N/-N 变更数（来源：[LocalSessionDiffs]）。
 * 每个文件均可点击 → FileViewer。
 */
@Composable
internal fun PatchCard(
    patch: Part.Patch,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFile: ((filePath: String) -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val accentColor = MaterialTheme.colorScheme.primary
    val title = if (patch.files.size == 1)
        stringResource(R.string.chat_files_changed, patch.files.size)
    else
        stringResource(R.string.chat_files_changed_plural, patch.files.size)
    val sessionDiffs = LocalSessionDiffs.current[patch.sessionId]

    ToolCardScaffold(
        icon = Icons.Default.Code,
        iconTint = accentColor,
        title = title,
        copyText = title,
        isExpanded = isExpanded,
        isRunning = false,
        hasContent = patch.files.isNotEmpty(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
    ) {
        ToolGroupList(
            items = patch.files.map { filePath ->
                val fileDiff = sessionDiffs?.find { it.file == filePath }
                ToolGroupListItem(
                    icon = Icons.Default.Description,
                    label = PathUtils.fileName(filePath),
                    subtitle = PathUtils.parentDir(filePath).ifEmpty { null },
                    trailing = {
                        DiffChangesInline(
                            additions = fileDiff?.additions ?: 0,
                            deletions = fileDiff?.deletions ?: 0
                        )
                    },
                )
            },
            onItemClick = if (onOpenFile != null) { idx -> onOpenFile(patch.files[idx]) } else null,
        )
    }
}
