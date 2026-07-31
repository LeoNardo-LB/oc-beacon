package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.halfScreenHeight
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Task (sub-agent) tool card — shows description + child info.
 * Like WebUI: trigger = "Agent (task)" + description, content = child tool list.
 */
@Composable
internal fun TaskToolCard(
    tool: Part.Tool,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {}
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val description = input["description"]?.jsonPrimitive?.contentOrNull
    val inputAgentType = input["subagent_type"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() }
    val metadataAgentName = when (val s = tool.state) {
        is ToolState.Completed -> s.metadata?.get("agent")?.jsonPrimitive?.contentOrNull
        is ToolState.Running -> s.metadata?.get("agent")?.jsonPrimitive?.contentOrNull
        else -> null
    }
    val agentType = inputAgentType
        ?: metadataAgentName?.replaceFirstChar { it.uppercase() }
        ?: turnAgentName?.replaceFirstChar { it.uppercase() }
    val output = extractToolOutput(tool)

    val serverTitle = when (val s = tool.state) {
        is ToolState.Running -> s.title
        is ToolState.Completed -> s.title
        else -> null
    }

    val isRunning = tool.state is ToolState.Running
    val hasOutput = output.isNotBlank()
    val longPressCopyText = description
        ?: agentType?.let { "$it Agent" }
        ?: serverTitle?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.tool_sub_agent)
    val subSessionId = when (val state = tool.state) {
            is ToolState.Completed -> state.metadata?.get("sessionId")
            is ToolState.Running -> state.metadata?.get("sessionId")
            else -> null
        }?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            ?.takeIf { it?.isNotBlank() == true }

    // Determine click behavior: navigate to subSession if available, else toggle expand
    val clickAction: (() -> Unit)? = if (subSessionId != null && onViewSubSession != null) {
        { onViewSubSession(subSessionId) }
    } else null

    // Determine right side: show navigation arrow if subSession, else copy + expand
    val showNavArrow = !isRunning && subSessionId != null && onViewSubSession != null

    ToolCardScaffold(
        icon = Icons.Default.AccountTree,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "", // not used since titleContent is provided
        copyText = if (showNavArrow) "" else longPressCopyText,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = if (showNavArrow) true else hasOutput,
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand,
        showExpandIcon = !showNavArrow,
        onClick = clickAction,
        rightSideExtras = if (showNavArrow) {
            { Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.a11y_icon_navigate_forward),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        titleContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = stringResource(R.string.tool_sub_agent),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agentType?.let { "$it Agent" }
                            ?: serverTitle?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.tool_sub_agent),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = CodeTypography,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.chat_task_output_summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectionContainer {
                    MarkdownContent(
                        markdown = output.take(2000),
                        textColor = if (isAmoled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED) else MaterialTheme.colorScheme.onSecondaryContainer,
                        isUser = false
                    )
                }
            }
        }
    }
}
