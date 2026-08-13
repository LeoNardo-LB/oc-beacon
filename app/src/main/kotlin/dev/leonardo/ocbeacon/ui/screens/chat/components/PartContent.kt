package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCallCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ViewToolRequest
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.PatchCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ShellCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.TodoListCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalCollapseTools
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalExpandReasoning
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalOnViewTool
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalSessionStreaming
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalToolExpandedStates
import dev.leonardo.ocbeacon.ui.screens.chat.util.QuestionParser
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.viewer.FileViewerSource
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
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
    onViewTool: ((ViewToolRequest) -> Unit)? = null,
    // 2026-08-12 根治：跳转预渲染——透传外部 MarkdownState（见 MarkdownContent）
    markdownStateOverride: MarkdownState? = null,
    // 2026-08-13 根本方案：跳转目标预解析结果（见 MarkdownContent）
    preParsedState: State? = null,
) {
    when (part) {
        is Part.Text -> {
            // 隐藏合成/忽略的文本 parts（内部系统内容）
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                // opencode 可能以文本 part 发送问题和答案（而非结构化的 Part.Question）
                // 检测该格式并以可折叠卡片渲染
                if (part.text.contains("questions:") && part.text.contains("User has answered")) {
                    CollapsibleQuestionPart(question = part.text)
                } else {
                    SelectionContainer {
                        MarkdownContent(
                            markdown = part.text,
                            textColor = textColor,
                            isUser = isUser,
                            immediate = !isUser,
                            overrideState = markdownStateOverride,
                            preParsedState = preParsedState
                        )
                    }
                }
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                // Reasoning 在 Waiting 阶段流式输出（TextStarted 之前）。
                // 必须直接检查 part.time?.end，而不是 LocalSessionStreaming ——
                // reasoning 期间 FSM 活动状态是 "Waiting" 而非 "Streaming"。
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
        is Part.Tool -> {            // todoread parts 完全过滤掉（WebUI 约定）
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            if (part.tool == "todoread") {
                // 跳过
            } else if (part.tool == "todowrite") {
                TodoListCard(
                    tool = part,
                    isExpanded = toolExpandedStates[part.id] ?: true,
                    onToggleExpand = { onToggleToolExpanded(part.id, true) }
                )
            } else if (part.tool == "question") {
                // 2026-08-14 根因修复：question 工具是内部提问机制（与 todoread
                // 同模式按工具名分流），**不渲染通用工具卡片**——
                // 活跃（未完成）：不渲染任何内容（提问由嵌入的 QuestionCard 展示，
                //   避免出现"Question loading 卡片"与提问卡片重复）；
                // 完成（历史）：渲染答案视图（ToolCardScaffold "Asked" + 已选选项）。
                val completedState = part.state as? ToolState.Completed
                if (completedState != null) {
                    val toolInput = completedState.input ?: emptyMap()
                    val toolOutput = completedState.output ?: ""
                    val parsed = remember(part.id) {
                        QuestionParser.parseQuestionFromToolData(part.id, toolInput, toolOutput)
                    }
                    if (parsed.any { it.options.isNotEmpty() }) {
                        val autoExpand = LocalCollapseTools.current
                        ToolCardScaffold(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = completedState.title ?: "Asked",
                            copyText = toolOutput,
                            isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                            isRunning = false,
                            hasContent = true,
                            isAmoled = isAmoledTheme(),
                            onToggleExpand = { onToggleToolExpanded(part.id, autoExpand) },
                        ) {
                            QuestionExpandedOptions(parsed)
                        }
                    }
                    // 完成但无有效问题数据（异常）：不渲染
                }
                // 未完成（活跃）：不渲染——提问由 QuestionCard 展示
            } else {
                // 历史兼容：工具名非 "question" 但输出含问题数据的（旧服务器/旧数据）
                val completedState = part.state as? ToolState.Completed
                val toolInput = completedState?.input ?: emptyMap()
                val toolOutput = completedState?.output ?: ""
                val isQuestionTool = toolOutput.contains("questions:")
                    || toolInput.any { it.key.contains("question", ignoreCase = true) }
                if (isQuestionTool) {
                    val parsed = remember(part.id) {
                        QuestionParser.parseQuestionFromToolData(part.id, toolInput, toolOutput)
                    }
                    val autoExpand = LocalCollapseTools.current
                    ToolCardScaffold(
                        icon = Icons.AutoMirrored.Filled.HelpOutline,
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
                // 使用解析器注册表
                val autoExpand = LocalCollapseTools.current
                val expanded = toolExpandedStates[part.id] ?: autoExpand
                val toggleExpand = { onToggleToolExpanded(part.id, autoExpand) }

                // 阶段 2：为 Read/Write/Edit 拦截 onOpenFile → TOOL_SNAPSHOT
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
                    // 回退到通用 ToolCallCard
                    ToolCallCard(
                        tool = part,
                        isExpanded = expanded,
                        onToggleExpand = toggleExpand
                    )
                }
                } // 关闭 question-summary 的 else 分支
            }
        }
        is Part.Shell -> {
            // 后台 shell 命令卡片（V2）——2 行布局，与 TaskToolCard 对称
            val toolExpandedStates = LocalToolExpandedStates.current
            val onToggleToolExpanded = LocalOnToggleToolExpanded.current
            ShellCard(
                shell = part,
                isExpanded = toolExpandedStates[part.id] ?: false,
                onToggleExpand = { onToggleToolExpanded(part.id, true) }
            )
        }
        is Part.StepStart -> {
            // 步骤之间的视觉分隔符（已隐藏 —— WebUI 不显示这些）
        }
        is Part.StepFinish -> {
            // Token/费用信息聚合在 assistant 消息底部展示
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
        // 忽略不太相关的 parts
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


