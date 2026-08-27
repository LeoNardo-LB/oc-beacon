package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.leonardo.ocbeacon.ui.screens.chat.components.rememberScrollIsland
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Search tool card (glob/grep) — shows pattern + expandable output.
 * Like WebUI: trigger = "Glob"/"Grep" + directory + [pattern=...], content = markdown output.
 */
@Composable
internal fun SearchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull
    val dirPath = input["path"]?.jsonPrimitive?.contentOrNull
    val output = extractToolOutput(tool)

    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()

    val baseTitle = when (tool.tool) {
        "glob" -> stringResource(R.string.tool_find_files)
        "grep" -> stringResource(R.string.tool_search_code)
        else -> tool.tool.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
    val patternShort = pattern?.let {
        if (it.length > 40) it.take(37) + "..." else it
    }
    val title = if (patternShort != null) "$baseTitle · $patternShort" else baseTitle

    ToolCardScaffold(
        icon = Icons.Default.Search,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = title,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = hasOutput,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            // 入参信息块
        if (pattern != null || !dirPath.isNullOrBlank()) {
            Surface(
                shape = ShapeTokens.extraSmall,
                color = toolOutputContainerColor(),
                border = if (isAmoled) AmoledDefaultBorder else null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    if (pattern != null) {
                        Text(
                            text = stringResource(R.string.tool_search_pattern, pattern),
                            style = CodeTypography.copy(
                                fontSize = 11.sp,
                                color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)
                            )
                        )
                    }
                    if (!dirPath.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.tool_search_path, dirPath),
                            style = CodeTypography.copy(
                                fontSize = 11.sp,
                                color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)
                            )
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        val halfScreenHeight = halfScreenHeight()
        val scrollState = rememberScrollState()
        Surface(
            shape = ShapeTokens.extraSmall,
            color = toolOutputContainerColor(),
            border = if (isAmoled) AmoledDefaultBorder else null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .heightIn(max = halfScreenHeight)
                .nestedScroll(rememberScrollIsland(scrollState))
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                MarkdownContent(
                    markdown = output,
                    textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer,
                    isUser = false
                )
            }
        }
        } // close Column
    }
}
