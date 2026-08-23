package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-08-16 根治：死代码根因 —— 发送后滚底 ForceScrollExecutor 单测。
 *
 * 覆盖三条主路径：
 * ① tick 后 totalItemsCount 增长 → 执行 requestScrollToItem(0)
 * ② fling 惯性中（isScrollInProgress true→false）→ 等待停止后仍滚
 * ③ 5s 超时（count 永不增长）→ 超时后仍滚 + 超时日志
 * 以及滚后校验路径：短暂未到位收敛（不重滚）与持续未到位（重滚一次）。
 *
 * 说明：LazyListState 依赖快照/布局体系难以干净 mock，故逻辑已抽为
 * [ForceScrollExecutor] + [ScrollListGate]（抽函数优于 hack mock），
 * 本测试用 State 驱动的 Fake 门面验证 —— snapshotFlow 可真实订阅其变化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {

    /** State 驱动的滚动门面 Fake：snapshotFlow 能真实订阅其属性变化。 */
    private class FakeGate(
        initialCount: Int = 10,
        initialIndex: Int = 3,
        initialOffset: Int = 0,
    ) : ScrollListGate {
        var count by mutableIntStateOf(initialCount)
        var scrolling by mutableStateOf(false)
        var index by mutableIntStateOf(initialIndex)
        var offset by mutableIntStateOf(initialOffset)

        /** 每次 requestScrollToItem 调用时 isScrollInProgress 的快照（断言时序）。 */
        val progressFlagAtScrollCalls = mutableListOf<Boolean>()

        /** 滚动后落点模拟：默认立即到位（index=0/offset=0，即 reverseLayout 底部）。 */
        var applyScroll: () -> Unit = {
            index = 0
            offset = 0
        }

        override val totalItemsCount: Int get() = count
        override val isScrollInProgress: Boolean get() = scrolling
        override val firstVisibleItemIndex: Int get() = index
        override val firstVisibleItemScrollOffset: Int get() = offset

        override fun requestScrollToItem(index: Int) {
            progressFlagAtScrollCalls.add(scrolling)
            applyScroll()
        }
    }

    private fun executor(gate: FakeGate, logs: MutableList<String>) = ForceScrollExecutor(
        gate = gate,
        onGrowthTimeout = { logs.add(it) },
        waitOneFrame = { }, // JVM 单测无 MonotonicFrameClock，注入空帧等待
    )

    /**
     * 显式通知全局快照应用。JVM 单测无 AndroidUiDispatcher/GlobalSnapshotManager，
     * MutableState 全局写入不会自动触发 apply observer（snapshotFlow 依赖其重发）；
     * 生产环境由帧调度保证，无需此调用。
     */
    private fun applySnapshot() = Snapshot.sendApplyNotifications()

    // ============ ① 消息增长 → 滚底 ============

    @Test
    fun `execute scrolls to bottom when totalItemsCount grows`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        val logs = mutableListOf<String>()
        launch { delay(100); gate.count = 11; applySnapshot() } // 模拟 POST 往返 + SSE 回显后消息入列

        executor(gate, logs).execute()

        assertEquals("消息增长后应恰好滚动一次", 1, gate.progressFlagAtScrollCalls.size)
        assertEquals("增长路径不应记超时日志", 0, logs.size)
        assertEquals("应锚定到 index 0（底部）", 0, gate.index)
    }

    // ============ ② fling 中到达 → 等待停止后仍滚 ============

    @Test
    fun `execute waits for fling to finish before scrolling`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        gate.scrolling = true // 用户 fling 惯性进行中
        launch { delay(100); gate.count = 11; applySnapshot() }
        launch { delay(300); gate.scrolling = false; applySnapshot() }

        val logs = mutableListOf<String>()
        executor(gate, logs).execute()

        assertEquals(1, gate.progressFlagAtScrollCalls.size)
        assertTrue(
            "滚动必须发生在 fling 结束之后（不得在 isScrollInProgress=true 时抢滚）",
            !gate.progressFlagAtScrollCalls.single(),
        )
        assertEquals(0, logs.size)
    }

    // ============ ③ 5s 超时兜底 → 仍滚 + 日志 ============

    @Test
    fun `execute scrolls anyway with log after growth timeout`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        val logs = mutableListOf<String>()
        // 不安排 count 增长：模拟发送失败/无 SSE 回显

        executor(gate, logs).execute()

        assertEquals("超时兜底后仍应滚动", 1, gate.progressFlagAtScrollCalls.size)
        assertEquals("超时路径应记一条日志", 1, logs.size)
    }

    // ============ ④ 滚后短暂未到位（补偿收敛）→ 不重滚 ============

    @Test
    fun `execute skips retry when position converges after compensation`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        // 滚后位置暂未收敛（模拟流式 turn 高度补偿），随后收敛
        gate.applyScroll = {
            gate.index = 0
            gate.offset = 250
        }
        launch { delay(100); gate.count = 11; applySnapshot() }
        launch { delay(150); gate.offset = 0; applySnapshot() }

        val logs = mutableListOf<String>()
        executor(gate, logs).execute()

        assertEquals("补偿收敛期内不应重滚（避免视口抖动）", 1, gate.progressFlagAtScrollCalls.size)
        assertEquals(0, logs.size)
    }

    // ============ ⑤ 滚后持续未到位 → 重滚一次 ============

    @Test
    fun `execute retries scroll once when never reaches bottom`() = runTest {
        val gate = FakeGate(initialCount = 10, initialIndex = 2)
        gate.applyScroll = { // 位置永不收敛（模拟补偿后仍偏离底部）
            gate.index = 0
            gate.offset = 250
        }
        launch { delay(100); gate.count = 11; applySnapshot() }

        val logs = mutableListOf<String>()
        executor(gate, logs).execute()

        assertEquals("校验超时后应重滚一次（共两次）", 2, gate.progressFlagAtScrollCalls.size)
        assertEquals("增长已发生，不应记超时日志", 0, logs.size)
    }
}
