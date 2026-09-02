package dev.leonardo.ocbeacon.ui.screens.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #301：autoScroll 锚定去抖判定单测——「底部上滑被高频拉回」根因是两环自持循环：
 * 拉底 → 贴底复真 → 瞬时再武装 → 守卫在拖动→fling 交接闪断帧同步开火再拉底。
 * 修复 = 再武装/守卫触发均要求「稳定非滚动 ≥ 去抖窗 + 条件复查」；collectLatest
 * 在新快照（滚动恢复）到达时取消未决去抖——本测试以 launch+cancel 模拟该语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoScrollArbiterTest {

    // ---------- 再武装（rearmWhenSettledAtBottom） ----------

    @Test
    fun `rearm fires after stable at-bottom window`() = runTest {
        var scrolling = false
        var atBottom = true
        var armed = false
        val job = launch {
            AutoScrollArbiter.rearmWhenSettledAtBottom(
                isScrolling = { scrolling },
                isAtBottom = { atBottom },
                rearm = { armed = true },
            )
        }
        advanceTimeBy(AutoScrollArbiter.ANCHOR_DEBOUNCE_MS - 1)
        assertFalse("去抖窗内不武装", armed)
        advanceTimeBy(1)
        job.join()
        assertTrue("稳定贴底超窗 → 武装", armed)
    }

    @Test
    fun `rearm cancelled when scrolling resumes within flicker window`() = runTest {
        var scrolling = false
        var atBottom = true
        var armed = false
        val job = launch {
            AutoScrollArbiter.rearmWhenSettledAtBottom(
                isScrolling = { scrolling },
                isAtBottom = { atBottom },
                rearm = { armed = true },
            )
        }
        // 手势交接闪断：去抖窗内滚动恢复（collectLatest 新快照取消未决去抖）
        advanceTimeBy(AutoScrollArbiter.ANCHOR_DEBOUNCE_MS / 2)
        scrolling = true
        job.cancel() // collectLatest 语义
        advanceTimeBy(AutoScrollArbiter.ANCHOR_DEBOUNCE_MS * 2)
        assertFalse("闪断帧不武装（拉底循环上半环断开）", armed)
    }

    @Test
    fun `rearm skipped when user scrolled away during window`() = runTest {
        var scrolling = false
        var atBottom = true
        var armed = false
        val job = launch {
            AutoScrollArbiter.rearmWhenSettledAtBottom(
                isScrolling = { scrolling },
                isAtBottom = { atBottom },
                rearm = { armed = true },
            )
        }
        // 去抖窗内离开贴底区（无新快照取消——复查路径兜底）
        advanceTimeBy(AutoScrollArbiter.ANCHOR_DEBOUNCE_MS / 2)
        atBottom = false
        advanceTimeBy(AutoScrollArbiter.ANCHOR_DEBOUNCE_MS)
        job.join()
        assertFalse("复查不贴底 → 不武装", armed)
    }

    // ---------- 守卫（reanchorWhenSettledOffBottom） ----------

    @Test
    fun `guard fires after stable off-bottom drift`() = runTest {
        var scrolling = false
        var autoOn = true
        var atBottom = false
        var fired = false
        val job = launch {
            AutoScrollArbiter.reanchorWhenSettledOffBottom(
                isScrolling = { scrolling },
                autoScrollOn = { autoOn },
                isAtBottom = { atBottom },
                jumpLockActive = { false },
                reanchor = { fired = true },
            )
        }
        advanceTimeBy(AutoScrollArbiter.GUARD_DEBOUNCE_MS - 1)
        assertFalse(fired)
        advanceTimeBy(1)
        job.join()
        assertTrue("稳定离底漂移（守卫本职）→ 重锚", fired)
    }

    @Test
    fun `guard cancelled on gesture-handoff flicker`() = runTest {
        var scrolling = false
        var autoOn = true
        var atBottom = false
        var fired = false
        val job = launch {
            AutoScrollArbiter.reanchorWhenSettledOffBottom(
                isScrolling = { scrolling },
                autoScrollOn = { autoOn },
                isAtBottom = { atBottom },
                jumpLockActive = { false },
                reanchor = { fired = true },
            )
        }
        // 拖动→fling 交接闪断：窗内滚动恢复 → 取消（collectLatest 语义）
        advanceTimeBy(AutoScrollArbiter.GUARD_DEBOUNCE_MS / 2)
        scrolling = true
        job.cancel()
        advanceTimeBy(AutoScrollArbiter.GUARD_DEBOUNCE_MS * 2)
        assertFalse("闪断帧不点火（拉底循环下半环断开）", fired)
    }

    @Test
    fun `guard recheck honours autoScroll off and jump lock`() = runTest {
        // autoScroll 窗内被关（用户滚动）
        run {
            var scrolling = false
            var autoOn = true
            var fired = false
            val job = launch {
                AutoScrollArbiter.reanchorWhenSettledOffBottom(
                    isScrolling = { scrolling },
                    autoScrollOn = { autoOn },
                    isAtBottom = { false },
                    jumpLockActive = { false },
                    reanchor = { fired = true },
                )
            }
            advanceTimeBy(100)
            autoOn = false
            advanceTimeBy(AutoScrollArbiter.GUARD_DEBOUNCE_MS)
            job.join()
            assertFalse("用户已撤跟随 → 不重锚", fired)
        }
        // 跳转锁活跃
        run {
            var fired = false
            val job = launch {
                AutoScrollArbiter.reanchorWhenSettledOffBottom(
                    isScrolling = { false },
                    autoScrollOn = { true },
                    isAtBottom = { false },
                    jumpLockActive = { true },
                    reanchor = { fired = true },
                )
            }
            advanceTimeBy(AutoScrollArbiter.GUARD_DEBOUNCE_MS * 2)
            job.join()
            assertFalse("跳转锁活跃 → 不重锚", fired)
        }
    }
}
