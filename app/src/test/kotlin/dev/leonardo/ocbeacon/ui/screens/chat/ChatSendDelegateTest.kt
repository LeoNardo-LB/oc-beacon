package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.SendMessageUseCase
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/**
 * #569：发送链级死线（[SEND_CHAIN_DEADLINE_MS]）行为测试。
 *
 * 钉死三条路径：
 * 1. 网络段永久挂起（冻结服务器等价——单调用无 per-request 超时兜底的极端形态，
 *    如 ensureSession 走 client 级 120s 之外的挂起源）→ 链级死线到点：
 *    失败对话框（sendFailureSink）+ isSending 复位 + 无成功信号；
 * 2. 引擎层超时异常上抛（sendChainTimeout 30s 或全局 120s 的触发形态）
 *    → 既有失败路径保持：失败对话框带异常信息；
 * 3. 正常成功 → 无对话框、成功信号携带快照、isSending 复位（回归钉）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendDelegateTest {

    private fun TestScope.makeDelegate(
        failures: MutableList<String>,
        successes: MutableList<String>,
        sendBehavior: suspend () -> Unit,
    ): Pair<ChatSendDelegate, SendStateStore> {
        val sendUseCase = mockk<SendMessageUseCase>()
        coEvery {
            sendUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers { sendBehavior() }
        val chatRepository = mockk<ChatRepository>(relaxed = true)
        every { chatRepository.getSessionsSnapshot() } returns emptyList()
        val sessionStateRepository = mockk<SessionStateService>(relaxed = true)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
        val store = SendStateStore()
        val delegate = ChatSendDelegate(
            scrollSignal = SessionScrollSignal(),
            sendMessageUseCase = sendUseCase,
            manageSessionUseCase = mockk<ManageSessionUseCase>(relaxed = true),
            chatRepository = chatRepository,
            sessionRepository = mockk<SessionRepository>(relaxed = true),
            sessionStateRepository = sessionStateRepository,
            sendStateStore = store,
            scope = backgroundScope,
            serverId = "srv1",
            sessionIdProvider = { "ses_1" },
            sessionDirectoryProvider = { null },
            ensureSession = { "ses_1" },
            modelConfigProvider = { ModelConfigState() },
            selectedVariantProvider = { null },
            errorSink = {},
            sendFailureSink = { failures.add(it) },
            onSendSuccess = { successes.add(it) },
            draftDelegate = mockk<DraftInputDelegate>(relaxed = true),
        )
        return delegate to store
    }

    @Test
    fun `send chain deadline fires failure dialog and clears isSending`() = runTest {
        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val (delegate, store) = makeDelegate(failures, successes) { awaitCancellation() }

        delegate.sendMessage("hello frozen")
        runCurrent()
        assertTrue("isSending must be true while hanging", store.isSendingValue)
        assertTrue("no failure before deadline", failures.isEmpty())

        advanceTimeBy(SEND_CHAIN_DEADLINE_MS + 100)
        runCurrent()

        assertEquals(1, failures.size)
        assertTrue(
            "deadline failure must be the chain-timeout message, got: ${failures.first()}",
            failures.first().contains("timed out", ignoreCase = true),
        )
        assertTrue("no success signal on timeout", successes.isEmpty())
        assertFalse("isSending must reset after deadline failure", store.isSendingValue)
    }

    @Test
    fun `engine timeout exception surfaces via existing failure path`() = runTest {
        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val (delegate, store) = makeDelegate(failures, successes) {
            throw SocketTimeoutException("read failed after 30s")
        }

        delegate.sendMessage("hello")
        runCurrent()

        assertEquals(1, failures.size)
        assertTrue(failures.first().contains("read failed"))
        assertTrue(successes.isEmpty())
        assertFalse(store.isSendingValue)
    }

    @Test
    fun `successful send emits success signal with snapshot and no failure`() = runTest {
        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val (delegate, store) = makeDelegate(failures, successes) { /* 受理成功 */ }

        delegate.sendMessage("hello")
        runCurrent()

        assertTrue(failures.isEmpty())
        assertEquals(listOf("hello"), successes)
        assertFalse(store.isSendingValue)
    }
}
