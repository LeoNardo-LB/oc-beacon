package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.PendingMessage
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 堆积消息推进管线（2026-08-20 设计定稿；2026-08-21 #176/#177 状态补偿扩展，
 * spec: docs/specs/2026-08-21-queue-drain-state-compensation-design.md）：
 * - peek → POST → 成功才 delete（失败留队首，心跳 5s 无限重试）
 * - POST 成功后 onClientSendParts 置 Busy
 * - 会话级 in-flight 去重
 * - 状态补偿：T1 心跳 / T2 入队即时 / T3 Idle 观察 → FSM Idle + 队列非空即发
 */
class PendingMessagePipelineTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val repo = mockk<PendingMessageRepository>(relaxed = true)
    private val sendUseCase = mockk<SendMessageUseCase>(relaxed = true)
    private val stateService = mockk<SessionStateService>(relaxed = true)
    private val statusFlow = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())

    init {
        every { stateService.statusFlow } returns statusFlow
        every { stateService.serverIdFor("s1") } returns "srv1"
    }

    private fun newPipeline() = PendingMessagePipeline(
        testScope,
        repo,
        Provider { sendUseCase },
        stateService,
    )

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private fun msg(id: Long, text: String, position: Int = 0, sessionId: String = "s1") = PendingMessage(
        id = id, sessionId = sessionId, position = position, text = text, createdAt = 0L,
    )

    @Test
    fun naturalTurnEndSendsHeadAndDeletesOnSuccess() {
        val pipeline = newPipeline()
        coEvery { repo.peekHead("s1") } returns msg(1, "hello")

        pipeline.onNaturalTurnEnd("s1", "srv1")

        val partsSlot = slot<List<PromptPart>>()
        coVerify {
            sendUseCase.sendPrompt(
                serverId = "srv1", sessionId = "s1",
                parts = capture(partsSlot),
                model = null, agent = "", variant = null, directory = null,
            )
        }
        assertEquals(1, partsSlot.captured.size)
        assertEquals("text", partsSlot.captured[0].type)
        assertEquals("hello", partsSlot.captured[0].text)
        coVerify(exactly = 1) { repo.delete(1L) }
        verify { stateService.onClientSendParts("s1") }
    }

    @Test
    fun sendFailureKeepsMessageAtHead() {
        val pipeline = newPipeline()
        coEvery { repo.peekHead("s1") } returns msg(1, "hello")
        coEvery {
            sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("network down")

        pipeline.onNaturalTurnEnd("s1", "srv1")

        coVerify(exactly = 0) { repo.delete(any()) }
    }

    @Test
    fun emptyQueueIsNoop() {
        val pipeline = newPipeline()
        coEvery { repo.peekHead("s1") } returns null

        pipeline.onNaturalTurnEnd("s1", "srv1")

        coVerify(exactly = 0) {
            sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun missingServerOwnershipSkipsDrain() {
        val pipeline = newPipeline()

        pipeline.onNaturalTurnEnd("s1", null)

        coVerify(exactly = 0) { repo.peekHead(any()) }
    }

    @Test
    fun sendOneNowSendsSpecificItem() {
        val pipeline = newPipeline()

        pipeline.sendOneNow("s1", "srv1", 42L, "middle item")

        val partsSlot = slot<List<PromptPart>>()
        coVerify {
            sendUseCase.sendPrompt(
                serverId = "srv1", sessionId = "s1",
                parts = capture(partsSlot),
                model = null, agent = "", variant = null, directory = null,
            )
        }
        assertEquals("middle item", partsSlot.captured[0].text)
        coVerify(exactly = 1) { repo.delete(42L) }
    }

    @Test
    fun drainingStateTracksSession() {
        val pipeline = newPipeline()
        var released = false
        coEvery { repo.peekHead("s1") } answers {
            // 首次调用时 draining 集合应包含本会话
            assertTrue(pipeline.drainingSessions.value.contains("s1"))
            released = true
            msg(1, "x")
        }

        pipeline.continueNow("s1", "srv1")

        assertTrue(released)
        // 完成后集合清空
        assertFalse(pipeline.drainingSessions.value.contains("s1"))
    }

    // ============ #176/#177 状态补偿 ============

    @Test
    fun t2EnqueueAtIdleDrainsImmediately() {
        // #176 精确场景：turn 已在入队前结束（FSM Idle），入队即时补偿发队首
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s1" to SessionStatus.Idle)
        coEvery { repo.peekHead("s1") } returns msg(1, "late stack")

        pipeline.onEnqueued("s1")

        coVerify(exactly = 1) { sendUseCase.sendPrompt("srv1", "s1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.delete(1L) }
    }

    @Test
    fun t2EnqueueAtBusyDoesNotDrain() {
        // Busy 会话入队：保持原语义（等 turn 结束），不抢发
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s1" to SessionStatus.Busy)

        pipeline.onEnqueued("s1")

        coVerify(exactly = 0) { repo.peekHead(any()) }
    }

    @Test
    fun t1HeartbeatRetriesAfterSendFailure() {
        // #177 断点②：POST 失败不动点 → 心跳 5s 无限重试
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s1" to SessionStatus.Idle)
        coEvery { repo.sessionIdsWithPending() } returns listOf("s1")
        coEvery { repo.peekHead("s1") } returns msg(1, "stuck")
        coEvery { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }
            .throws(RuntimeException("server down"))

        pipeline.start()
        // 首拍前无动作；advanceTimeBy 触发第一拍
        testScope.advanceTimeBy(5_001)
        coVerify(atLeast = 1) { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repo.delete(any()) }

        // 服务器恢复：下一拍发送成功 → delete
        coEvery { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        testScope.advanceTimeBy(5_001)
        coVerify(atLeast = 1) { repo.delete(1L) }
    }

    @Test
    fun t3IdleTransitionTriggersDrain() {
        // #177 断点③：RestValidation(Idle) 类语义——statusFlow 落 Idle 即 drain
        val pipeline = newPipeline()
        coEvery { repo.peekHead("s1") } returns msg(1, "recovered")
        pipeline.start()

        // Busy 期间无动作
        statusFlow.value = mapOf("s1" to SessionStatus.Busy)
        coVerify(exactly = 0) { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }

        // L3/L4 恢复 → Idle（非自然结束白名单路径）
        statusFlow.value = mapOf("s1" to SessionStatus.Idle)
        coVerify(exactly = 1) { sendUseCase.sendPrompt("srv1", "s1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.delete(1L) }
    }

    @Test
    fun pendingUserInputSkipsCompensation() {
        // 护栏：问题/权限待处理时不 drain（防把待处理状态当可推进）
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s1" to SessionStatus.Idle)
        every { stateService.hasPendingUserInput("s1") } returns true

        pipeline.onEnqueued("s1")

        coVerify(exactly = 0) { repo.peekHead(any()) }
    }

    @Test
    fun missingServerOwnershipSkipsCompensation() {
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s2" to SessionStatus.Idle)
        every { stateService.serverIdFor("s2") } returns null

        pipeline.onEnqueued("s2")

        coVerify(exactly = 0) { repo.peekHead(any()) }
    }

    @Test
    fun concurrentTriggersDedupToOneSend() {
        // in-flight 去重：同会话并发触发（边沿 + 补偿）只发一条
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s1" to SessionStatus.Idle)
        val gate = CompletableDeferred<Unit>()
        coEvery { repo.peekHead("s1") } coAnswers { gate.await(); msg(1, "race") }

        pipeline.onNaturalTurnEnd("s1", "srv1")
        pipeline.onEnqueued("s1")

        gate.complete(Unit)
        coVerify(exactly = 1) { sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.delete(1L) }
    }

    @Test
    fun multiSessionCompensationIsIndependent() {
        val pipeline = newPipeline()
        statusFlow.value = mapOf("s1" to SessionStatus.Idle, "s2" to SessionStatus.Idle)
        every { stateService.serverIdFor("s2") } returns "srv2"
        coEvery { repo.peekHead("s1") } returns msg(1, "a", sessionId = "s1")
        coEvery { repo.peekHead("s2") } returns msg(2, "b", sessionId = "s2")

        pipeline.onEnqueued("s1")
        pipeline.onEnqueued("s2")

        coVerify(exactly = 1) { sendUseCase.sendPrompt("srv1", "s1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { sendUseCase.sendPrompt("srv2", "s2", any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.delete(1L) }
        coVerify(exactly = 1) { repo.delete(2L) }
    }
}
