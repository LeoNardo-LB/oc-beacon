package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 压缩分割线（2026-08-24 #217「分割线包揽一切」统一重构）。
 *
 * 一个元素两种状态：
 * - 进行中（[state] 非空且 isActive）：与完成态同构的骑线分割线——左右两
 *   段 2dp indeterminate LinearProgressIndicator 即分割线本体（track 为完成态
 *   同款 FAINT 静色线，tertiary 扫动段为进度动画，#220 用户裁决：不另占块），
 *   中央「正在压缩上下文…」标签可展开——展开区实时渲染
 *   deltaText 流式摘要（session.compaction.delta 逐段累积，逐字生长）。
 *   V2 由 SSE started/delta/ended 驱动；V1 由本地置态驱动（HTTP 挂起期间），
 *   无摘要则不可展开。
 * - 已完成（summary 非空）：静分割线 +「上下文已压缩」+ 展开箭头；展开区为
 *   无边框引用式布局——左侧 2dp 细竖线 + Markdown 渲染（bodySmall、降透明度），
 *   与消息流同宽（用户裁决 2026-08-24：旧边框比视图窄一圈太丑，废弃）。
 *
 * 状态切换（进行中到完成）由外部重组驱动：同一分割线原位切换，展开态保持
 * （Q13 连续性），流式文本无缝衔接最终全文。展开态不跨会话记忆（Q10）。
 * 动画裁决（#215 沿袭）：AnimatedVisibility 无参默认，零自定义 spec。
 *
 * #227（2026-08-26 用户反馈「拉到别处展开内容自动合上」）：展开态提升到调用方——
 * LazyColumn 视口外 item 会被丢弃，item 内 remember 随之清零，滚回即默认收起。
 * 由 ChatMessageList 按 messageId 维护展开表（屏幕级生命周期）：滚出视口不丢、
 * 离开会话即清（Q10 仍成立）。本组件退化为受控组件。
 */
@Composable
internal fun CompactionCard(
    state: CompactionStateInfo? = null,
    summary: String? = null,
    /** #219：失败压缩消息——失败标签（chat_session_compact_failed）+ 错误色。 */
    failed: Boolean = false,
    /** #227：受控展开态（提升到 ChatMessageList 的 messageId 键表）。 */
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val isActive = state != null && state.isActive
    val activeState = if (state != null && state.isActive) state else null
    val liveText = when {
        activeState != null -> activeState.deltaText.takeIf { it.isNotBlank() }
        else -> summary?.takeIf { it.isNotBlank() }
    }
    // #221：文本锁存——完成瞬间（ended 清态 → REST 刷新带入 summary 前的空窗）
    // 与失败（无 summary、delta 残留）都不收回展开：latchedText 记住最近一次
    // 非空文本，canExpand 不闪断 → AnimatedVisibility 不折叠，展开态跨完成保持。
    var latchedText by remember { mutableStateOf<String?>(null) }
    if (liveText != null) latchedText = liveText
    val expandableText = liveText ?: latchedText
    val canExpand = expandableText != null
    val onToggle: () -> Unit = { if (canExpand) onExpandedChange(!expanded) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (activeState != null) {
            ActiveDividerRow(
                expanded = expanded,
                canExpand = canExpand,
                reason = activeState.reason,
                onToggle = onToggle
            )
        } else {
            CompletedDividerRow(
                expanded = expanded,
                canExpand = canExpand,
                onToggle = onToggle,
                failed = failed
            )
        }

        AnimatedVisibility(visible = expanded && canExpand) {
            ExpandContent(text = expandableText ?: "", active = activeState != null)
        }
    }
}

/**
 * 进行中态（#220 用户裁决：标签骑线，不另占块）：与 [CompletedDividerRow]
 * 完全同构——线—标签—线。两段线各为 2dp indeterminate LinearProgressIndicator：
 * track 用完成态同款 FAINT 静色（分割线本体），tertiary 半透明扫动段即进度
 * 动画（M3 原生动画，零自定义 spec）。进行中→完成切换仅线由动转静、标签换
 * 文案，行高与位置零位移（Q13 连续性强化）。
 */
@Composable
private fun ActiveDividerRow(
    expanded: Boolean,
    canExpand: Boolean,
    reason: String,
    onToggle: () -> Unit,
) {
    val label = if (reason.isNotBlank()) {
        stringResource(R.string.chat_compressing_context, reason)
    } else {
        stringResource(R.string.chat_compressing_context_plain)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.XS.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .weight(1f)
                .height(2.dp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
        )
        Row(
            modifier = Modifier
                .let { m -> if (canExpand) m.clickable(onClick = onToggle) else m }
                .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
            )
            if (canExpand) {
                Spacer(modifier = Modifier.width(SpacingTokens.XS.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded)
                        stringResource(R.string.chat_collapse)
                    else
                        stringResource(R.string.chat_expand),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            }
        }
        LinearProgressIndicator(
            modifier = Modifier
                .weight(1f)
                .height(2.dp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT),
        )
    }
}

/** 完成态：静分割线 + 中央标签（既有形态；Q7/Q11 只改展开区——去边框竖线式）。
 *  #219：failed=true 时标签为「压缩会话失败」+ 错误色（失败压缩消息不再伪装成功）。 */
@Composable
private fun CompletedDividerRow(
    expanded: Boolean,
    canExpand: Boolean,
    onToggle: () -> Unit,
    failed: Boolean = false,
) {
    val labelRes = if (failed) R.string.chat_session_compact_failed else R.string.chat_summarized
    val labelColor = if (failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.XS.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
        )
        Row(
            modifier = Modifier
                .let { m -> if (canExpand) m.clickable(onClick = onToggle) else m }
                .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
            )
            if (canExpand) {
                Spacer(modifier = Modifier.width(SpacingTokens.XS.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded)
                        stringResource(R.string.chat_collapse)
                    else
                        stringResource(R.string.chat_expand),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
        )
    }
}

/**
 * 展开区（Q11-B 用户裁决；#221 高度修正）：无边框引用式——左侧 2dp 细竖线与
 * Markdown 内容**等高**（matchParentSize 叠加层绘制，弃固定 240dp；流式期间
 * 随 delta 增长实时变高）。左右取舍（#221 用户征询后代理裁决）：仅左侧——
 * 引用式语义（blockquote 惯例，与 Q11-B 设计语言一致）；双侧线无上下横线
 * 会读成「未闭合的框」，右线贴 Markdown 参差右缘徒增噪声。
 */
@Composable
private fun ExpandContent(text: String, active: Boolean) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)
    val lineWidth = 2.dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.XS.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = lineWidth + SpacingTokens.MD.dp)
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                MarkdownContent(
                    markdown = text,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (active) AlphaTokens.MUTED else AlphaTokens.MEDIUM
                    ),
                    isUser = false,
                    customFontSize = "small"
                )
            }
        }
        // 内容高度叠加层：matchParentSize 不参与测量（Box 高度由内容行决定），
        // 绘制阶段在左侧画 2dp 全高竖线——内容多高线就多高，流式增长零延迟跟随。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        color = lineColor,
                        topLeft = Offset.Zero,
                        size = Size(lineWidth.toPx(), size.height)
                    )
                }
        )
    }
}
