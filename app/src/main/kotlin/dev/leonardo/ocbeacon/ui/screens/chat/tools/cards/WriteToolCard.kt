package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractFileName
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Write tool card — shows file path + code content.
 * Like WebUI: trigger = "Write" + filename, content = code view.
 */
@Composable
internal fun WriteToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFile: ((filePath: String) -> Unit)? = null
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val filePath = input["filePath"]?.jsonPrimitive?.contentOrNull
        ?: input["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val shortPath = extractFileName(filePath)
    val content = input["content"]?.jsonPrimitive?.contentOrNull ?: ""

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val hasContent = content.isNotBlank()
    val title = if (shortPath.isNotBlank()) {
        "${stringResource(R.string.chat_write_label)} · $shortPath"
    } else {
        stringResource(R.string.chat_write_label)
    }
    val copyText = if (filePath.isNotBlank()) "Write: $filePath" else "Write"

    ToolCardScaffold(
        icon = if (isError) Icons.Default.Error else Icons.Default.EditNote,
        iconTint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        title = title,
        copyText = copyText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = hasContent,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        trailingExtras = {
            if (filePath.isNotBlank() && onOpenFile != null) {
                OpenFileIconButton(onClick = { onOpenFile.invoke(filePath) })
            }
        }
    ) {
        val halfScreenHeight = halfScreenHeight()
        val scrollState = rememberScrollState()
        Surface(
            shape = ShapeTokens.extraSmall,
            color = toolOutputContainerColor(),
            border = if (isAmoled) AmoledDefaultBorder else null,            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = content.take(5000),
                    style = CodeTypography.copy(color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer),
                    modifier = Modifier
                        .padding(4.dp)
                        .codeHorizontalScroll()
                )
            }
        }
    }
}
