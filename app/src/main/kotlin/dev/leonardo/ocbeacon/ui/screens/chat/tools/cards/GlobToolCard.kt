package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Glob tool card — shows glob pattern + match count + expandable file list.
 */
@Composable
internal fun GlobToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val pattern = input["pattern"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // Parse output lines as file paths
    val files = remember(output) {
        if (output.isBlank()) emptyList()
        else output.lines().filter { it.isNotBlank() }
    }

    val title = if (pattern.isNotBlank()) {
        "${stringResource(R.string.tool_find_files)} · $pattern"
    } else {
        stringResource(R.string.tool_find_files)
    }

    ToolCardScaffold(
        icon = Icons.Default.FolderOpen,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = files.joinToString("\n"),
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = files.isNotEmpty(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            // Match count
            if (files.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_glob_match_count, files.size),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                    ),
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            // Expandable file list
            AnimatedVisibility(visible = isExpanded) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        items(files, key = { it }) { filePath ->
                            SelectionContainer {
                                Text(
                                    text = filePath,
                                    style = CodeTypography.copy(
                                        fontSize = 11.sp,
                                        color = if (isAmoled) {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
