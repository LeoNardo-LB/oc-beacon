package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.PendingMessage
import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 堆积消息推进管线（2026-08-20 设计定稿）：
 * - peek → POST → 成功才 delete（失败留队首）
 * - POST 成功后 onClientSendParts 置 Busy
 * - 会话级 in-flight 去重
 */
class PendingMessagePipelineTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val repo = mockk<PendingMessageRepository>(relaxed = true)
    private val sendUseCase = mockk<SendMessageUseCase>(relaxed = true)
    private val stateService = mockk<SessionStateService>(relaxed = true)

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

    private fun msg(id: Long, text: String, position: Int = 0) = PendingMessage(
        id = id, sessionId = "s1", position = position, text = text, createdAt = 0L,
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
}
