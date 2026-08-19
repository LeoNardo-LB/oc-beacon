package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 堆积消息推进触发器（2026-08-20 设计定稿）：
 * 「自然成功 turn 结束」= Busy→Idle 且触发事件 ∈ {SseIdle, SseStatus(Idle)}。
 * 其余一切到 Idle 的路径（手动 abort / 错误 / REST 兜底）不得触发；
 * V1 status/idle 双发的第二发因已 Idle 天然去重。
 */
class NaturalTurnEndListenerTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newService(): SessionStateService = SessionStateService(
        testScope,
        Provider { mockk<SessionRepository>(relaxed = true) },
    )

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun busyToIdleViaSseIdleFiresListener() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1") // Idle→Busy
        service.onSseEvent(SseEvent.SessionIdle("s1"), "s1", "srv1")

        assertEquals(listOf("s1" to "srv1"), fired)
    }

    @Test
    fun busyToIdleViaV1SessionStatusIdleFiresListener() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionStatus("s1", SessionStatus.Idle), "s1", "srv1")

        assertEquals(listOf("s1" to "srv1"), fired)
    }

    @Test
    fun v1DoubleSendDedupesSecondIdleDoesNotFire() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        // V1 双发：session.status(idle) 先到，deprecated session.idle 后到
        service.onSseEvent(SseEvent.SessionStatus("s1", SessionStatus.Idle), "s1", "srv1")
        service.onSseEvent(SseEvent.SessionIdle("s1"), "s1", "srv1")

        assertEquals(1, fired.size)
    }

    @Test
    fun manualClientAbortDoesNotFire() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        service.onClientAbort("s1")

        assertTrue(fired.isEmpty())
    }

    @Test
    fun serverIdleAfterClientAbortDoesNotFire() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        service.onClientAbort("s1")
        // 服务器随后补发的 idle（abortSession 先 cancelSseJob，但兜底场景仍可能到达）
        service.onSseEvent(SseEvent.SessionIdle("s1"), "s1", "srv1")

        assertTrue(fired.isEmpty())
    }

    @Test
    fun errorTurnEndDoesNotFire() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionError("s1", "boom"), "s1", "srv1")

        assertTrue(fired.isEmpty())
    }

    @Test
    fun restValidationFallbackToIdleDoesNotFire() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        // V2 出错 turn：无终态事件，L2/L3 兜底走 RestValidation(Idle)
        service.onRestValidation("s1", SessionStatus.Idle)

        assertTrue(fired.isEmpty())
    }

    @Test
    fun idleBaselineSseIdleWithoutBusyDoesNotFire() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        // 未经历过 Busy 的孤儿 idle 事件
        service.onSseEvent(SseEvent.SessionIdle("s1"), "s1", "srv1")

        assertTrue(fired.isEmpty())
    }

    @Test
    fun nextNaturalTurnRestartsPipeline() {
        val service = newService()
        val fired = mutableListOf<Pair<String, String?>>()
        service.naturalTurnEndListener = { sid, srv -> fired.add(sid to srv) }

        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionIdle("s1"), "s1", "srv1")
        // 推进后新 turn（队首消息发出 → execution.started Busy）
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionIdle("s1"), "s1", "srv1")

        assertEquals(2, fired.size)
    }
}
