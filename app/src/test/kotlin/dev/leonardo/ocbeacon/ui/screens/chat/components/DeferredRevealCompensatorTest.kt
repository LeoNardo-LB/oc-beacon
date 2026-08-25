package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.*
import org.junit.Test

/**
 * #222 修二强化：延迟揭示状态机单测（真·渲染前语义）。
 *
 * 核心不变量：任何向 LazyList 上报的高度增量，其对应的 scrollToBeConsumed
 * 注入都发生在**上一遍**（消费先于揭示）——未补偿几何永不被放置。
 */
class DeferredRevealCompensatorTest {

    @Test
    fun `cold start reports full height without injection`() {
        val c = DeferredRevealCompensator()
        val d = c.onMeasure(realHeight = 1000, shouldCompensate = true)
        assertEquals(1000, d.reportHeight)
        assertEquals(0, d.injectDelta)
        assertFalse(d.poke)
        assertEquals(1000, c.reportedHeight)
    }

    @Test
    fun `single growth defers then reveals with consumption-first ordering`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        // 增长遍：不上报新高度（保持 1000），注入 100，poke
        val defer = c.onMeasure(1100, true)
        assertEquals(1000, defer.reportHeight)
        assertEquals(100, defer.injectDelta)
        assertTrue(defer.poke)
        assertEquals(100, c.injectedPending)
        assertEquals(1000, c.reportedHeight)
        // 揭示遍（遍首已消费 100）：上报 1000+100，无新注入
        val reveal = c.onMeasure(1100, true)
        assertEquals(1100, reveal.reportHeight)
        assertEquals(0, reveal.injectDelta)
        assertFalse(reveal.poke)
        assertEquals(1100, c.reportedHeight)
        assertEquals(0, c.injectedPending)
    }

    @Test
    fun `continuous growth chains reveal of previous delta each pass`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        // 遍1：1000→1150，注入 150，上报 1000
        val d1 = c.onMeasure(1150, true)
        assertEquals(1000, d1.reportHeight); assertEquals(150, d1.injectDelta)
        // 遍2：又长到 1300。揭示 150（已消费），注入新增 150，上报 1150
        val d2 = c.onMeasure(1300, true)
        assertEquals(1150, d2.reportHeight)
        assertEquals(150, d2.injectDelta)
        assertEquals(300, c.injectedPending)
        // 遍3：稳定 1300。揭示全部，清欠
        val d3 = c.onMeasure(1300, true)
        assertEquals(1300, d3.reportHeight); assertEquals(0, d3.injectDelta)
        assertEquals(1300, c.reportedHeight); assertEquals(0, c.injectedPending)
    }

    @Test
    fun `no compensate context reveals fully and clears pending`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        c.onMeasure(1100, true) // 延迟，pending=100
        // 用户回底：shouldCompensate=false → 全量揭示清欠
        val d = c.onMeasure(1100, false)
        assertEquals(1100, d.reportHeight)
        assertEquals(0, d.injectDelta)
        assertEquals(0, c.injectedPending)
    }

    @Test
    fun `shrink reveals immediately and resets`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        c.onMeasure(1100, true) // pending=100
        val d = c.onMeasure(900, true) // 收缩
        assertEquals(900, d.reportHeight)
        assertEquals(0, d.injectDelta)
        assertEquals(900, c.reportedHeight)
        assertEquals(0, c.injectedPending)
    }

    @Test
    fun `reset clears baseline for cold restart`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        c.onMeasure(1100, true)
        c.reset()
        assertEquals(0, c.reportedHeight)
        assertEquals(0, c.injectedPending)
        // 重启后首测 = 冷启动语义
        val d = c.onMeasure(800, true)
        assertEquals(800, d.reportHeight)
        assertEquals(0, d.injectDelta)
    }

    @Test
    fun `stable height pass is a no-op`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        val d1 = c.onMeasure(1000, true)
        assertEquals(1000, d1.reportHeight)
        assertEquals(0, d1.injectDelta)
        assertFalse(d1.poke)
    }
}
