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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
) {
    val textColor = if (isAmoled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface

    if (renderableTurn.isEmpty) return

    val compact = LocalChatDensity.current == ChatDensity.Compact
    val hapticView = LocalView.current
    val hapticOn = LocalHapticFeedbackEnabled.current
    val showTurnDividers = LocalShowTurnDividers.current

    // 保留供页脚显示（时间、提供商图标）
    val assistantMsg = currentMessage.message as? Message.Assistant
    // turn 级流式判定：turn 内任一消息仍在流式即视为流式（多消息 turn 的
    // 代表消息是 oldest 可能已完成，仅看自身会漏判 → 统计栏延迟出现）。
    val isStreaming = isStreamingTurn || (assistantMsg?.time?.completed == null)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = ShapeTokens.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = if (isAmoled) AmoledDefaultBorder else null,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else SpacingTokens.LG.dp,
                    vertical = if (compact) SpacingTokens.SM.dp else 14.dp
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else SpacingTokens.XS.dp)
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
                                }
                            }
                        }
                    }
                }

                // 预计算的元数据
                val agentName = renderableTurn.agentName
                val copyText = renderableTurn.copyText
                val modelId = renderableTurn.modelId

                // 统一统计栏 —— 消息气泡页脚（流式/完成是同一事物的两种状态，2026-08-07 合并）。
                // 流式：显示实时耗时（ticker 每秒刷新）；完成：显示固定时长 + 复制按钮。
                // 显示条件：流式必有；完成态有统计内容（时长/模型/agent）或仅需复制按钮时显示。
                // 流式与完成互斥（isStreaming / !isStreaming），不会重叠。
                val durationMs = renderableTurn.durationMs
                val hasFooter = (durationMs ?: 0) > 0 || !modelId.isNullOrBlank() || !agentName.isNullOrBlank()
                if (isStreaming || hasFooter || (copyText != null && isTurnLast)) {
                    // 耗时显示：流式 = 实时 ticker（独立子 composable，重组只限单个 Text，
                    // 不触发整个 footer Row——#47：原 100ms ticker 在 footer 级 state，
                    // 与 48ms flush 叠加 ~30 次/s footer 重组）；完成 = 固定时长。
                    val startMs = renderableTurn.turnStartMs ?: assistantMsg?.time?.created

                    Spacer(modifier = Modifier.height(if (compact) SpacingTokens.XS.dp else SpacingTokens.SM.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 时间
                        assistantMsg?.time?.created?.let { createdMs ->
                            val timeText = remember(createdMs) {
                                SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(Date(createdMs))
                            }
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                            )
                        }
                        // Agent 名称标签（样式类似 QUEUED 徽章，带 agent 颜色）
                        if (!agentName.isNullOrBlank()) {
                            val tagColor = agentColor(agentName, agents)
                            Surface(
                                shape = ShapeTokens.smallMedium,
                                color = tagColor.copy(alpha = AlphaTokens.FAINT)
                            ) {
                                Text(
                                    text = agentName.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = tagColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
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
                }

                // 错误展示
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
}

/**
 * 流式耗时实时显示（#47 优化）。
 *
 * 独立子 composable：内部 100ms ticker 更新自身 state——重组范围仅限
 * 本 Text，不触发整个 footer Row 重组（原实现 ticker state 在 footer 级，
 * 与 48ms SSE flush 叠加导致 ~30 次/s footer 重组）。
 * isStreaming 结束（父分支移出组合）时 LaunchedEffect 自动取消。
 */
@Composable
private fun StreamingElapsedText(
    startMs: Long,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(100)
        }
    }
    Text(
        text = formatDuration(nowMs - startMs),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
        modifier = modifier
    )
}
