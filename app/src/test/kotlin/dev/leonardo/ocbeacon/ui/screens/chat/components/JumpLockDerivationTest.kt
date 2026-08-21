package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #159 收口（2026-08-22）：jumpLockActive 派生锁单测——替代 ChatMessageList
 * 手工镜像（4 写点任一遗漏即竞态；loadAround 失败路径漏复位已实证锁永久卡死）。
 *
 * 经 phaseFlow 注入直接驱动相位（不触发执行器——listState 全程闲置），
 * 虚拟时钟验证终点 300ms 缓冲窗口与「缓冲期内新跳转取消解锁」的
 * collectLatest 语义（等价原 ChatMessageList 解锁 effect 键重启）。
 */
class JumpLockDerivationTest {

    private class Env(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) {
        val phaseFlow = MutableStateFlow<JumpPhase>(JumpPhase.Idle)
        val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler) + Job())
        val controller = JumpNavigationController(
            listState = LazyListState(),
            scope = scope,
            phaseFlow = phaseFlow,
            resolveLazyIndex = { null },
        )
    }

    @Test
    fun `初始 Idle 锁为 false`() = runTest {
        val env = Env(testScheduler)
        try {
            assertFalse(env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `跳转进行中锁定（Preparing Measuring Settling 全相）`() = runTest {
        val env = Env(testScheduler)
        try {
            env.phaseFlow.value = JumpPhase.Preparing("m")
            assertTrue(env.controller.jumpLockActive.value)
            env.phaseFlow.value = JumpPhase.Measuring("m")
            assertTrue(env.controller.jumpLockActive.value)
            env.phaseFlow.value = JumpPhase.Settling("m")
            assertTrue(env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `Displayed 终点后 300ms 内保持锁定随后解锁`() = runTest {
        val env = Env(testScheduler)
        try {
            env.phaseFlow.value = JumpPhase.Preparing("m")
            env.phaseFlow.value = JumpPhase.Displayed("m")
            advanceTimeBy(JUMP_UNLOCK_DELAY_MS - 1)
            assertTrue("终点缓冲内应锁定", env.controller.jumpLockActive.value)
            // advanceTimeBy 不执行目标时刻恰好到期的任务（kotlinx 语义）——多走 1ms
            advanceTimeBy(2)
            assertFalse("缓冲期满应解锁", env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `Failed 终点同样走缓冲解锁`() = runTest {
        val env = Env(testScheduler)
        try {
            env.phaseFlow.value = JumpPhase.Preparing("m")
            env.phaseFlow.value = JumpPhase.Failed("m", "测试超时")
            advanceTimeBy(JUMP_UNLOCK_DELAY_MS / 2)
            assertTrue(env.controller.jumpLockActive.value)
            advanceTimeBy(JUMP_UNLOCK_DELAY_MS)
            assertFalse(env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `缓冲期内新跳转到来取消解锁并继续锁定`() = runTest {
        val env = Env(testScheduler)
        try {
            env.phaseFlow.value = JumpPhase.Preparing("a")
            env.phaseFlow.value = JumpPhase.Failed("a", "超时")
            advanceTimeBy(JUMP_UNLOCK_DELAY_MS - 50)
            // 旧缓冲还差 50ms——新跳转插入：collectLatest 取消未完成的解锁延迟
            env.phaseFlow.value = JumpPhase.Preparing("b")
            advanceTimeBy(100) // 越过旧解锁点
            assertTrue("新跳转进行中应锁定", env.controller.jumpLockActive.value)
            env.phaseFlow.value = JumpPhase.Displayed("b")
            advanceTimeBy(JUMP_UNLOCK_DELAY_MS + 1)
            assertFalse(env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `markJumpPending 异步窗口立即锁定且 phase 保持 Idle`() = runTest {
        val env = Env(testScheduler)
        try {
            env.controller.markJumpPending()
            assertTrue("异步窗口（loadAround 期间）应同步锁定", env.controller.jumpLockActive.value)
            assertTrue("phase 不经执行器不应变化", env.phaseFlow.value is JumpPhase.Idle)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `异步定位失败解锁（回归：旧镜像此路径漏复位永久卡死）`() = runTest {
        val env = Env(testScheduler)
        try {
            env.controller.markJumpPending()
            env.controller.clearPendingJumpLock()
            assertFalse("失败路径必须解锁", env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }

    @Test
    fun `失败清理与活跃跳转交错时是 no-op（锁归进行中的跳转）`() = runTest {
        val env = Env(testScheduler)
        try {
            env.controller.markJumpPending()
            env.phaseFlow.value = JumpPhase.Preparing("real") // 用户已点可跳目标
            env.controller.clearPendingJumpLock()
            assertTrue("活跃跳转期间失败清理不得解锁", env.controller.jumpLockActive.value)
        } finally {
            env.scope.cancel()
        }
    }
}
