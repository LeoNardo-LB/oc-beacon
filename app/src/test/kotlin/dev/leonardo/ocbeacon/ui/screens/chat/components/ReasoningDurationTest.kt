package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #263：思考卡天文时长「29800753m」回归锁定——start=0 哨兵（服务器未给真实
 * reasoning 起点）时完结时长必须按缺失处理，而非 end - 0 ≈ 当下 Unix 毫秒。
 */
class ReasoningDurationTest {
    @Test
    fun `start 为 0 哨兵时完结时长按缺失处理`() {
        assertNull(reasoningDurationMs(start = 0L, end = 1_788_000_000_000L))
    }

    @Test
    fun `start 为负同样按缺失处理`() {
        assertNull(reasoningDurationMs(start = -5L, end = 1_788_000_000_000L))
    }

    @Test
    fun `start 正常时返回差值`() {
        assertEquals(65_000L, reasoningDurationMs(start = 1_788_000_000_000L, end = 1_788_000_065_000L))
    }

    @Test
    fun `start 等于 end 时时长为零`() {
        assertEquals(0L, reasoningDurationMs(start = 1000L, end = 1000L))
    }

    // ===== #263 round2：完结显示时长合成（服务器未知 → 本地冻结实测） =====

    @Test
    fun `服务器可信时长优先`() {
        assertEquals(12_000L, resolveReasoningDisplayDuration(durationMs = 12_000L, frozenElapsedMs = 9_500L))
    }

    @Test
    fun `服务器未知时退回本地冻结实测值`() {
        assertEquals(9_500L, resolveReasoningDisplayDuration(durationMs = null, frozenElapsedMs = 9_500L))
    }

    @Test
    fun `零时长按未知处理由冻结值兜底`() {
        assertEquals(8_000L, resolveReasoningDisplayDuration(durationMs = 0L, frozenElapsedMs = 8_000L))
    }

    @Test
    fun `负时长按未知处理由冻结值兜底`() {
        assertEquals(8_000L, resolveReasoningDisplayDuration(durationMs = -5L, frozenElapsedMs = 8_000L))
    }

    @Test
    fun `两者皆未知返回 null 不显示伪造时长`() {
        assertNull(resolveReasoningDisplayDuration(durationMs = null, frozenElapsedMs = 0L))
        assertNull(resolveReasoningDisplayDuration(durationMs = 0L, frozenElapsedMs = 0L))
    }
}
