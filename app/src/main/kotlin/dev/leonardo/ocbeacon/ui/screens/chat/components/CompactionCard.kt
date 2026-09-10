package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo

/**
 * 压缩卡（V1 摘要线/消息流触发线/尾部兜底线三认领点的共用视觉本体）。
 *
 * #389（2026-09-10 用户裁决）：弃 2026-08-24 #217 定型的「骑线分割线」形态
 * （左右两段 LinearProgressIndicator 分割线 + 中央标签——观感丑），改以思考卡
 * （[ReasoningBlock]）为容器改文案复用：进行中＝脉冲圆点 + 圈进度条（流式摘要
 * 未到达前）+「正在压缩上下文…」；完成＝「上下文已压缩」；失败＝error 破色。
 * 全服务器类型（V1/V2/DSH）压缩 UIUX 由此单一化——ui-conventions
 * 「服务器类型交互统一铁律」。
 *
 * 沿袭不动：
 * - #221 文本锁存——完成瞬间（ended 清态 → REST 刷新带入 summary 前的空窗）
 *   与失败（无 summary、delta 残留）都不收回展开：latchedText 记住最近一次
 *   非空文本，canExpand 不闪断 → 展开态跨完成保持。
 * - #227 受控展开态（提升到 ChatMessageList 的 messageId 键表，滚出视口不丢）。
 * - 流式摘要 deltaText 逐段累积（session.compaction.delta），进行中即可展开
 *   阅读；无摘要不可展开。V2 由 SSE started/delta/ended 驱动，V1 由本地置态
 *   驱动（HTTP 挂起期间）。
 */
@Composable
internal fun CompactionCard(
    state: CompactionStateInfo? = null,
    summary: String? = null,
    /** #219：失败压缩消息——失败标签（chat_session_compact_failed）+ error 破色。 */
    failed: Boolean = false,
    /** #227：受控展开态（提升到 ChatMessageList 的 messageId 键表）。 */
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val activeState = if (state != null && state.isActive) state else null
    val liveText = when {
        activeState != null -> activeState.deltaText.takeIf { it.isNotBlank() }
        else -> summary?.takeIf { it.isNotBlank() }
    }
    // #221：文本锁存（见类注释）
    var latchedText by remember { mutableStateOf<String?>(null) }
    if (liveText != null) latchedText = liveText
    val expandableText = liveText ?: latchedText
    val canExpand = expandableText != null
    val onToggle: () -> Unit = { if (canExpand) onExpandedChange(!expanded) }

    val label = when {
        activeState != null && activeState.reason.isNotBlank() ->
            stringResource(R.string.chat_compressing_context, activeState.reason)
        activeState != null -> stringResource(R.string.chat_compressing_context_plain)
        failed -> stringResource(R.string.chat_session_compact_failed)
        else -> stringResource(R.string.chat_summarized)
    }

    ReasoningBlock(
        text = expandableText ?: "",
        isExpanded = expanded,
        onToggleExpand = onToggle,
        isStreaming = activeState != null,
        headerOverride = label,
        accentOverride = if (failed) MaterialTheme.colorScheme.error else null,
    )
}
