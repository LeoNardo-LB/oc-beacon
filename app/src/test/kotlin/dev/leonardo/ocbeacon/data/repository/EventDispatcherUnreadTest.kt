package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessagePartHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageRemovedHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageUpdatedHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * 未读提示数据源（lastMessageTime）测试：红点绑定 **turn 结束**——
 * step.ended（finish ≠ "tool-calls"）才记录；单个 step/工具调用完成不算；
 * 用户消息不产生未读。
 */
class EventDispatcherUnreadTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var stateServiceScope: TestScope
    private lateinit var sessionStateService: SessionStateService

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        val messageStore = MessageEventHandler()
        sessionStateService = SessionStateService(
            appScope = stateServiceScope,
            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
        )
        dispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            messagePartHandler = MessagePartHandler(messageStore),
            messageUpdatedHandler = MessageUpdatedHandler(messageStore),
            messageRemovedHandler = MessageRemovedHandler(messageStore),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(),
            sessionStateService = sessionStateService,
            settingsDataStore = mockk<SettingsDataStore>(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    private fun pushAssistantMessage(id: String, sessionId: String, created: Long) {
        dispatcher.processEvent(
            SseEvent.MessageUpdated(
                Message.Assistant(id = id, sessionId = sessionId, time = TimeInfo(created = created, completed = null), parentId = "p0")
            ),
            "svr1"
        )
    }

    private fun pushStepEnded(sessionId: String, messageId: String, finish: String, timestamp: Long = 0) {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepEnded(
                sessionId = sessionId, messageId = messageId, step = 1, finish = finish, timestamp = timestamp
            )),
            "svr1"
        )
    }

    @Test
    fun `turn end records server timestamp not processing time`() = runTest {
        // 服务器时刻（回复完成）必然早于客户端处理时刻——延迟到达不产生误报
        pushStepEnded("s1", "m1", "stop", timestamp = 1000L)
        assertEquals(1000L, dispatcher.lastReplyTime.first()["s1"])
    }

    @Test
    fun `turn end with finish stop records end time`() = runTest {
        val before = System.currentTimeMillis()
        pushStepEnded("s1", "m1", "stop")
        val recorded = dispatcher.lastReplyTime.first()["s1"]
        assertEquals(true, recorded != null && recorded >= before)
    }

    @Test
    fun `tool-calls finish does NOT record (turn continues)`() = runTest {
        // 工具调用完成：turn 未结束，不应产生未读
        pushStepEnded("s1", "m1", "tool-calls")
        assertNull("tool-calls step should NOT trigger unread", dispatcher.lastReplyTime.first()["s1"])

        // 后续 step 正常停止 → 记录
        pushStepEnded("s1", "m1", "stop")
        assertEquals(true, dispatcher.lastReplyTime.first()["s1"] != null)
    }

    @Test
    fun `user message does NOT count as unread`() = runTest {
        dispatcher.processEvent(
            SseEvent.MessageUpdated(Message.User(id = "m1", sessionId = "s1", time = TimeInfo(5000L))),
            "svr1"
        )
        assertNull("user message should NOT trigger unread", dispatcher.lastReplyTime.first()["s1"])
    }

    @Test
    fun `later turn end overwrites with newer time`() = runTest {
        pushStepEnded("s1", "m1", "stop")
        val first = dispatcher.lastReplyTime.first()["s1"]!!
        // 第二个 turn 结束（时间推进）
        pushStepEnded("s1", "m2", "stop")
        val second = dispatcher.lastReplyTime.first()["s1"]!!
        assertEquals(true, second >= first)
    }

    @Test
    fun `turn end for unknown session still records (no message lookup needed)`() = runTest {
        pushStepEnded("s1", "ghost", "stop")
        assertEquals(true, dispatcher.lastReplyTime.first()["s1"] != null)
    }
}
