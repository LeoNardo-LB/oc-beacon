package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 压缩通知卡（#389 三轮终型，2026-09-10 用户裁决）——消息卡家族规格
 *（[MessageBubble] 骨架：透明底＋外描边＋10sp 标签行＋13dp 前导图标＋右贴
 * trailing 组），全服务器类型（V1 摘要线/V2 触发线/DSH 转录实体）压缩呈现的
 * 唯一视觉本体。
 *
 * 布局：[时间] [摘要图标 13dp（用户裁决 2026-09-10 六选一）] [状态文案·flush] — 右缘 [状态图标 14dp（压缩中
 * 圈进度/完成对钩/失败 error）] [展开箭头]。展开体＝Markdown 摘要，240dp 限高
 * 内滚（对齐思考卡）；#384 结算/失败原因以 labelSmall 支持行常驻。
 * 演化存档：#217 分割线 → #389 一轮思考壳（「突兀」）→ 二轮通知卡（徽章偏重）
 * → 本三轮家族规格。
 *
 * 文案族（三认领点统一）：chat_compressing_context(_plain) / chat_summarized /
 * chat_session_compact_failed。
 */
@Composable
internal fun CompactionNoticeCard(
    title: String,
    active: Boolean,
    failed: Boolean,
    errorText: String?,
    resultText: String?,
    resultIsError: Boolean,
    body: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    timeMs: Long,
    modifier: Modifier = Modifier,
) {
    val canExpand = !body.isNullOrBlank()
    val borderColor = if (failed) {
        AgentError.copy(alpha = AlphaTokens.MEDIUM)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = AlphaTokens.MEDIUM)
    }
    val labelIconTint = if (failed) {
        AgentError
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
    }
    val statusTint = if (failed) {
        AgentError
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    val hasSupportRows = !errorText.isNullOrBlank() || !resultText.isNullOrBlank()
    // 尾部兜底等无消息时间戳的认领点：取首组合时刻（活体压缩≈当下）
    val displayTime = remember(timeMs) {
        if (timeMs > 0) timeMs else System.currentTimeMillis()
    }

    MessageBubble(
        alignEnd = false,
        containerColor = Color.Transparent,
        border = BorderStroke(1.dp, borderColor),
        shape = ShapeTokens.medium,
        label = title,
        timeMs = displayTime,
        labelRowHorizontalPadding = 8.dp,
        onCardClick = if (canExpand) ({ onToggle() }) else null,
        labelLeading = {
            Icon(
                imageVector = Icons.Outlined.Summarize,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = labelIconTint,
            )
        },
        labelTrailing = {
            // 状态图标（右贴组首位）：压缩中圈进度条 / 完成对钩 / 失败 error
            when {
                active -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = statusTint,
                )
                failed -> Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = statusTint,
                )
                else -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = statusTint,
                )
            }
            if (canExpand) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.chat_collapse)
                    } else {
                        stringResource(R.string.chat_expand)
                    },
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                )
            }
        },
        modifier = modifier,
        // #389 三轮c：内容栏 AnimatedVisibility（统一展开/收起动画）。
        // 有支持行＝内容常驻（null，正文走卡内 AV）；无支持行＝随展开态动画。
        contentExpanded = if (hasSupportRows) null else expanded,
    ) {
        // 支持行：失败原因（排障优先）＋ #384 吸收的源命令结算
        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.labelSmall,
                color = AgentError,
                maxLines = 2,
            )
        }
        if (!resultText.isNullOrBlank()) {
            Text(
                text = resultText,
                style = MaterialTheme.typography.labelSmall,
                color = if (resultIsError) {
                    AgentError
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                },
                maxLines = 2,
            )
        }
        // 展开体：Markdown 摘要，240dp 限高内滚（对齐思考卡 #2026-08-16 定档）
        if (canExpand) {
            CardExpandReveal(
                visible = expanded,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clipToBounds()
                        .verticalScroll(rememberScrollState()),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        MarkdownContent(
                            markdown = body,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = AlphaTokens.MUTED,
                            ),
                            isUser = false,
                            customFontSize = "small",
                        )
                    }
                }
            }
        }
    }
}

/**
 * V1 摘要线/消息流触发线/尾部兜底线三认领点的参数适配（视觉本体见
 * [CompactionNoticeCard]）。沿袭：#221 文本锁存（完成空窗/失败不收回展开）、
 * #227 受控展开态（ChatMessageList messageId 键表）、流式 deltaText 进行中
 * 可读；V2 由 SSE started/delta/ended 驱动，V1 由本地置态驱动。
 */
@Composable
internal fun CompactionCard(
    state: CompactionStateInfo? = null,
    summary: String? = null,
    /** #219：失败压缩消息——失败标签 + error 破色。 */
    failed: Boolean = false,
    /** #227：受控展开态。 */
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    /** 认领消息时间戳（0=尾部兜底等无时间源→取当下）。 */
    timeMs: Long = 0L,
) {
    val activeState = if (state != null && state.isActive) state else null
    val liveText = when {
        activeState != null -> activeState.deltaText.takeIf { it.isNotBlank() }
        else -> summary?.takeIf { it.isNotBlank() }
    }
    // #221：文本锁存——latchedText 记住最近一次非空文本，canExpand 不闪断，
    // 展开态跨完成/失败保持。
    var latchedText by remember { mutableStateOf<String?>(null) }
    if (liveText != null) latchedText = liveText
    val expandableText = liveText ?: latchedText

    val title = when {
        activeState != null && activeState.reason.isNotBlank() ->
            stringResource(R.string.chat_compressing_context, activeState.reason)
        activeState != null -> stringResource(R.string.chat_compressing_context_plain)
        failed -> stringResource(R.string.chat_session_compact_failed)
        else -> stringResource(R.string.chat_summarized)
    }

    CompactionNoticeCard(
        title = title,
        active = activeState != null,
        failed = failed,
        errorText = null,
        resultText = null,
        resultIsError = false,
        body = expandableText,
        expanded = expanded,
        onToggle = { if (expandableText != null) onExpandedChange(!expanded) },
        timeMs = timeMs,
    )
}
