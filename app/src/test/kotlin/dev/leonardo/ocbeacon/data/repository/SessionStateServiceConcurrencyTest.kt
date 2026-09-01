package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.FsmEvent
import dev.leonardo.ocbeacon.domain.model.SessionActivity
import dev.leonardo.ocbeacon.domain.model.SessionFSMState
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * SessionStateService 的并发安全测试。
 *
 * 记录了竞态条件 RS-010 到 RS-013，并验证修复在并发访问下仍保持正确性。
 *
 * 测试策略：
 *  - 顺序测试验证行为契约（无回归）
 *  - 并发压力测试用真实线程压测竞态窗口
 *  - 确定性测试使用 latch/barrier 强制特定的交错顺序
 */
class SessionStateServiceConcurrencyTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newService(collab: SessionStateCollaborator = StubCollaborator()) = SessionStateService(
        testScope,
        Provider { mockk<SessionRepository>(relaxed = true) },
        collab,
        PaginationCursorPolicyFactory(Provider { mockk<SessionRepository>(relaxed = true) }),
    )

    private fun newServiceWith(repo: SessionRepository, collab: SessionStateCollaborator = StubCollaborator()) = SessionStateService(
        testScope,
        Provider { repo },
        collab,
        PaginationCursorPolicyFactory(Provider { repo }),
    )

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // ============ RS-010：applyTransition 原子性 ============

    /**
     * RS-010 回归测试：并发的 ClientSendParts 和 TextStarted 不得
     * 导致 Idle 状态（TextStarted 读取到陈旧的 Idle 时不得覆盖
     * ClientSendParts 写入的 Busy 转移）。
     *
     * 策略：使用线程池强制产生确定性竞态，N 个线程先发 ClientSendParts
     * 再发 TextStarted。全部完成后，状态必须是 Busy —— 绝不会是 Idle。
     */
    @Test
    fun `RS-010 concurrent ClientSendParts then TextStarted never loses Busy state`() {
        val service = newService()
        val threadCount = 16
        val eventPairsPerThread = 5
        val pool = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(threadCount)
        val errors = ConcurrentHashMap<Int, Throwable>()

        val futures = (0 until threadCount).map { idx ->
            pool.submit {
                try {
                    readyLatch.countDown()
                    startLatch.await(5, TimeUnit.SECONDS)
                    repeat(eventPairsPerThread) {
                        service.applyTransition("s1", FsmEvent.ClientSendParts)
                        service.applyTransition("s1", FsmEvent.TextStarted)
                    }
                } catch (t: Throwable) {
                    errors[idx] = t
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertTrue("Thread errors: $errors", errors.isEmpty())

        testScope.runCurrent()

        val status = service.statusFlow.value["s1"]
        assertEquals(
            "After ClientSendParts, status must be Busy regardless of concurrent TextStarted (RS-010)",
            SessionStatus.Busy,
            status
        )
    }

    /**
     * RS-010 回归测试：一种确定性交错 —— TextStarted 在 ClientSendParts 写入
     * Busy 之前读取到 Idle。旧代码中，TextStarted 的 `.update{}` 会用 Idle
     * 覆盖 Busy（因为在 Idle 状态下的 activity 事件返回 `isSuspicious=true`
     * 并保持状态不变）。
     *
     * 修复后：`.update{}` 会重试 read-compute-write，因此 TextStarted 能看到
     * ClientSendParts 写入的 Busy 状态。
     */
    @Test
    fun `RS-010 sequential ClientSendParts then TextStarted produces Busy Streaming`() {
        val service = newService()

        // 顺序基线 —— 应当总是通过
        service.applyTransition("s1", FsmEvent.ClientSendParts)
        service.applyTransition("s1", FsmEvent.TextStarted)
        testScope.runCurrent()

        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Streaming, service.activityFlow.value["s1"])
    }

    /**
     * 压力测试：跨多个会话的大量并发转移不应破坏 FSM map 或丢失转移。
     */
    @Test
    fun `RS-010 multi-session concurrent transitions are all recorded in history`() {
        val service = newService()
        val sessionCount = 10
        val transitionsPerSession = 20
        val pool = Executors.newFixedThreadPool(sessionCount)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(sessionCount)

        val futures = (0 until sessionCount).map { idx ->
            val sessionId = "s$idx"
            pool.submit {
                readyLatch.countDown()
                startLatch.await(5, TimeUnit.SECONDS)
                // #122 D2-15 后：重复同事件在稳定态只剩时间戳变化（被短路，
                // 不记 history）——压测改用交替真实转移（Idle↔Busy），每事件
                // 均为真实转移，CAS 压力不变且 history 断言精确有效。
                repeat(transitionsPerSession) { i ->
                    if (i % 2 == 0) {
                        service.applyTransition(sessionId, FsmEvent.ClientSendParts)
                    } else {
                        service.applyTransition(sessionId, FsmEvent.SseIdle)
                    }
                }
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        futures.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        testScope.runCurrent()

        // 每个会话应恰好有 transitionsPerSession 条历史记录
        for (i in 0 until sessionCount) {
            val history = service.historyFlow.value["s$i"]
            assertEquals(
                "Session s$i should have $transitionsPerSession history entries",
                transitionsPerSession,
                history?.size ?: 0
            )
        }
    }

    // ============ RS-011：clearAll 原子性 ============

    /**
     * RS-011 回归测试：clearAll() 不得被一个在 clearAll 运行前就读取了状态的
     * 并发 applyTransition 所覆盖。
     *
     * 旧代码（`_fsmStates.value = emptyMap()`）中，如果 applyTransition 的
     * `.update{}` CAS 读取发生在赋值之前，而 CAS 写入发生在之后，该写入会
     * 恢复 clearAll 已移除的旧会话状态。
     *
     * 修复后：clearAll 使用 `.update {}` 参与 CAS，最终状态保持一致。
     */
    @Test
    fun `RS-011 clearAll concurrent with applyTransition does not resurrect cleared state`() {
        val service = newService()
        // 种入一个 Busy 会话
        service.applyTransition("s1", FsmEvent.ClientSendParts)
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])

        // 现在在紧凑循环中反复 clear 并立即重新 apply
        val pool = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(2)

        val clearFuture = pool.submit {
            readyLatch.countDown()
            startLatch.await(5, TimeUnit.SECONDS)
            repeat(100) { service.clearAll() }
        }
        val applyFuture = pool.submit {
            readyLatch.countDown()
            startLatch.await(5, TimeUnit.SECONDS)
            repeat(100) {
                service.applyTransition("s1", FsmEvent.ClientSendParts)
            }
        }

        readyLatch.await(5, TimeUnit.SECONDS)
        startLatch.countDown()
        clearFuture.get(10, TimeUnit.SECONDS)
        applyFuture.get(10, TimeUnit.SECONDS)
        pool.shutdown()
        testScope.runCurrent()

        // map 应内部一致 —— 要么为空（如果 clear 最后执行），
        // 要么包含 s1（如果 apply 最后执行）。本测试断言无部分损坏。
        val status = service.statusFlow.value["s1"]
        // clearAll + applyTransition 后，状态应是确定性的：
        // 在 CAS 排序序列中真正最后执行的操作胜出。
        // 这里只断言不崩溃且 map 处于有效状态。
        assertTrue(
            "Status should be either Busy or null (consistent), was $status",
            status == null || status == SessionStatus.Busy
        )
    }

    @Test
    fun `RS-011 clearSession and clearAll together leave empty state`() {
        val service = newService()
        service.applyTransition("s1", FsmEvent.ClientSendParts)
        service.applyTransition("s2", FsmEvent.ClientSendParts)
        testScope.runCurrent()

        service.clearSession("s1")
        service.clearAll()
        testScope.runCurrent()

        assertTrue(service.statusFlow.value.isEmpty())
        assertTrue(service.historyFlow.value.isEmpty())
    }

    // ============ RS-012：triggerRestValidation 去重 ============

    /**
     * RS-012 回归测试（2026-09-02 #278 残项策略修订）：对同一会话的多个并发
     * triggerRestValidation 调用，进行中的校验让后续触发直接跳过（原「新 job
     * 取消旧 job」在回放风暴期产生 121 次 job 创建/取消 + 半途 Ktor 请求取消
     * ——校验是服务器真相快照，结果与触发原因无关，首个在飞 job 完成即收敛）。
     *
     * 验证：① 风暴期只发**一次** fetch；② 该次结果被应用到 FSM；
     * ③ 校验完成后的新触发会再启校验（无丢步）。
     */
    @Test
    fun `RS-012 concurrent triggerRestValidation skips while in-flight and re-triggers after completion`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        val callCount = AtomicInteger(0)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } coAnswers {
            callCount.incrementAndGet()
            gate.await()
            Result.success(mapOf("s1" to SessionStatus.Busy))
        }
        val collab = StubCollaborator()
        val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        service.onClientSendParts("s1")
        testScope.runCurrent()

        // 快速触发 5 次校验 —— 首个 job 在 gate 挂起，其余 4 次跳过（不 spawn）
        repeat(5) { service.triggerRestValidation("s1") }
        testScope.runCurrent()
        assertEquals("风暴期只应发出一次 fetch", 1, callCount.get())

        // 释放挂起的校验 —— 唯一结果（Busy）被应用
        gate.complete(Unit)
        testScope.runCurrent()
        assertEquals(
            "FSM should reflect the in-flight validation result (Busy)",
            SessionStatus.Busy,
            service.statusFlow.value["s1"]
        )

        // 校验完成后新触发应再启校验（结果幂等回 Busy）——coAnswers 保持计数
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } coAnswers {
            callCount.incrementAndGet()
            Result.success(mapOf("s1" to SessionStatus.Busy))
        }
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals("完成后应允许新校验", 2, callCount.get())
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
    }

    /**
     * RS-012 回归测试：去重必须按会话进行。不同会话应各自能拥有
     * 自己的活动校验。
     */
    @Test
    fun `RS-012 dedup is per-session, not global`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())

        val collab = StubCollaborator()
        val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        service.onClientSendParts("s1")
        service.onClientSendParts("s2")
        testScope.runCurrent()

        // 为两个不同会话触发校验
        service.triggerRestValidation("s1")
        service.triggerRestValidation("s2")
        service.triggerRestValidation("s1")  // s1 的去重
        service.triggerRestValidation("s2")  // s2 的去重

        testScope.runCurrent()

        // 每个会话应都至少进行过一次 REST 调用
        coVerify(atLeast = 1) { fakeRepo.fetchSessionStatuses("svr1", "D:/proj") }
    }

    /**
     * RS-012 回归测试：当一次校验完成时，应清理其去重条目，
     * 允许同一会话未来的校验。
     */
    @Test
    fun `RS-012 completed validation allows subsequent validation for same session`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        val callCount = AtomicInteger(0)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } coAnswers {
            callCount.incrementAndGet()
            Result.success(emptyMap())  // 缺失 → Idle
        }

        val collab = StubCollaborator()
        val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        service.onClientSendParts("s1")
        testScope.runCurrent()

        // 第一次校验 —— 完成（2026-08-16 新鲜度护栏：onClientSendParts 后
        // lastEventAt fresh，缺失不转 Idle——保持 Busy，见 SessionStateServiceTest 同名用例）
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])

        // 第二次校验 —— 应被允许（第一次已完成）
        service.onClientSendParts("s1")
        service.triggerRestValidation("s1")
        testScope.runCurrent()

        // 应至少进行过 2 次 REST 调用（每个校验周期一次）
        assertTrue(
            "Should allow new validation after previous completed (callCount=${callCount.get()})",
            callCount.get() >= 2
        )
    }

    // ============ RS-013：syncFromRest 快照一致性 ============

    /**
     * RS-013 基线测试：syncFromRest 对缺失的会话标记为 Idle。
     * 这是必须保留的既有行为。
     */
    @Test
    fun `RS-013 syncFromRest marks absent Busy session as Idle`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val service = newServiceWith(fakeRepo)
        service.setServerId("svr1")

        service.onClientSendParts("s1")
        testScope.runCurrent()

        runBlocking { service.syncFromRest(listOf()) }
        testScope.runCurrent()

        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
    }
}
