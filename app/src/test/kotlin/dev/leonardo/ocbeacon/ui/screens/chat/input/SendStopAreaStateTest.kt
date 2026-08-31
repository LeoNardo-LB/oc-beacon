package dev.leonardo.ocbeacon.ui.screens.chat.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sendStopAreaState] 按钮区状态机单测（2026-09-01 走查 #8：忙碌双键并存）。
 *
 * 覆盖 busy × 输入空/非空 × isSending 全 8 组合的可见键集与变体：
 * - 空闲：仅发送键（现状不变）
 * - 忙碌+输入空白：仅停止键（现状不变）
 * - 忙碌+输入非空：停止键+发送键并存（2026-09-01 用户裁决，发送点击=服务端排队）；
 *   忙碌转圈变体由停止键承载
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
    fun `忙碌有文本_停止加发送双键_停止键转圈变体`() {
        // 2026-09-01 用户裁决核心场景：忙碌且输入非空 → 双键并存
        val s = sendStopAreaState(isBusy = true, hasText = true, isSending = false)
        assertEquals(listOf(SendStopKey.STOP, SendStopKey.SEND), s.keys)
        assertTrue(s.stopSpinner)
        assertFalse(s.sendSpinner)
    }

    @Test
    fun `忙碌有文本发送中_双键_排队转圈由发送键承载`() {
        val s = sendStopAreaState(isBusy = true, hasText = true, isSending = true)
        assertEquals(listOf(SendStopKey.STOP, SendStopKey.SEND), s.keys)
        assertFalse(s.stopSpinner)
        assertTrue(s.sendSpinner)
    }
}
