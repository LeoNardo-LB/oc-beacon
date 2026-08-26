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

    // ============ #239 二审：滚动保持（holdReveal）============
    // 场景：SSE 流式中用户 fling——滚动中增长必须「冻结」（不上报、不注入、
    // 不全揭示），静止后配对恢复。首版错走全揭示分支 = 增长无配对注入，
    // 内容被顶起（「fling 中随流输出上推」回归）。

    @Test
    fun `hold freezes growth - no report jump no injection`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        // 滚动中内容长到 1200：上报保持 1000、零注入（增长裁剪不可见）
        val d = c.onMeasure(1200, true, holdReveal = true)
        assertEquals(1000, d.reportHeight)
        assertEquals(0, d.injectDelta)
        assertFalse(d.poke)
        // 连续 hold 多遍：基准纹丝不动
        c.onMeasure(1350, true, holdReveal = true)
        val d2 = c.onMeasure(1500, true, holdReveal = true)
        assertEquals(1000, d2.reportHeight)
        assertEquals(0, d2.injectDelta)
    }

    @Test
    fun `hold reveals paired pending from pass before scroll started`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        c.onMeasure(1100, true) // 滚动开始前已注入 100（pending）
        // 滚动开始的第一遍：遍首已消费该 100 → 配对揭示 1100，但新增长(→1300)冻结
        val d = c.onMeasure(1300, true, holdReveal = true)
        assertEquals(1100, d.reportHeight)
        assertEquals(0, d.injectDelta)
        assertEquals(1100, c.reportedHeight)
        assertEquals(0, c.injectedPending)
        // 后续 hold 遍：冻结在 1100
        val d2 = c.onMeasure(1400, true, holdReveal = true)
        assertEquals(1100, d2.reportHeight)
    }

    @Test
    fun `after scroll ends accumulated growth resumes via inject-reveal pair`() {
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        // 滚动中累计增长 1000→1600，全部冻结
        c.onMeasure(1300, true, holdReveal = true)
        c.onMeasure(1600, true, holdReveal = true)
        // 滚动结束：恢复遍——注入累计 600（1600−冻结基准1000），上报保持 1000
        //（配对语义：消费先于揭示）
        val resume = c.onMeasure(1600, true, holdReveal = false)
        assertEquals(1000, resume.reportHeight)
        assertEquals(600, resume.injectDelta)
        assertTrue(resume.poke)
        // 揭示遍：配对完成，基准对齐真实高度
        val reveal = c.onMeasure(1600, true, holdReveal = false)
        assertEquals(1600, reveal.reportHeight)
        assertEquals(0, reveal.injectDelta)
        assertEquals(0, c.injectedPending)
    }

    @Test
    fun `hold at bottom settles with full reveal - no pairing residue`() {
        // 贴底态下滚动结束（isScrollInProgress 下降沿）：shouldCompensate=false
        // 走全揭示清欠——冻结的增量一次性呈现（贴底看最新的正确行为）
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        c.onMeasure(1400, true, holdReveal = true)
        val settle = c.onMeasure(1400, false, holdReveal = false)
        assertEquals(1400, settle.reportHeight)
        assertEquals(0, settle.injectDelta)
        assertEquals(0, c.injectedPending)
    }

    @Test
    fun `hold takes precedence regardless of shouldCompensate flag`() {
        // hold 分支在 shouldCompensate 判定之前返回——两条旗标组合下行为一致
        val c = DeferredRevealCompensator()
        c.onMeasure(1000, true)
        val dFalse = c.onMeasure(1300, false, holdReveal = true)
        assertEquals(1000, dFalse.reportHeight)
        assertEquals(0, dFalse.injectDelta)
    }
}
