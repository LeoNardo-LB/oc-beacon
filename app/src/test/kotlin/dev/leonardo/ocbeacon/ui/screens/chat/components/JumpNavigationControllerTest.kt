package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳转定位状态机单测（架构评审 Q7——状态转移 + 纯函数）。
 * 覆盖本会话反复出错的计算逻辑：desired/gap 公式、状态转移路径。
 */
class JumpNavigationControllerTest {

    // ============ 纯函数：computeDesiredOffset ============

    @Test
    fun desiredOffset_顶边贴视口顶_含paddingTop修正() {
        // vh=1808, item=331, paddingTop=21 → 1456（实测拟合值）
        assertEquals(1456f, computeDesiredOffset(1808f, 331f, 21f), 0.01f)
    }

    @Test
    fun desiredOffset_目标越大偏移越小() {
        val small = computeDesiredOffset(1808f, 200f, 21f)
        val large = computeDesiredOffset(1808f, 600f, 21f)
        assertTrue("目标越高 desired 越小", large < small)
    }

    @Test
    fun desiredOffset_paddingTop变化跟随() {
        // paddingTop 变化（contentPadding 调整/不同密度）→ desired 跟随
        assertEquals(1435f, computeDesiredOffset(1808f, 331f, 42f), 0.01f)
        assertEquals(1477f, computeDesiredOffset(1808f, 331f, 0f), 0.01f)
    }

    // ============ 纯函数：computeGap ============

    @Test
    fun gap_贴顶为零() {
        // offset+size = vh - paddingTop → gap=0（顶边贴视口顶）
        assertEquals(0f, computeGap(1456, 331, 1808f, 21f), 0.01f)
    }

    @Test
    fun gap_超出为负() {
        // reverse 坐标：顶边滚动坐标 < 视口顶 → 顶边在视口上方（超出）→ gap < 0
        assertEquals(-117f, computeGap(1456, 214, 1808f, 21f), 0.01f)
    }

    @Test
    fun gap_空隙为正() {
        // 顶边在视口下方（有空隙）→ gap > 0
        assertEquals(117f, computeGap(1573, 331, 1808f, 21f), 0.01f)
    }

    // ============ 状态转移：正常路径 ============

    @Test
    fun transition_正常链路Idle到Displayed() {
        var phase: JumpPhase = JumpPhase.Idle
        phase = jumpTransition(phase, JumpEvent.PrepareStarted)
        assertTrue(phase is JumpPhase.Preparing)
        phase = jumpTransition(phase, JumpEvent.ParsedReady)
        assertTrue(phase is JumpPhase.Measuring)
        phase = jumpTransition(phase, JumpEvent.MeasureReady)
        assertTrue(phase is JumpPhase.Settling)
        phase = jumpTransition(phase, JumpEvent.Settled)
        assertTrue(phase is JumpPhase.Displayed)
    }

    @Test
    fun transition_保持目标msgId() {
        var phase: JumpPhase = JumpPhase.Preparing("msg_123")
        phase = jumpTransition(phase, JumpEvent.ParsedReady)
        assertEquals("msg_123", (phase as JumpPhase.Measuring).msgId)
        phase = jumpTransition(phase, JumpEvent.MeasureReady)
        assertEquals("msg_123", (phase as JumpPhase.Settling).msgId)
        phase = jumpTransition(phase, JumpEvent.Settled)
        assertEquals("msg_123", (phase as JumpPhase.Displayed).msgId)
    }

    // ============ 状态转移：失败路径 ============

    @Test
    fun transition_预解析超时Failed() {
        val phase = jumpTransition(JumpPhase.Preparing("m"), JumpEvent.TimedOut("parsing"))
        assertTrue(phase is JumpPhase.Failed)
        assertTrue((phase as JumpPhase.Failed).reason.contains("预解析超时"))
    }

    @Test
    fun transition_测量超时Failed() {
        val phase = jumpTransition(JumpPhase.Measuring("m"), JumpEvent.TimedOut("measuring"))
        assertTrue(phase is JumpPhase.Failed)
    }

    @Test
    fun transition_收敛超时Failed() {
        val phase = jumpTransition(JumpPhase.Settling("m"), JumpEvent.TimedOut("settling"))
        assertTrue(phase is JumpPhase.Failed)
    }

    @Test
    fun transition_Abort回Idle() {
        val phase = jumpTransition(JumpPhase.Displayed("m"), JumpEvent.Abort)
        assertTrue(phase is JumpPhase.Idle)
    }

    // ============ 状态转移：非法事件不破坏状态 ============

    @Test
    fun transition_Idle收到ParsedReady不转移() {
        val phase = jumpTransition(JumpPhase.Idle, JumpEvent.ParsedReady)
        assertTrue(phase is JumpPhase.Idle)
    }

    @Test
    fun transition_Preparing收到Settled不转移() {
        val phase = jumpTransition(JumpPhase.Preparing("m"), JumpEvent.Settled)
        assertTrue(phase is JumpPhase.Preparing)
    }

    @Test
    fun transition_Displayed收到MeasureReady不转移() {
        val phase = jumpTransition(JumpPhase.Displayed("m"), JumpEvent.MeasureReady)
        assertTrue(phase is JumpPhase.Displayed)
    }
}
