package dev.leonardo.ocbeacon.ui.screens.chat.input

/**
 * 发送/停止按钮区的可见键（#326 单键统一，2026-09-04 用户裁决）。
 */
internal enum class SendStopKey {
    /** 停止键：中断当前会话轮次。 */
    STOP,

    /** 发送键：空闲=发送；忙碌=服务端排队（DSH prompt mode=queue；长按=直发插话 steer #309④）。 */
    SEND,
}

/**
 * 按钮区状态机输出：可见键集 + 各键的转圈变体归属。
 *
 * @param keys 可见键（单键时代恒为 1 键；保留列表结构兼容布局与测试断言）
 * @param stopSpinner 停止键忙碌转圈变体（单键时代恒 false——忙碌转圈仅存在于已退役的双键态）
 * @param sendSpinner 发送键发送中转圈变体（环形进度 + 飞机图标，点击无效）
 */
internal data class SendStopAreaState(
    val keys: List<SendStopKey>,
    val stopSpinner: Boolean,
    val sendSpinner: Boolean,
)

/**
 * 发送/停止按钮区状态机（纯函数 seam，单测覆盖 busy × 文本 × isSending × inputBlocked 组合）。
 *
 * #326（2026-09-04 用户裁决）：busy 输入区统一**单按钮**承担发送/入队，对齐 web 主按钮
 * （dsh web mod20.js：`primaryStops = running && !subagent && (empty || blocked)`，否则发送
 * ——忙+文本=排队提交，Cmd/Ctrl+Enter=steer；移动端长按=steer 对位）。取代 2026-09-01
 * 走查 #8 的忙碌双键并存裁决。
 *
 * - 空闲：仅发送键
 * - 忙碌+输入空白：仅停止键
 * - 忙碌+输入非空：仅发送键（点击=服务端排队；长按=直发插话 steer）
 * - 忙碌+被阻塞（等待提问/权限应答，inputEnabled=false）：仅停止键（web blocked 同款）
 */
internal fun sendStopAreaState(
    isBusy: Boolean,
    hasText: Boolean,
    isSending: Boolean,
    inputBlocked: Boolean = false,
): SendStopAreaState = when {
    isBusy && (!hasText || inputBlocked) -> SendStopAreaState(
        keys = listOf(SendStopKey.STOP),
        stopSpinner = false,
        sendSpinner = false,
    )
    else -> SendStopAreaState(
        keys = listOf(SendStopKey.SEND),
        stopSpinner = false,
        sendSpinner = isSending,
    )
}
