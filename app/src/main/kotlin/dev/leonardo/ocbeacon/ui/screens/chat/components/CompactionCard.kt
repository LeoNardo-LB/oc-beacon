package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 压缩通知卡（#389 二轮终型，2026-09-10 用户裁决）——后台通知卡片样式，全服务器
 * 类型（V1 摘要线/V2 触发线/DSH 转录实体）压缩呈现的唯一视觉本体。
 *
 * 形态：透明底＋外描边（EventCard 族容器语言），左侧 32dp 圆形徽章内状态图标
 * （压缩中＝圈进度条、完成＝对钩、失败＝error 破色），右侧标题（状态文案）＋
 * 支持行（失败原因/#384 结算），有摘要时整卡点击展开 Markdown 摘要体。
 * 演化存档：#217 分割线形态 → #389 一轮思考壳（用户反馈「突兀」）→ 本终型。
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
    modifier: Modifier = Modifier,
) {
    val canExpand = !body.isNullOrBlank()
    val badgeColor = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val iconTint = if (failed) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Surface(
        shape = ShapeTokens.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.SM.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { m -> if (canExpand) m.clickable(onClick = onToggle) else m },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态徽章：压缩中圈进度条 / 完成对钩 / 失败 error
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(badgeColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        active -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = iconTint,
                        )
                        failed -> Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = iconTint,
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = iconTint,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(SpacingTokens.MD.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    if (!errorText.isNullOrBlank()) {
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                        )
                    }
                    if (!resultText.isNullOrBlank()) {
                        Text(
                            text = resultText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (resultIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                            },
                            maxLines = 2,
                        )
                    }
                }
                if (canExpand) {
                    Spacer(modifier = Modifier.width(SpacingTokens.SM.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) {
                            stringResource(R.string.chat_collapse)
                        } else {
                            stringResource(R.string.chat_expand)
                        },
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    )
                }
            }
            if (canExpand) {
                AnimatedVisibility(
                    visible = expanded,
                    enter = CardExpandEnterTransition,
                    exit = CardExpandExitTransition,
                ) {
                    Column(modifier = Modifier.padding(top = SpacingTokens.SM.dp)) {
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
    )
}
