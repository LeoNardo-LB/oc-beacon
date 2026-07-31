package dev.leonardo.octether.ui.screens.chat.tools

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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import dev.leonardo.octether.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.octether.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.octether.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.octether.ui.theme.CodeTypography
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import dev.leonardo.octether.ui.theme.ShapeTokens
import dev.leonardo.octether.ui.theme.AlphaTokens

@Composable
internal fun ToolCallCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val stateColor = when (tool.state) {
        is ToolState.Pending -> MaterialTheme.colorScheme.outline
        is ToolState.Running -> MaterialTheme.colorScheme.tertiary
        is ToolState.Completed -> MaterialTheme.colorScheme.primary
        is ToolState.Error -> MaterialTheme.colorScheme.error
    }

    // Extract input args for context-specific display
    val input = when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }

    // Resolve display info based on tool type
    val toolDisplay = resolveToolDisplay(tool.tool.lowercase(), tool.state, input)

    val longPressCopyText = if (toolDisplay.subtitle != null && toolDisplay.subtitle != toolDisplay.title) {
        "${toolDisplay.title} · ${toolDisplay.subtitle}"
    } else {
        toolDisplay.title
    }

    val isRunning = tool.state is ToolState.Running
    val isError = tool.state is ToolState.Error
    val isTask = tool.tool == "task"
    val hasExpandedContent = input.isNotEmpty() || when (val s = tool.state) {
        is ToolState.Completed -> s.output.isNotBlank()
        is ToolState.Error -> s.error.isNotBlank()
        else -> false
    }

    val resolvedIcon = when (tool.state) {
        is ToolState.Running -> Icons.Default.Sync
        is ToolState.Completed -> toolDisplay.icon
        is ToolState.Error -> Icons.Default.Error
        else -> Icons.Default.PlayArrow
    }
    val resolvedIconTint = if (isError) stateColor else toolDisplay.iconTint ?: stateColor

    ToolCardScaffold(
        icon = resolvedIcon,
        iconTint = resolvedIconTint,
        title = "", // not used when titleContent is provided for task
        copyText = longPressCopyText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = true, // always show copy + expand for generic renderer
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        titleContent = if (isTask) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = resolvedIcon,
                        contentDescription = toolDisplay.title,
                        modifier = Modifier.size(16.dp),
                        tint = resolvedIconTint
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = toolDisplay.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (toolDisplay.subtitle != null) {
                            Text(
                                text = toolDisplay.subtitle,
                                style = CodeTypography.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = resolvedIcon,
                        contentDescription = toolDisplay.title,
                        modifier = Modifier.size(16.dp),
                        tint = resolvedIconTint
                    )
                    val displayText = if (toolDisplay.subtitle != null && toolDisplay.subtitle != toolDisplay.title) {
                        "${toolDisplay.title} · ${toolDisplay.subtitle}"
                    } else {
                        toolDisplay.title
                    }
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) {
        val halfScreenHeight = halfScreenHeight()
        val toolCardScrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = halfScreenHeight)
                .verticalScroll(toolCardScrollState)
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (input.isNotEmpty()) {
                        val inputText = input.entries
                            .filter { (_, v) -> v.toString().length <= 500 }
                            .joinToString("\n") { (k, v) ->
                                val value = (v as? JsonPrimitive)?.contentOrNull ?: v.toString().take(200)
                                "$k: $value"
                            }
                        if (inputText.isNotBlank()) {
                            Surface(
                                shape = ShapeTokens.extraSmall,
                                color = toolOutputContainerColor(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = inputText.take(2000),
                                    style = CodeTypography.copy(fontSize = 11.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = AlphaTokens.HIGH)),
                                    modifier = Modifier.padding(4.dp).codeHorizontalScroll()
                                )
                            }
                        }
                    }
                    val output = when (val s = tool.state) {
                        is ToolState.Completed -> s.output
                        is ToolState.Running -> s.output
                        is ToolState.Error -> s.error
                        else -> ""
                    }
                    if (output.isNotBlank()) {
                        Surface(
                            shape = ShapeTokens.extraSmall,
                            color = toolOutputContainerColor(),
                            border = if (isAmoled) BorderStroke(1.dp, stateColor.copy(alpha = AlphaTokens.MEDIUM)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = output.take(3000),
                                style = CodeTypography.copy(fontSize = 11.sp, color = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer),
                                modifier = Modifier.padding(4.dp).codeHorizontalScroll()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extract common tool input values.
 */
internal fun extractToolInput(tool: Part.Tool): Map<String, kotlinx.serialization.json.JsonElement> {
    return when (val state = tool.state) {
        is ToolState.Pending -> state.input
        is ToolState.Running -> state.input
        is ToolState.Completed -> state.input
        is ToolState.Error -> state.input
    }
}

internal fun extractToolOutput(tool: Part.Tool): String {
    return when (val s = tool.state) {
        is ToolState.Completed -> s.output
        is ToolState.Running -> s.output
        is ToolState.Error -> s.error
        else -> ""
    }
}
