package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
    private lateinit var sessionStateRepository: SessionStateService
    private lateinit var unreadStateStore: UnreadStateStore

    private fun makeDispatcher(): EventDispatcher {
        val messageStore = MessageEventHandler()
        return EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageStore),
            sessionStateRepository = sessionStateRepository,
            unreadBadgeService = UnreadBadgeService(
                unreadStateStore,
                CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
            ),
            ownershipRegistry = StreamingOwnershipRegistry(),
            // #122 接线新增：自动批准（relaxed mock——shouldAutoApprove 恒 false，既有用例不受影响）
            permissionAutoApprover = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover>(relaxed = true),
            // 堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessagePipelineProvider = Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true) },
            historySyncManagerProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.HistorySyncManager>(relaxed = true) },
            dshJobsHandler = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.handler.DshJobsHandler>(relaxed = true),
            dshQueueHandler = dev.leonardo.ocbeacon.data.repository.handler.DshQueueHandler(mockk(relaxed = true)),

        )
    }

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        sessionStateRepository = SessionStateService(
            appScope = stateServiceScope,
            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
            collaborator = StubCollaborator(),
            cursorPolicyFactory = dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory(Provider { mockk<SessionRepository>(relaxed = true) }),
        )
        unreadStateStore = mockk<UnreadStateStore>(relaxed = true)
        dispatcher = makeDispatcher()
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

    // 注：init 触发迁移/seed 的两个用例已随 C7 编排迁移 UnreadBadgeServiceTest
    //（bootstrap 现由 OpenCodeApp 启动调用，EventDispatcher 不再持有持久化 init）。

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
        dispatcher.upsertMessages("s1", listOf(MessageWithParts(info = newer, parts = emptyList())), MergeStrategy.REST_AUTHORITY)
        assertEquals(2000L, dispatcher.lastCompletedReplyTime.first()["s1"])
        // 整批替换后会话无完成消息 → maxCompleted 移除条目（无完成消息）
        val incomplete = Message.Assistant(id = "m10", sessionId = "s2", time = TimeInfo(created = 3000L, completed = null), parentId = "p0")
        dispatcher.upsertMessages("s2", listOf(MessageWithParts(info = incomplete, parts = emptyList())), MergeStrategy.REST_AUTHORITY)
        assertNull(dispatcher.lastCompletedReplyTime.first()["s2"])
    }

    @Test
    fun `recompute with null completed snapshot keeps existing max`() = runTest {
        // 根因 1 防回归：REST 快照滞后（会话流式中 completed=null）不应移除已记录的 maxCompleted
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
        // 模拟 REST 同步拉到流式快照（最后一条 assistant completed=null）
        dispatcher.upsertMessages("s1", listOf(
            MessageWithParts(
                info = Message.Assistant(id = "m1", sessionId = "s1", time = TimeInfo(created = 100L, completed = null), parentId = "p0"),
                parts = emptyList()
            )
        ), MergeStrategy.REST_AUTHORITY)
        // 已记录的 500L 必须保留——暂时的 null 快照不能抹掉已知完成时刻
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `completed update triggers persist via UnreadBadgeService`() = runTest {
        // saveLastCompletedReplyTimes 现为 SettingsDataStore 成员方法（合并自扩展文件），可被 mock 拦截记录。
        // processEvent → UnreadBadgeService.persist 同步调用本方法；coVerify 无需等待即可断言
        //（同步语义由代码结构保证——非异步 collect）。
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        coVerify { unreadStateStore.saveLastCompletedReplyTimes(any()) }
    }

    @Test
    fun `clearForServer keeps maxCompleted (connection teardown is not deletion)`() = runTest {
        // 根因 3 防回归：clearForServer（stopConnection 调用，连接停止）是连接状态清理，
        // 不应抹掉红点事实数据（服务器最后完成时刻）
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        dispatcher.clearForServer("svr1")
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `clearAll keeps maxCompleted`() = runTest {
        // 根因 3 防回归：clearAll（stopAllConnections 调用，连接全停）不清红点数据
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        dispatcher.clearAll()
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `session deleted removes maxCompleted`() = runTest {
        // 根因 3：只有 SessionDeleted（会话真删）才移除红点条目
        pushAssistantMessage("m1", "s1", created = 100L, completed = 500L)
        assertEquals(500L, dispatcher.lastCompletedReplyTime.first()["s1"])
        dispatcher.processEvent(
            SseEvent.SessionDeleted(info = Session(id = "s1", time = Session.Time(created = 100L, updated = 100L))),
            "svr1"
        )
        assertNull(dispatcher.lastCompletedReplyTime.first()["s1"])
    }
}
