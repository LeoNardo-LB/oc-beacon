package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #437 非前缀风暴探测（spec §5 阶段 D）。 */
class FlapDetectorTest {

    @Test
    fun `零星非前缀不触发抑制`() {
        var t = 0L
        val f = FlapDetector(now = { t })
        assertFalse(f.onNonPrefix())   // 0ms
        t = 600; assertFalse(f.onNonPrefix()) // 窗口内 2 次 = 上限，未超
        assertEquals(0, f.stormCount)
    }

    @Test
    fun `窗口内超2次触发抑制`() {
        var t = 0L
        val f = FlapDetector(now = { t })
        f.onNonPrefix(); t = 100; f.onNonPrefix()
        t = 200
        assertTrue(f.onNonPrefix())    // 第 3 次（窗口内）→ 抑制
        assertEquals(1, f.stormCount)
    }

    @Test
    fun `抑制期内即使窗口未满也持续抑制`() {
        var t = 0L
        val f = FlapDetector(now = { t })
        f.onNonPrefix(); t = 100; f.onNonPrefix(); t = 200; f.onNonPrefix() // 抑制开启，suppressedUntil=1200
        t = 1300  // 窗口 [300,1300] 内仅 1 个事件（200 的已滑出？t-200=1100>1000 滑出，300 的也滑出）
        // 窗口空但 suppressedUntil=1200 < 1300 已过期 → 不抑制
        assertFalse(f.onNonPrefix())
    }

    @Test
    fun `风暴停止后自动解除并恢复正常计数`() {
        var t = 0L
        val f = FlapDetector(now = { t })
        t = 0; f.onNonPrefix(); t = 100; f.onNonPrefix(); t = 200; f.onNonPrefix()
        assertTrue(f.stormCount == 1)
        t = 1500  // 风暴停止（窗口滑空，抑制过期）
        assertFalse(f.onNonPrefix())
        t = 1600; assertFalse(f.onNonPrefix())
        t = 1700; assertTrue(f.onNonPrefix())  // 新一轮 3 次 → 再抑制
        assertEquals(2, f.stormCount)
    }

    @Test
    fun `持续风暴维持抑制（事件持续入窗）`() {
        var t = 0L
        val f = FlapDetector(now = { t })
        for (i in 0 until 10) {
            t = i * 100L
            if (i < 2) assertFalse(f.onNonPrefix()) // 前两次未超限
            else assertTrue(f.onNonPrefix())        // 第 3 次起持续抑制
        }
        assertEquals(8, f.stormCount) // 第3次起每次都进入抑制分支
    }
}
