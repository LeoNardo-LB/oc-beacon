package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #533（2026-10-08）：展开卡终高进程店 + 冷抬底欠测桥接——纯逻辑族。
 *
 * 定罪（5h soak 05:32:55 三帧）：SliceHost 槽位出窗即弃 → 槽内 finalHCache
 * （rememberSaveable）随槽位销毁 → 展开卡重入窗盒瞬态欠测 → 与片高账本稳态
 * 落差 = item −H 塌缩脉冲（86ms-83s 回长族）。
 */
class CardExpandFinalHStoreTest {

    @After
    fun tearDown() = CardExpandFinalHStore.clearForTest()

    @Test
    fun `record and get roundtrip`() {
        CardExpandFinalHStore.record("k1", 1080, 6144)
        assertEquals(6144, CardExpandFinalHStore.get("k1", 1080))
    }

    @Test
    fun `width keying isolates entries`() {
        CardExpandFinalHStore.record("k1", 1080, 6144)
        assertNull(CardExpandFinalHStore.get("k1", 720))
        CardExpandFinalHStore.record("k1", 720, 4000)
        assertEquals(6144, CardExpandFinalHStore.get("k1", 1080))
        assertEquals(4000, CardExpandFinalHStore.get("k1", 720))
    }

    @Test
    fun `551 事件时刻直写 - 后写覆盖先写（settle 值即新真相）`() {
        // #551 高度铁律：record 为 settle/落地实测的直写通道，max 棘轮退役
        CardExpandFinalHStore.record("k1", 1080, 6144)
        CardExpandFinalHStore.record("k1", 1080, 63)
        assertEquals(63, CardExpandFinalHStore.get("k1", 1080))
    }

    @Test
    fun `non-positive rejected`() {
        CardExpandFinalHStore.record("k1", 1080, 0)
        CardExpandFinalHStore.record("k1", 1080, -5)
        assertNull(CardExpandFinalHStore.get("k1", 1080))
    }
}

class CardExpandFloorBridgeTest {

    private class FakeClock(var now: Long)
    private fun bridge(c: FakeClock) = CardExpandFloorBridge { c.now }

    @Test
    fun `cold window delegates to classic eligibility`() {
        val c = FakeClock(0)
        val b = bridge(c)
        assertTrue(b.holds(fraction = 1f, animating = false, measureCount = 1, measured = 0, floor = 6144))
        assertTrue(b.holds(fraction = 1f, animating = false, measureCount = 2, measured = 51, floor = 6144))
    }

    @Test
    fun `under-measure landing holds floor beyond cold window`() {
        val c = FakeClock(0)
        val b = bridge(c)
        // 重入窗落地期：measured 恒低于 floor（分段/异步在途），桥接持续持有
        var t = 0L
        repeat(50) { // 50 遍欠测,期间每 100ms 变化一次(分段落地重置计时)
            t += 100_000_000L; c.now = t
            assertTrue(b.holds(1f, false, 40, 63 + it * 10, 6144))
        }
    }

    @Test
    fun `stable below floor releases after timeout`() {
        val c = FakeClock(0)
        val b = bridge(c)
        assertTrue(b.holds(1f, false, 40, 63, 6144))
        c.now = 1_000_000_000L
        assertTrue(b.holds(1f, false, 41, 63, 6144)) // 1s 静止仍在窗内
        c.now = 1_600_000_000L
        assertFalse(b.holds(1f, false, 42, 63, 6144)) // 1.5s 静止=合法收缩释放
    }

    @Test
    fun `released value change re-arms bridge`() {
        val c = FakeClock(0)
        val b = bridge(c)
        assertTrue(b.holds(1f, false, 40, 63, 6144))
        c.now = 1_600_000_000L
        assertFalse(b.holds(1f, false, 41, 63, 6144))
        // 内容再变化（迟到增量）：重新进入欠测域,桥接重挂
        c.now = 1_700_000_000L
        assertTrue(b.holds(1f, false, 42, 80, 6144))
    }

    @Test
    fun `gates keep episode and collapsed domains exclusive`() {
        val c = FakeClock(0)
        val b = bridge(c)
        assertFalse(b.holds(fraction = 0f, animating = false, measureCount = 9, measured = 0, floor = 6144))
        assertFalse(b.holds(fraction = 0.5f, animating = false, measureCount = 9, measured = 0, floor = 6144))
        assertFalse(b.holds(fraction = 1f, animating = true, measureCount = 1, measured = 0, floor = 6144))
    }

    @Test
    fun `measured at or above floor releases`() {
        val c = FakeClock(0)
        val b = bridge(c)
        assertFalse(b.holds(1f, false, 40, measured = 6144, floor = 6144))
        assertFalse(b.holds(1f, false, 40, measured = 6500, floor = 6144))
    }
}
