package dev.leonardo.ocbeacon.ui.screens.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sendStopAreaState] 按钮区状态机单测（2026-09-01 走查 #8：忙碌双键并存）。
 *
 * 覆盖 busy × 输入空/非空 × isSending 组合的可见键集与变体：
 * - 空闲：仅发送键
 * - 忙碌+输入空白：仅停止键
 * - 忙碌+输入非空：仅发送键（2026-09-04 用户裁决 #326 单键统一，对齐 web 主按钮
 *   primaryStops=running&&(empty||blocked)——忙+文本=排队发送；取代 2026-09-01 双键并存）
 */
class SendStopAreaStateTest {

    @Test
    fun `空闲无文本_仅发送键`() {
        val s = sendStopAreaState(isBusy = false, hasText = false, isSending = false)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `空闲有文本_仅发送键`() {
        val s = sendStopAreaState(isBusy = false, hasText = true, isSending = false)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `空闲发送中_仅发送键_发送转圈变体`() {
        val s = sendStopAreaState(isBusy = false, hasText = false, isSending = true)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertTrue(s.sendSpinner)
    }

    @Test
    fun `空闲有文本发送中_仅发送键_发送转圈变体`() {
        val s = sendStopAreaState(isBusy = false, hasText = true, isSending = true)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertTrue(s.sendSpinner)
    }

    @Test
    fun `忙碌无文本_仅停止键_无双键`() {
        val s = sendStopAreaState(isBusy = true, hasText = false, isSending = false)
        assertEquals(listOf(SendStopKey.STOP), s.keys)
        assertFalse(s.stopSpinner)
    }

    @Test
    fun `忙碌无文本发送中_仅停止键`() {
        val s = sendStopAreaState(isBusy = true, hasText = false, isSending = true)
        assertEquals(listOf(SendStopKey.STOP), s.keys)
        assertFalse(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `忙碌有文本_仅发送键_单键排队`() {
        // 2026-09-04 用户裁决（#326）：busy 输入区统一单按钮——忙+文本=发送键（服务端排队）
        val s = sendStopAreaState(isBusy = true, hasText = true, isSending = false)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `忙碌有文本发送中_仅发送键_发送转圈变体`() {
        val s = sendStopAreaState(isBusy = true, hasText = true, isSending = true)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertTrue(s.sendSpinner)
    }

    // ---- inputBlocked（等待提问/权限应答，web blocked 对位；#326） ----

    @Test
    fun `忙碌有文本_被阻塞_仅停止键`() {
        val s = sendStopAreaState(isBusy = true, hasText = true, isSending = false, inputBlocked = true)
        assertEquals(listOf(SendStopKey.STOP), s.keys)
        assertFalse(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `忙碌空白_被阻塞_仅停止键`() {
        val s = sendStopAreaState(isBusy = true, hasText = false, isSending = false, inputBlocked = true)
        assertEquals(listOf(SendStopKey.STOP), s.keys)
        assertFalse(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `空闲有文本_被阻塞_仅发送键_禁用由渲染层承载`() {
        // 空闲+被阻塞：键集仍为发送（web 同款——primaryStops 需 running）；
        // 实际禁用由 canSend(inputEnabled) 在渲染层关闭。
        val s = sendStopAreaState(isBusy = false, hasText = true, isSending = false, inputBlocked = true)
        assertEquals(listOf(SendStopKey.SEND), s.keys)
        assertFalse(s.sendSpinner)
    }
}
