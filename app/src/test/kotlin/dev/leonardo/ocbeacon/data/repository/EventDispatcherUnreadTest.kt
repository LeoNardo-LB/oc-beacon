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
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.coVerify
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
 * 未读提示数据源（lastCompletedReplyTime / maxCompleted）测试：
 * 由 assistant 消息 completed（服务器时刻）更新；用户消息/未完成消息不算；
 * 增量取 max；无完成消息的会话移除条目。
 */
class EventDispatcherUnreadTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var stateServiceScope: TestScope
    private lateinit var sessionStateService: SessionStateService
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        val messageStore = MessageEventHandler()
        sessionStateService = SessionStateService(
            appScope = stateServiceScope,
            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
        )
        settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
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
            settingsDataStore = settingsDataStore,
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    private fun pushAssistantMessage(id: String, sessionId: String, created: Long, completed: Long? = null) {
        dispatcher.processEvent(
            SseEvent.MessageUpdated(
                Message.Assistant(id = id, sessionId = sessionId, time = TimeInfo(created = created, completed = completed), parentId = "p0")
            ),
            "svr1"
        )
    }

    @Test
    fun `init triggers v2 migration once`() = runTest {
        // runUnreadStateV2Migration 是扩展函数——coVerify 直接对其调用会真实执行函数体，
        // 内部 dataStore.edit → updateData 在 relaxed DataStore mock 上产生畸形期望（continuation 不匹配）。
        // 改为验证其唯一副作用：EventDispatcher init 对 DataStore 的 updateData 调用
        //（迁移是 init 触发 DataStore 写入的唯一路径）。coVerify(timeout) 等待独立 IO scope 执行完。
        coVerify(timeout = 5000) { settingsDataStore.dataStore.updateData(any()) }
    }

    @Test
    fun `assistant message with completed updates maxCompleted with server timestamp`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `assistant message without completed does NOT update`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = null)
        assertNull(dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `user message does NOT update`() = runTest {
        dispatcher.processEvent(
            SseEvent.MessageUpdated(Message.User(id = "m1", sessionId = "s1", time = TimeInfo(5000L))),
            "svr1"
        )
        assertNull(dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `later completed overwrites with max`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        pushAssistantMessage("m2", "s1", created = 200L, completed = 400L) // 更早完成 → 不覆盖
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
        pushAssistantMessage("m3", "s1", created = 300L, completed = 900L) // 更晚完成 → 覆盖
        assertEquals(900L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `replaceMessages recomputes max for session`() = runTest {
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        // REST 整批替换：replaceMessages 以 REST 为真相源合并（保留 SSE 已有消息）→ 重算 max
        val newer = Message.Assistant(id = "m9", sessionId = "s1", time = TimeInfo(created = 1000L, completed = 2000L), parentId = "p0")
        dispatcher.replaceMessages("s1", listOf(MessageWithParts(info = newer, parts = emptyList())))
        assertEquals(2000L, dispatcher.lastCompletedReplyTime.first()["s1"])
        // 整批替换后会话无完成消息 → maxCompleted 移除条目（无完成消息）
        val incomplete = Message.Assistant(id = "m10", sessionId = "s2", time = TimeInfo(created = 3000L, completed = null), parentId = "p0")
        dispatcher.replaceMessages("s2", listOf(MessageWithParts(info = incomplete, parts = emptyList())))
        assertNull(dispatcher.lastCompletedReplyTime.first()["s2"])
    }
}
