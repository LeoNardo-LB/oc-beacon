package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #437 扣留尾部超龄状态机（spec §5 阶段 B 用例）。 */
class HeldTailAgingTest {

    @Test
    fun `扣留期内不可见`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held text", 40)
        t = 299
        s.update("held text more", 52)
        assertFalse(s.visible)
        assertEquals(-1, s.lockedHeightPx) // 不可见期间不锁高
    }

    @Test
    fun `超龄300ms后可见`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 300
        s.update("held", 40)
        assertTrue(s.visible)
        assertEquals(40, s.lockedHeightPx) // 可见即刻锁高
    }

    @Test
    fun `锁高500ms节流期内不刷新`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 300; s.update("held", 40)      // 可见+锁高 40
        t = 400; s.update("held+", 80)     // 节流期内：文本长高但不刷锁高
        assertEquals(40, s.lockedHeightPx)
        assertEquals(80, s.lastNaturalHeightPx) // 自然高度照常观测
    }

    @Test
    fun `节流到期锁高刷新`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 300; s.update("held", 40)
        t = 800; s.update("held++", 96)    // 500ms 已过 → 刷新
        assertEquals(96, s.lockedHeightPx)
    }

    @Test
    fun `扣留清空复位并重新计时`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 40)
        t = 300; s.update("held", 40)
        assertTrue(s.visible)
        s.update("", 0)                     // 全部毕业
        assertFalse(s.visible)
        assertEquals(-1, s.lockedHeightPx)
        // 再扣留：从新时刻重新计时（不沿用旧 heldSince）
        t = 500; s.update("new held", 30)
        assertFalse(s.visible)
        t = 801; s.update("new held", 30)  // 301ms ≥300 → 可见
        assertTrue(s.visible)
    }

    @Test
    fun `首扣留即超龄场景`() {
        // gate 首跑可能一次扣留大量内容（如整回复一个代码围栏）——
        // heldSince 取首次 update 时刻，首批内不可见
        var t = 10_000L
        val s = HeldTailAgingState(now = { t })
        s.update("```code...", 120)
        assertFalse(s.visible)
        t = 10_299; s.update("```code...", 120)
        assertFalse(s.visible)
        t = 10_310; s.update("```code...", 120)
        assertTrue(s.visible)
        assertEquals(120, s.lockedHeightPx)
    }

    @Test
    fun `无自然高度观测时不锁高`() {
        // 超龄轮询期 Text 尚未布局（natural=0）——防止可见瞬间锁 0 高度
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("held", 0)
        t = 300; s.update("held", 0)
        assertTrue(s.visible)
        assertEquals(-1, s.lockedHeightPx)
        // 首次真实布局观测到达 → 立即锁高
        s.update("held", 44)
        assertEquals(44, s.lockedHeightPx)
    }

    @Test
    fun `超龄首亮量子上限`() {
        // #437 验收九轮：中继停顿冲刷 → 扣留区瞬时积压数千 px，首亮不得一次落地
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("停顿冲刷的大段文本", 7378)
        t = 300; s.update("停顿冲刷的大段文本", 7378)
        assertTrue(s.visible)
        assertEquals(HeldTailAgingState.ONSET_REVEAL_CAP_PX, s.lockedHeightPx)
    }

    @Test
    fun `首亮后按步进铺开至自然高`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("burst", 7378)
        t = 300; s.update("burst", 7378)   // 首亮 800
        t = 800; s.update("burst", 7378)   // 步进 800+1600
        assertEquals(2400, s.lockedHeightPx)
        t = 1300; s.update("burst", 7378)
        assertEquals(4000, s.lockedHeightPx)
        t = 1800; s.update("burst", 7378)
        assertEquals(5600, s.lockedHeightPx)
        t = 2300; s.update("burst", 7378)
        assertEquals(7200, s.lockedHeightPx)
        t = 2800; s.update("burst", 7378)
        assertEquals(7378, s.lockedHeightPx) // 收敛至自然高（不超过）
    }

    @Test
    fun `小尾巴不受量子上限影响`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("小尾巴", 96)
        t = 300; s.update("小尾巴+", 150)
        assertEquals(150, s.lockedHeightPx)
    }

    @Test
    fun `步进随自然高收缩`() {
        // 毕业使扣留变短：自然高回落后锁高不得高于自然高
        var t = 0L
        val s = HeldTailAgingState(now = { t })
        s.update("burst", 7378)
        t = 300; s.update("burst", 7378)   // 首亮 800
        t = 800; s.update("short", 300)    // 大批毕业 → 自然高 300
        assertEquals(300, s.lockedHeightPx)
    }

    @Test
    fun `自定义阈值注入生效`() {
        var t = 0L
        val s = HeldTailAgingState(now = { t }, revealAfterMs = 100, heightRefreshMs = 50)
        s.update("held", 10)
        t = 100; s.update("held", 10)
        assertTrue(s.visible)
        t = 150; s.update("held+", 20)   // 距上次锁高 50ms 到期
        assertEquals(20, s.lockedHeightPx)
    }
}
