package dev.leonardo.octether.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.leonardo.octether.R
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import dev.leonardo.octether.ui.theme.AlphaTokens
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.leonardo.octether.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.octether.ui.screens.chat.tools.ToolCallCard
import dev.leonardo.octether.ui.screens.chat.tools.ViewToolRequest
import dev.leonardo.octether.ui.screens.chat.tools.cards.PatchCard
import dev.leonardo.octether.ui.screens.chat.tools.cards.TodoListCard
import dev.leonardo.octether.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.octether.ui.screens.chat.util.LocalCollapseTools
import dev.leonardo.octether.ui.screens.chat.util.LocalExpandReasoning
import dev.leonardo.octether.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.octether.ui.screens.chat.util.LocalOnViewTool
import dev.leonardo.octether.ui.screens.chat.util.LocalSessionStreaming
import dev.leonardo.octether.ui.screens.chat.util.LocalToolCardResolver
import dev.leonardo.octether.ui.screens.chat.util.LocalToolExpandedStates
import dev.leonardo.octether.ui.screens.chat.util.QuestionParser
import dev.leonardo.octether.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.octether.ui.screens.viewer.FileViewerSource
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun PartContent(
    part: Part,
    textColor: Color,
    isUser: Boolean = false,
    onViewSubSession: ((String) -> Unit)? = null,
    turnAgentName: String? = null,
    onOpenFile: ((filePath: String) -> Unit)? = null,
    onViewTool: ((ViewToolRequest) -> Unit)? = null
) {
    when (part) {
        is Part.Text -> {
            // Hide synthetic/ignored text parts (internal system content)
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                // opencode may send question+answer as a text part (not structured Part.Question)
                // Detect this format and render as collapsible card
                if (part.text.contains("questions:") && part.text.contains("User has answered")) {
                    CollapsibleQuestionPart(question = part.text)
                } else {
                    SelectionContainer {
                        MarkdownContent(
                            markdown = part.text,
                            textColor = textColor,
                            isUser = isUser,
                            immediate = !isUser
                        )
                    }
                }
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                // Reasoning streams during the Waiting phase (before TextStarted).
                // Must check part.time?.end directly, NOT LocalSessionStreaming —
                // the FSM activity is "Waiting" during reasoning, not "Streaming".
                val isStreaming = part.time?.end == null
                val startTimeMs = part.time?.start
                val reasoningDuration = part.time?.let { t ->
                    t.end?.let { end -> end - t.start }
                }
                val toolExpandedStates = LocalToolExpandedStates.current
                val onToggleToolExpanded = LocalOnToggleToolExpanded.current
                val expandReasoningDefault = LocalExpandReasoning.current
                ReasoningBlock(
                    text = part.text,
                    isExpanded = toolExpandedStates[part.id] ?: expandReasoningDefault,
                    onToggleExpand = { onToggleToolExpanded(part.id, expandReasoningDefault) },
                    durationMs = reasoningDuration,
                    isStreaming = isStreaming,
                    startTimeMs = startTimeMs
                )
            }
        }
        is Part.Tool -> {
            // todoread parts are filtered out entirely (WebUI convention)
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            if (part.tool == "todoread") {
                // skip
            } else if (part.tool == "todowrite") {
                TodoListCard(
                    tool = part,
                    isExpanded = toolExpandedStates[part.id] ?: true,
                    onToggleExpand = { onToggleToolExpanded(part.id, true) }
                )
            } else {
                // Intercept question-summary tools — keep ToolCardScaffold appearance,
                // but expanded content shows ALL options with user's selection marked.
                val completedState = part.state as? ToolState.Completed
                val toolInput = completedState?.input ?: emptyMap()
                val toolOutput = completedState?.output ?: ""
                android.util.Log.e("PartContent", "TOOL ELSE: tool=${part.tool} state=${part.state::class.simpleName} outputLen=${toolOutput.length} outputHead=${toolOutput.take(200)}")
                val isQuestionTool = toolOutput.contains("questions:")
                    || toolInput.any { it.key.contains("question", ignoreCase = true) }
                android.util.Log.e("PartContent", "isQuestionTool=$isQuestionTool inputKeys=${toolInput.keys}")
                if (isQuestionTool) {
                    // Debug: log full tool data to find where answers live
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "=== tool data ===")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "input keys: ${toolInput.keys}")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "input: $toolInput")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "output: $toolOutput")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "metadata: ${completedState?.metadata}")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "title: ${completedState?.title}")
                    dev.leonardo.octether.util.DebugLogger.log("QuestionTool", "tool name: ${part.tool}")
                    val parsed = remember(part.id) {
                        QuestionParser.parseQuestionFromToolData(part.id, toolInput, toolOutput)
                    }
                    val autoExpand = LocalCollapseTools.current
                    ToolCardScaffold(
                        icon = Icons.Default.HelpOutline,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = completedState?.title ?: "Asked",
                        copyText = toolOutput,
                        isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                        isRunning = false,
                        hasContent = parsed.any { it.options.isNotEmpty() },
                        isAmoled = isAmoledTheme(),
                        onToggleExpand = { onToggleToolExpanded(part.id, autoExpand) },
                    ) {
                        QuestionExpandedOptions(parsed)
                    }
                } else {
                // Use the resolver registry
                val autoExpand = LocalCollapseTools.current
                val expanded = toolExpandedStates[part.id] ?: autoExpand
                val toggleExpand = { onToggleToolExpanded(part.id, autoExpand) }

                // Phase 2: intercept onOpenFile for Read/Write/Edit → TOOL_SNAPSHOT
                val viewTool = onViewTool ?: LocalOnViewTool.current
                val toolName = part.tool.lowercase()
                val isFileTool = toolName in setOf("read", "write", "edit", "multiedit")
                val isDiffTool = toolName in setOf("edit", "multiedit")
                val effectiveOnOpenFile: ((String) -> Unit)? = if (viewTool != null && isFileTool) {
                    { filePath ->
                        val source = if (isDiffTool) FileViewerSource.TOOL_SNAPSHOT_DIFF
                        else FileViewerSource.TOOL_SNAPSHOT
                        viewTool(ViewToolRequest(filePath, source, part))
                    }
                } else onOpenFile

                val resolved = LocalToolCardResolver.current.resolve(
                    tool = part,
                    isExpanded = expanded,
                    onToggleExpand = toggleExpand,
                    onViewSubSession = onViewSubSession,
                    turnAgentName = turnAgentName,
                    onOpenFile = effectiveOnOpenFile
                )

                if (resolved != null) {
                    resolved()
                } else {
                    // Fallback to generic ToolCallCard
                    ToolCallCard(
                        tool = part,
                        isExpanded = expanded,
                        onToggleExpand = toggleExpand
                    )
                }
                } // close question-summary else
            }
        }
        is Part.StepStart -> {
            // Visual separator between steps (hidden - WebUI doesn't show these)
        }
        is Part.StepFinish -> {
            // Token/cost info is aggregated at the bottom of the assistant message
        }
        is Part.Patch -> {
            val autoExpand = LocalCollapseTools.current
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            PatchCard(
                patch = part,
                isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                onToggleExpand = { onToggleToolExpanded(part.id, autoExpand) },
                onOpenFile = onOpenFile
            )
        }
        is Part.File -> {
            FileCard(file = part)
        }
        is Part.Permission -> {
            Text(
                text = stringResource(R.string.chat_permission_label, part.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        is Part.Question -> {
            CollapsibleQuestionPart(question = part.question)
        }
        is Part.Abort -> {
            Text(
                text = stringResource(R.string.chat_aborted, part.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        is Part.Retry -> {
            Text(
                text = stringResource(R.string.chat_retry, part.attempt, part.errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Ignore less relevant parts
        is Part.Snapshot, is Part.Subtask, is Part.Compaction,
        is Part.SessionTurn, is Part.Unknown -> { /* skip */ }
        is Part.Agent -> {
            val displayName = part.name.ifBlank { "Agent" }
            val displaySource = part.source?.jsonPrimitive?.contentOrNull ?: ""
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = stringResource(R.string.a11y_icon_select_provider),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                if (displaySource.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displaySource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                    )
                }
            }
        }
    }
}


