package dev.leonardo.ocbeacon.ui.screens.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 发送状态存储 —— 悲观消息模式下仅保留"发送中"标志。
 *
 * 悲观消息（与 opencode 官方一致）：发送后不显示乐观消息，等待服务器
 * SSE 回显（MessageUpdated）才出现在列表；发送失败恢复草稿到输入框。
 * 因此乐观消息体系（pending 消息/parts、服务器确认对账）已整体移除。
 * [_isSending] 仅用于防快速双击（RS-007）与发送按钮禁用。
 */
internal class SendStateStore {
    private val _isSending = MutableStateFlow(false)
    /** 同步读取 [_isSending]，用于竞态条件保护（RS-007）。 */
    internal val isSendingValue: Boolean get() = _isSending.value
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    fun setSending(sending: Boolean) {
        _isSending.value = sending
    }
}
