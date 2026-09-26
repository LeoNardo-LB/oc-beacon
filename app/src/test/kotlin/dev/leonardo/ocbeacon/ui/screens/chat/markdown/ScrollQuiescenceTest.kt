package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 滚动静止单信号源（#437 二十五世轮架构审查 C1 根修）：
 * 「滚动期静止」语义原先四处分身（pilot append 暂缓 / ChatScreen 快照冻结 /
 * ledger rebaseAll / 帽持帽）——统一为 ScrollQuiescence 单点，写点唯一（flush task），
 * 四处消费者只读。本测试锁信号语义与迁移兼容缝。
 */
class ScrollQuiescenceTest {

    @Test
    fun `滚动开始即活跃_停止即静止`() {
        ScrollQuiescence.onScrollStateChanged(scrolling = true)
        assertFalse(ScrollQuiescence.isQuiescent)
        ScrollQuiescence.onScrollStateChanged(scrolling = false)
        assertTrue(ScrollQuiescence.isQuiescent)
    }

    @Test
    fun `重复同值信号稳定`() {
        ScrollQuiescence.onScrollStateChanged(scrolling = true)
        ScrollQuiescence.onScrollStateChanged(scrolling = true)
        assertFalse(ScrollQuiescence.isQuiescent)
        ScrollQuiescence.onScrollStateChanged(scrolling = false)
        assertTrue(ScrollQuiescence.isQuiescent)
    }

    @Test
    fun `旧缝StreamingScrollHold委托一致`() {
        // 迁移期兼容缝：旧消费者（pilot LaunchedEffect 键 / ChatScreen 冻结门控）
        // 经 StreamingScrollHold.holding 读到的值必须与单点信号恒一致。
        ScrollQuiescence.onScrollStateChanged(scrolling = true)
        assertEquals(!ScrollQuiescence.isQuiescent, StreamingScrollHold.holding)
        ScrollQuiescence.onScrollStateChanged(scrolling = false)
        assertEquals(!ScrollQuiescence.isQuiescent, StreamingScrollHold.holding)
    }
}
