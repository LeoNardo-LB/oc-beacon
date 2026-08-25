package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog

/**
 * C4：压缩分割线统一渲染槽——V1 摘要线（assistant agent=compaction）、消息流
 * 触发线（role=compaction 对位 / 带 Compaction part）、尾部兜底线三处认领点的
 * 共用渲染分发目标（CompactionCard 本体零改动，2026-08-15 起分割线为可展开
 * 卡片）。职责：
 * - 撤销确认对话框 + 长按手势 + a11y 自定义无障碍动作（revertTargetId 非空时；
 *   2026-08-20 a11y P3：纯 pointerInput 长按 + semantics 自定义动作——空 onClick
 *   的 combinedClickable 会被 TalkBack 朗读为可点击但无动作）
 * - #227 屏幕级展开表接线（expandedStates 由 ChatMessageList 持有并传入——
 *   滚出视口不丢、离开会话即清）
 * - CompactionCard 参数分发（state/summary/failed）
 *
 * 流式增长补偿（deferredRevealCompensation，COMP-CMP）**不在此处**——各认领点
 * 原位构造含补偿的 growModifier 传入（SSE 滚动稳定性铁律：挂载点原位不动）。
 *
 * @param revertTargetId 撤销目标消息 id；null（尾部兜底——进行中无消息可撤）
 *   时不挂撤销交互（无对话框/长按/a11y 动作）。
 */
@Composable
internal fun CompactionDividerSlot(
    modifier: Modifier,
    expansionKey: String,
    state: CompactionStateInfo?,
    summary: String?,
    failed: Boolean,
    expandedStates: MutableMap<String, Boolean>,
    revertTargetId: String? = null,
    onRevert: ((String) -> Unit)? = null,
) {
    var showRevertDialog by remember { mutableStateOf(false) }
    if (revertTargetId != null && showRevertDialog) {
        ConfirmDialog(
            title = stringResource(R.string.chat_revert_title),
            message = stringResource(R.string.chat_revert_message),
            confirmLabel = stringResource(R.string.chat_revert),
            onDismiss = { showRevertDialog = false },
            onConfirm = {
                showRevertDialog = false
                onRevert?.invoke(revertTargetId)
            },
        )
    }
    val interactionModifier = if (revertTargetId != null) {
        val revertActionLabel = stringResource(R.string.chat_revert)
        Modifier
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { showRevertDialog = true })
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(
                        label = revertActionLabel,
                        action = { showRevertDialog = true; true }
                    )
                )
            }
    } else Modifier
    Column(modifier = modifier.then(interactionModifier)) {
        CompactionCard(
            expanded = expandedStates[expansionKey] ?: false,
            onExpandedChange = { expandedStates[expansionKey] = it },
            state = state,
            summary = summary,
            failed = failed,
        )
    }
}
