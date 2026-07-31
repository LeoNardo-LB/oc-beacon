package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractFileName
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.components.ErrorPayloadContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DiffChangesInline
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DiffLineType
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DiffView
import dev.leonardo.ocbeacon.ui.screens.chat.tools.computeSimpleDiff
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Edit tool card — shows file path + diff with red/green colored lines.
 * Like WebUI: trigger = "Edit" + filename + DiffChanges, content = diff view.
 */
@Composable
internal fun EditToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFile: ((filePath: String) -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = extractFileName(filePath)
    val dirPath = dev.leonardo.ocbeacon.util.PathUtils.parentDir(filePath)
    val oldString = input["oldString"]?.jsonPrimitive?.contentOrNull ?: ""
    val newString = input["newString"]?.jsonPrimitive?.contentOrNull ?: ""

    // Try to get filediff from metadata (full file before/after)
    val metadata = when (val s = tool.state) {
        is ToolState.Completed -> s.metadata
        is ToolState.Running -> s.metadata
        else -> null
    }
    val filediffBefore = metadata?.get("filediff")?.jsonObject?.get("before")?.jsonPrimitive?.contentOrNull
    val filediffAfter = metadata?.get("filediff")?.jsonObject?.get("after")?.jsonPrimitive?.contentOrNull

    val diffBefore = filediffBefore ?: oldString
    val diffAfter = filediffAfter ?: newString

    // Compute additions/deletions using proper diff
    val diffLines = remember(diffBefore, diffAfter) {
        val beforeLines = if (diffBefore.isBlank()) emptyList() else diffBefore.lines()
        val afterLines = if (diffAfter.isBlank()) emptyList() else diffAfter.lines()
        computeSimpleDiff(beforeLines, afterLines)
    }
    val additions = diffLines.count { it.type == DiffLineType.ADDED }
    val deletions = diffLines.count { it.type == DiffLineType.REMOVED }

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = oldString.isNotBlank() || newString.isNotBlank()
    val copyText = if (filePath.isNotBlank()) "Edit: $filePath" else "Edit"

    ToolCardScaffold(
        icon = if (isError) Icons.Default.Error else Icons.Default.Edit,
        iconTint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = if (shortPath.isNotBlank()) "${stringResource(R.string.chat_edit_label)} · $shortPath" else stringResource(R.string.chat_edit_label),
        copyText = copyText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = hasContent,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        rightSideExtras = {
            if (additions > 0 || deletions > 0) {
                DiffChangesInline(additions = additions, deletions = deletions)
            }
        },
        trailingExtras = {
            if (filePath.isNotBlank() && onOpenFile != null) {
                OpenFileIconButton(onClick = { onOpenFile.invoke(filePath) })
            }
        }
    ) {
        val halfScreenHeight = halfScreenHeight()
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            Column(modifier = Modifier.padding(top = 3.dp)) {
                if (isError) {
                    val errorText = (tool.state as ToolState.Error).error
                    Surface(
                        shape = ShapeTokens.extraSmall,
                        color = MaterialTheme.colorScheme.errorContainer,
                        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.MEDIUM)) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ErrorPayloadContent(
                            text = errorText,
                            textStyle = CodeTypography.copy(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                            textColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                } else {
                    SelectionContainer {
                        DiffView(before = diffBefore, after = diffAfter)
                    }
                }
            }
        }
    }
}
