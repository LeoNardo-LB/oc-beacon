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
import dev.leonardo.ocbeacon.ui.screens.chat.isReasoningStreaming
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCallCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ViewToolRequest
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.PatchCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ShellCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.TodoListCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ToolCardScaffold
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalAutoExpandTools
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

/**
 * #263：思考完结时长合成。start=0 哨兵（V2 reasoning.started 无服务器时间戳，
 * 服务器以 0 占位）时 end - 0 ≈ 当下 Unix 毫秒 → 「29800753m」天文时长症状根因；
 * start<=0 一律按缺失处理（返回 null），显示侧走 ReasoningBlock 的 startTimeMs
 * 降级链（流中计时/续计语义，见 PartContent 调用点注释），完结时长留空不显示。
 */
internal fun reasoningDurationMs(start: Long, end: Long): Long? =
    if (start > 0) end - start else null

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
    // 2026-08-22 滚动巨帧根治：非流式 fallback 异步解析（见 MarkdownContent）
    asyncParse: Boolean = false,
) {
    when (part) {
        is Part.Text -> {
            // 隐藏合成/忽略的文本 parts（内部系统内容）
            if (part.text.isNotBlank() && part.synthetic != true && part.ignored != true) {
                // opencode 可能以文本 part 发送问题和答案（而非结构化的 Part.Question）
                // 检测该格式并以可折叠卡片渲染
                if (part.text.contains("questions:") && part.text.contains("User has answered")) {
                    CollapsibleQuestionPart(question = part.text)
                } else if (isUser) {
                    // 2026-08-15 用户要求 + 官方 TUI 对齐（tui index.tsx:1420 纯文本
                    // <text> 渲染）：用户消息不渲染 Markdown——所见即所得，避免
                    // 下划线转斜体（v1_regression_e2e 误判根因）等意外转换。
                    SelectionContainer {
                        Text(
                            text = part.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                        )
                    }
                } else {
                    SelectionContainer {
                        MarkdownContent(
                            markdown = part.text,
                            textColor = textColor,
                            isUser = isUser,
                            immediate = !isUser,
                            overrideState = markdownStateOverride,
                            preParsedState = preParsedState,
                            asyncParse = asyncParse,
                        )
                    }
                }
            }
        }
        is Part.Reasoning -> {
            if (part.text.isNotBlank()) {
                // Reasoning 在 Waiting 阶段流式输出（TextStarted 之前）。
                // 2026-08-16 修复（重进后计时停住）+ #207（历史残留卡永续 tick /
                // 滑动归零）：三态合成见 isReasoningStreaming——未结束 ∧（有锚 ∨
                // 会话流式）才计时；time=null 的历史残留（野生实例：事故恢复消息）
                // 在 idle 会话下静态显示，不再以组合期时钟为锚反复归零。
                val sessionStreaming = LocalSessionStreaming.current
                val partEnded = part.time?.end != null
                // start 降级链：part.time.start（>0）→ null（ReasoningBlock 内部
                // 降级到 rememberSaveable 首组合时刻——重进场景即"从进入时刻续计"，
                // 正确语义：服务器侧真实起点不可知（V2 reasoning.started 无服务器
                // 时间戳，本地时刻在退出时丢失），续计优于冻结；saveable 保证
                // 滑出视口销毁后滑回不重置）。
                val startTimeMs = part.time?.start?.takeIf { it > 0 }
                val isStreaming = isReasoningStreaming(
                    partEnded = partEnded,
                    sessionStreaming = sessionStreaming,
                    hasValidAnchor = startTimeMs != null,
                )
                val reasoningDuration = part.time?.let { t ->
                    t.end?.let { end -> reasoningDurationMs(t.start, end) }
                }
                val toolExpandedStates = LocalToolExpandedStates.current
                val onToggleToolExpanded = LocalOnToggleToolExpanded.current
                val expandReasoningDefault = LocalExpandReasoning.current
                val rbExpanded = toolExpandedStates[part.id] ?: expandReasoningDefault
                androidx.compose.runtime.LaunchedEffect(part.id, rbExpanded) {
                    dev.leonardo.ocbeacon.logging.AppLogger.w(
                        "RB-EXP",
                        "[DEBUG-rbexp] RB id=" + part.id.takeLast(8) + " expanded=" + rbExpanded +
                            " mapHit=" + (toolExpandedStates[part.id] != null) +
                            " default=" + expandReasoningDefault
                    )
                }
                ReasoningBlock(
                    text = part.text,
                    isExpanded = rbExpanded,
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
                        val autoExpand = LocalAutoExpandTools.current
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
                } else {
                    // 2026-08-16 修复（用户报告"回答完毕后问题卡片直接消失"）：
                    // 服务器回答后常以 error 态收尾 question part——实测 8 样本中 6 个为
                    // error="Tool execution interrupted[: question]"（回答动作本身触发）、
                    // 1 个 "The user dismissed this question"（取消）。error 态 input
                    // 保留完整 questions 结构（与 completed 同构），但此前只认 Completed
                    // → as? 转型为 null → 不渲染 → 交互卡片随 pendingQuestions 移除而
                    // 消失，折叠卡片又不出现 = 卡片彻底消失。
                    // 修复：error 态同样渲染折叠卡片（复用 QuestionExpandedOptions，
                    // output 传空 → 无答案高亮，展开可看问题与选项）。
                    val errorState = part.state as? ToolState.Error
                    if (errorState != null) {
                        val parsed = remember(part.id) {
                            QuestionParser.parseQuestionFromToolData(part.id, errorState.input, "")
                        }
                        if (parsed.any { it.options.isNotEmpty() }) {
                            val autoExpand = LocalAutoExpandTools.current
                            ToolCardScaffold(
                                icon = Icons.AutoMirrored.Filled.HelpOutline,
                                iconTint = MaterialTheme.colorScheme.primary,
                                title = "Asked",
                                copyText = "",
                                isExpanded = toolExpandedStates[part.id] ?: autoExpand,
                                isRunning = false,
                                hasContent = true,
                                isAmoled = isAmoledTheme(),
                                onToggleExpand = { onToggleToolExpanded(part.id, autoExpand) },
                            ) {
                                QuestionExpandedOptions(parsed)
                            }
                        }
                    }
                    // 未完成（活跃）：不渲染——提问由 QuestionCard 展示
                }
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
                    val autoExpand = LocalAutoExpandTools.current
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
                val autoExpand = LocalAutoExpandTools.current
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
                    // 降级到通用 ToolCallCard
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
                // #215 批2 修复首击陷阱：初值 ?: false 与 toggle 默认参必须一致
                //（原默认参 true：首击 null→!true=false 视觉无变化，需双击才展开）
                isExpanded = toolExpandedStates[part.id] ?: false,
                onToggleExpand = { onToggleToolExpanded(part.id, false) }
            )
        }
        is Part.StepStart -> {
            // 步骤之间的视觉分隔符（已隐藏 —— WebUI 不显示这些）
        }
        is Part.StepFinish -> {
            // Token/费用信息聚合在 assistant 消息底部展示
        }
        is Part.Patch -> {
            val autoExpand = LocalAutoExpandTools.current
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
                text = stringResource(R.string.chat_interrupted, part.reason),
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


