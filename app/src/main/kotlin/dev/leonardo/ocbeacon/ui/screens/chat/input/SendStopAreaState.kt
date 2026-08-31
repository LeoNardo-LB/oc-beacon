package dev.leonardo.ocbeacon.ui.screens.chat.input

/**
 * 发送/停止按钮区的可见键（2026-09-01 走查 #8 双键并存裁决）。
 */
internal enum class SendStopKey {
    /** 停止键：中断当前会话轮次。 */
    STOP,

    /** 发送键：空闲=发送；忙碌=服务端排队（DSH prompt mode=queue）。 */
    SEND,
}

/**
 * 按钮区状态机输出：可见键集 + 各键的转圈变体归属。
 *
 * @param keys 可见键，按布局顺序（停止在左、发送在右）
 * @param stopSpinner 停止键承载忙碌转圈（环形进度 + 小停止图标）
 * @param sendSpinner 发送键承载发送中转圈（环形进度 + 飞机图标，点击无效）
 */
internal data class SendStopAreaState(
    val keys: List<SendStopKey>,
    val stopSpinner: Boolean,
    val sendSpinner: Boolean,
)

/**
 * 发送/停止按钮区状态机（纯函数 seam，单测覆盖 busy × 输入空/非空 × isSending 全组合）。
 *
 * - 空闲：仅发送键（现状不变）
 * - 忙碌+输入空白：仅停止键（现状不变）
 * - 忙碌+输入非空：停止键+发送键并存（2026-09-01 用户裁决，Web 同款）——
 *   发送键点击走既有 sendMessage 链（DSH promptAsync 本就 mode=queue，服务端
 *   排队 → session/queue 帧 → QueueDock 呈现）；忙碌转圈由停止键承载
 */
internal fun sendStopAreaState(
    isBusy: Boolean,
    hasText: Boolean,
    isSending: Boolean,
): SendStopAreaState = when {
    isBusy && !hasText -> SendStopAreaState(
        keys = listOf(SendStopKey.STOP),
        stopSpinner = false,
        sendSpinner = false,
    )
    isBusy -> SendStopAreaState(
        keys = listOf(SendStopKey.STOP, SendStopKey.SEND),
        stopSpinner = !isSending,
        sendSpinner = isSending,
    )
    else -> SendStopAreaState(
        keys = listOf(SendStopKey.SEND),
        stopSpinner = false,
        sendSpinner = isSending,
    )
}
