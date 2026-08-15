package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ContextToolGroupCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalShowTurnDividers
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.delay

/**
 * 智能体消息气泡——统一容器（MessageBubble）：
 * 标签栏（时间 + "智能体"）+ 正文（renderItems：文本/推理/工具卡片/分隔线）+
 * 统计栏（agent 标签 / 提供商·模型 / 时长 / 复制）+ 错误展示（气泡内）。
 * 左对齐 + surfaceVariant 底色 + ShapeTokens.medium 圆角。
 */
@Composable
internal fun MessageCardAssistant(
    renderableTurn: RenderableTurn,
    currentMessage: ChatMessage,
    onViewSubSession: ((String) -> Unit)?,
    onOpenFile: ((String) -> Unit)?,
    isAmoled: Boolean,
    isTurnLast: Boolean,
    /** turn 级流式判定（turn 内任一消息 completed == null）。多消息 turn 时
     *  代表消息（oldest）可能已完成，仅看自身会漏判流式 → 统计栏延迟到
     *  回复完毕才出现（2026-08 修复：统计栏应在气泡出现时同步出现）。 */
    isStreamingTurn: Boolean = false,
    agents: List<AgentInfo> = emptyList(),
    onCopy: (() -> Unit)? = null,
    onLocateTask: ((String) -> Unit)? = null,
    /** 嵌入思考卡片（ReasoningBlock）的待处理提问（2026-08-14）。 */
    pendingQuestion: SseEvent.QuestionAsked? = null,
    onQuestionSubmit: ((String, List<List<String>>) -> Unit)? = null,
    onQuestionReject: ((String) -> Unit)? = null,
) {
    // D2-L22：原 if(isAmoled) 两分支相同（死条件）——直接取 onSurface
    val textColor = MaterialTheme.colorScheme.onSurface

    if (renderableTurn.isEmpty) return

    val compact = LocalChatDensity.current == ChatDensity.Compact
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val showTurnDividers = LocalShowTurnDividers.current

    // 保留供统计栏显示（agent/模型）
    val assistantMsg = currentMessage.message as? Message.Assistant
    // turn 级流式判定：turn 内任一消息仍在流式即视为流式（多消息 turn 的
    // 代表消息是 oldest 可能已完成，仅看自身会漏判 → 统计栏延迟出现）。
    val isStreaming = isStreamingTurn || (assistantMsg?.time?.completed == null)

    // 预计算的元数据
    val agentName = renderableTurn.agentName
    val copyText = renderableTurn.copyText
    val modelId = renderableTurn.modelId

    // 统一统计栏 —— 消息气泡页脚（流式/完成是同一事物的两种状态，2026-08-07 合并）。
    // 流式：显示实时耗时（ticker 每秒刷新）；完成：显示固定时长 + 复制按钮。
    // 显示条件：流式必有；完成态有统计内容（时长/模型/agent）或仅需复制按钮时显示。
    val durationMs = renderableTurn.durationMs
    val hasFooter = (durationMs ?: 0) > 0 || !modelId.isNullOrBlank() || !agentName.isNullOrBlank()
    val showStatsBar = isStreaming || hasFooter || (copyText != null && isTurnLast)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        MessageBubble(
            alignEnd = false,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            border = if (isAmoled) AmoledDefaultBorder else null,
            shape = ShapeTokens.medium,
            label = stringResource(R.string.chat_label_agent),
            timeMs = currentMessage.message.time.created,
            statsBar = if (showStatsBar) {
                {
                    // 耗时显示：流式 = 实时 ticker（独立子 composable，重组只限单个 Text，
                    // 不触发整个 footer Row——#47：原 100ms ticker 在 footer 级 state，
                    // 与 48ms flush 叠加 ~30 次/s footer 重组）；完成 = 固定时长。
                    val startMs = renderableTurn.turnStartMs ?: assistantMsg?.time?.created

                    // Agent 名称标签（2026-08-12：与输入组件 agent 选择器同款紧凑标签——
                    // M3 SuggestionChip 32dp 偏大，用户确认改回紧凑样式）
                    if (!agentName.isNullOrBlank()) {
                        val tagColor = agentColor(agentName, agents)
                        AgentTag(agent = agentName, tagColor = tagColor)
                    }
                    // 提供商图标 + 模型名
                    val hasProviderOrModel = assistantMsg?.providerId != null || !modelId.isNullOrBlank()
                    if (hasProviderOrModel) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (assistantMsg?.providerId != null) {
                                ProviderIcon(
                                    providerId = assistantMsg.providerId,
                                    size = 10.dp,
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                                )
                            }
                            if (!modelId.isNullOrBlank()) {
                                Text(
                                    text = modelId,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    // 2026-08-15 用户要求：移除 Token 占比圆环——无信息量。
                    // 统计栏仅保留：agent 徽标 / 模型图标+模型名 / 耗时 / 右对齐复制。
                    // 耗时（流式 = 实时 ticker 子 composable；完成 = 固定）
                    if (isStreaming && startMs != null) {
                        StreamingElapsedText(startMs)
                    } else if (!isStreaming && (durationMs ?: 0L) > 0) {
                        Text(
                            text = formatDuration(durationMs!!),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 复制按钮（仅完成态）
                    if (!isStreaming && copyText != null) {
                        CopyButton(
                            text = copyText,
                            modifier = Modifier.size(14.dp),
                            onCopied = onCopy
                        )
                    }
                }
            } else null,
        ) {
            // 渲染预计算项 —— 组合期间零过滤/零分组。
            for (item in renderableTurn.renderItems) {
                when (item) {
                    is RenderItem.TurnDivider -> {
                        if (showTurnDividers) {
                            // 暗色模式下 outlineVariant 偏暗 + 半透明几乎不可见，改用更亮的 outline
                            val dividerColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = if (compact) 3.dp else 6.dp),
                                color = dividerColor
                            )
                        }
                    }
                    is RenderItem.SyntheticNotice -> {
                        // synthetic 系统通知卡片（后台任务完成）——嵌入气泡内渲染
                        key(item.msgId) {
                            SyntheticNotificationCard(
                                currentMessage = item.message,
                                isAmoled = isAmoled,
                                onViewSubSession = onViewSubSession,
                                onLocateTask = onLocateTask,
                            )
                        }
                    }
                    is RenderItem.GroupedParts -> {
                        when (item.group) {
                            is PartGroup.Context -> key(item.group.parts.first().id) {
                                ContextToolGroupCard(
                                    parts = item.group.parts,
                                    onOpenFile = onOpenFile ?: {},
                                )
                            }
                            is PartGroup.Single -> key(item.group.part.id) {
                                PartContent(
                                    part = item.group.part,
                                    textColor = textColor,
                                    isUser = false,
                                    onViewSubSession = onViewSubSession,
                                    onOpenFile = onOpenFile,
                                    turnAgentName = if (item.group.part is Part.Tool && item.group.part.tool == "task") {
                                        renderableTurn.taskAgentName
                                    } else null,
                                )
                                // 2026-08-14：待处理提问渲染为独立提问卡片——
                                // 位于思考卡片（ReasoningBlock）之后、气泡内；
                                // 不嵌入推理文本内部（用户反馈"嵌入到思考过程中"是 bug）。
                                // 2026-08-14 走查修复（#131）：V1 的 question 工具调用消息是
                                // Part.Tool 而非 Part.Reasoning——原条件仅 Reasoning 导致
                                // tool 消息上的问题卡片不渲染；同时 unembeddedQuestions 因
                                // 已匹配嵌入而排除 → 卡片凭空消失 + 输入框禁用（UI 卡死）。
                                // 放宽为 Reasoning 或 Tool（question/permission 工具调用）都渲染。
                                if (pendingQuestion != null &&
                                    (item.group.part is Part.Reasoning || item.group.part is Part.Tool)
                                ) {
                                    QuestionCard(
                                        question = pendingQuestion,
                                        onSubmit = { answers ->
                                            onQuestionSubmit?.invoke(pendingQuestion.id, answers)
                                        },
                                        onReject = {
                                            onQuestionReject?.invoke(pendingQuestion.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 错误展示（气泡内）
            if (renderableTurn.errorText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = AlphaTokens.FAINT),
                    shape = ShapeTokens.mediumSmall,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (isAmoled) AlphaTokens.HIGH else AlphaTokens.FAINT)),
                    tonalElevation = 0.dp,
                ) {
                    ErrorPayloadContent(
                        text = renderableTurn.errorText,
                        textStyle = MaterialTheme.typography.bodySmall,
                        textColor = textColor,
                        modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * 流式耗时实时显示（#47 优化）。
 *
 * 独立子 composable：内部 ticker（2026-08-15 用户要求：1s → 300ms，
 * 秒级小数进度感）更新自身 state——重组范围仅限
 * 本 Text，不触发整个 footer Row 重组（原实现 ticker state 在 footer 级，
 * 与 48ms SSE flush 叠加导致 ~30 次/s footer 重组）。
 */
@Composable
private fun StreamingElapsedText(startMs: Long) {
    var elapsedText by remember { mutableStateOf("0s") }
    LaunchedEffect(startMs) {
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            elapsedText = formatDuration(elapsedMs)
            delay(100)
        }
    }
    Text(
        text = elapsedText,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
    )
}
