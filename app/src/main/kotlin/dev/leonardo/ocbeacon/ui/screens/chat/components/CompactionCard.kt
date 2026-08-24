package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
 * - 进行中（[state] 非空且 isActive）：分割线位置是全宽 indeterminate 进度线
 *   （M3 原生动画），中央「正在压缩上下文…」标签可展开——展开区实时渲染
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
 */
@Composable
internal fun CompactionCard(
    state: CompactionStateInfo? = null,
    summary: String? = null,
) {
    val isActive = state != null && state.isActive
    var expanded by remember { mutableStateOf(false) }
    val activeState = if (state != null && state.isActive) state else null
    val expandableText = when {
        activeState != null -> activeState.deltaText.takeIf { it.isNotBlank() }
        else -> summary?.takeIf { it.isNotBlank() }
    }
    val canExpand = expandableText != null
    val onToggle: () -> Unit = { if (canExpand) expanded = !expanded }

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
                onToggle = onToggle
            )
        }

        AnimatedVisibility(visible = expanded && canExpand) {
            ExpandContent(text = expandableText ?: "", active = activeState != null)
        }
    }
}

/**
 * 进行中态：进度线即分割线（Q8-A 用户裁决）——全宽 2dp indeterminate 进度线
 * 横贯，文字标签居中（表面色遮罩垫底保证进度线穿过时可读），可展开时带箭头。
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
    Column(modifier = Modifier.padding(vertical = SpacingTokens.XS.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { m -> if (canExpand) m.clickable(onClick = onToggle) else m }
                .padding(vertical = SpacingTokens.XS.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = SpacingTokens.MD.dp)
            )
            if (canExpand) {
                Spacer(modifier = Modifier.width(SpacingTokens.XS.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded)
                        stringResource(R.string.chat_collapse)
                    else
                        stringResource(R.string.chat_expand),
                    modifier = Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                )
            }
        }
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = AlphaTokens.MEDIUM),
            trackColor = Color.Transparent,
        )
    }
}

/** 完成态：静分割线 + 中央标签（既有形态；Q7/Q11 只改展开区——去边框竖线式）。 */
@Composable
private fun CompletedDividerRow(
    expanded: Boolean,
    canExpand: Boolean,
    onToggle: () -> Unit,
) {
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
                text = stringResource(R.string.chat_summarized),
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
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
        )
    }
}

/**
 * 展开区（Q11-B 用户裁决）：无边框引用式——左侧 2dp 细竖线 + Markdown 渲染，
 * 与消息内容同宽（无旧版 Surface 边框/圆角/水平内缩）。进行中态文本随
 * delta 流逐字生长（MarkdownContent 流式增量路径，48ms 批处理铁律）。
 */
@Composable
private fun ExpandContent(text: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.XS.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(240.dp)
                .background(
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM)
                )
        )
        Spacer(modifier = Modifier.width(SpacingTokens.MD.dp))
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
}
