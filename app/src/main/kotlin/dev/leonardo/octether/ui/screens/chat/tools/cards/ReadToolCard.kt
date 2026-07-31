package dev.leonardo.octether.ui.screens.chat.tools.cards

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import dev.leonardo.octether.ui.screens.chat.tools.extractToolInput
import dev.leonardo.octether.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.octether.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.octether.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.octether.ui.screens.chat.tools.extractFileName
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.AlphaTokens

/**
 * Read tool card — shows "读取" title, file name subtitle, expandable for details.
 */
@Composable
internal fun ReadToolCard(
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
    val offset = input["offset"]?.jsonPrimitive?.contentOrNull
    val limit = input["limit"]?.jsonPrimitive?.contentOrNull

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error

    // Build args string like WebUI: [offset=N, limit=N]
    val args = buildList {
        offset?.let { add("offset=$it") }
        limit?.let { add("limit=$it") }
    }.takeIf { it.isNotEmpty() }?.joinToString(", ", "[", "]")

    val title = if (shortPath.isNotBlank()) {
        "${stringResource(R.string.tool_read)} · $shortPath"
    } else {
        stringResource(R.string.tool_read)
    }
    val copyText = if (filePath.isNotBlank()) "Read: $filePath" else "Read"
    // ReadToolCard shows copy button even without output (always has info to show)
    val hasContent = true

    ToolCardScaffold(
        icon = if (isError) Icons.Default.Error else Icons.Default.Description,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = halfScreenHeight)
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (filePath.isNotBlank()) {
                        Surface(
                            shape = ShapeTokens.extraSmall,
                            color = toolOutputContainerColor(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = shortPath,
                                style = CodeTypography.copy(
                                    fontSize = 11.sp,
                                    color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)
                                ),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .codeHorizontalScroll()
                            )
                        }
                    }
                    if (args != null) {
                        Surface(
                            shape = ShapeTokens.extraSmall,
                            color = toolOutputContainerColor(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = args,
                                style = CodeTypography.copy(
                                    fontSize = 11.sp,
                                    color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)
                                ),
                                modifier = Modifier
                                    .padding(4.dp)
                                    .codeHorizontalScroll()
                            )
                        }
                    }
                }
            }
        }
    }
}
