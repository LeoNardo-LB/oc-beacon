package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * #171 时钟域纯度测试——三条铁律从注释升级为结构不变量的反例验证：
 * 1. seedCachedMessages（DB 回读载荷）**不喂**水位线（客户端终结戳无从混入）
 * 2. upsertMessages（服务器载荷）从**载荷本身**提取 max（不扫合并缓存）
 * 3. SessionError 的客户端时刻经 [UnreadEvent.SessionErrorOccurred] 显式例外通道进水位线
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadClockDomainTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var unreadBadgeService: UnreadBadgeService
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var scope: CoroutineScope
    private lateinit var stateServiceScope: TestScope

    @Before
    fun setup() {
        settingsDataStore = mockk(relaxed = true)
        scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        unreadBadgeService = UnreadBadgeService(settingsDataStore, scope)
        val messageStore = MessageEventHandler()
        dispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore()),
            sessionStateRepository = SessionStateService(
                appScope = stateServiceScope,
                sessionRepoProvider = Provider { mockk<dev.leonardo.ocbeacon.domain.repository.SessionRepository>(relaxed = true) },
                collaborator = StubCollaborator(),
            cursorPolicyFactory = dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory(Provider { mockk<SessionRepository>(relaxed = true) }),
            ),
            settingsDataStore = settingsDataStore,
            unreadBadgeService = unreadBadgeService,
            ownershipRegistry = StreamingOwnershipRegistry(),
            sessionRepoProvider = Provider { mockk<dev.leonardo.ocbeacon.domain.repository.SessionRepository>(relaxed = true) },
            permissionAutoApprover = mockk(relaxed = true),
            chatRepoProvider = Provider { mockk<dev.leonardo.ocbeacon.domain.repository.ChatRepository>(relaxed = true) },
            pendingMessagePipelineProvider = Provider { mockk<PendingMessagePipeline>(relaxed = true) },
            pendingMessageRepository = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        stateServiceScope.cancel()
    }

    private fun assistant(id: String, sessionId: String, created: Long, completed: Long?): MessageWithParts =
        MessageWithParts(
            Message.Assistant(id = id, sessionId = sessionId, time = TimeInfo(created = created, completed = completed), parentId = "p0"),
            emptyList(),
        )

    @Test
    fun `seedCachedMessages does not feed watermark`() = runTest {
        // 反例核心：DB 回读载荷携带 markSessionIdle 的客户端终结戳（999_999 模拟本地 now）
        dispatcher.seedCachedMessages("s1", listOf(assistant("m1", "s1", created = 100L, completed = 999_999L)))
        assertNull(unreadBadgeService.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `upsertMessages extracts watermark from payload`() = runTest {
        dispatcher.upsertMessages("s1", listOf(assistant("m1", "s1", created = 100L, completed = 500L)), MergeStrategy.REST_AUTHORITY)
        assertEquals(500L, unreadBadgeService.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `payload extraction ignores cache pollution`() = runTest {
        // 缓存被本地终结戳污染（seed 载荷 completed=999_999）后，服务器载荷（600）提取的水位线应为 600
        // ——若实现退化为扫缓存 max 会得到 999_999。
        dispatcher.seedCachedMessages("s1", listOf(assistant("m1", "s1", created = 100L, completed = 999_999L)))
        dispatcher.upsertMessages("s1", listOf(assistant("m2", "s1", created = 200L, completed = 600L)), MergeStrategy.REST_AUTHORITY)
        assertEquals(600L, unreadBadgeService.lastCompletedReplyTime.first()["s1"])
    }

    @Test
    fun `session error feeds watermark via explicit client-clock exception`() = runTest {
        val before = System.currentTimeMillis()
        dispatcher.processEvent(
            SseEvent.SessionError(sessionId = "s1", error = "boom"),
            "svr1",
        )
        val ts = unreadBadgeService.lastCompletedReplyTime.first()["s1"]
        val after = System.currentTimeMillis()
        assertNotNull(ts)
        assertTrue("client now should be within [before, after]", ts!! in before..after)
    }

    @Test
    fun `markSessionRead no-op without watermark entry`() = runTest {
        // 秒退/消息未加载：无水位线记录 → 不写内存信号、不落盘（之后红点合理）
        unreadBadgeService.markSessionRead("svr1", "s1")
        assertTrue(unreadBadgeService.justRead.value.isEmpty())
        coVerify(exactly = 0) { settingsDataStore.markSessionRead(any(), any(), any()) }
    }

    @Test
    fun `unread judgment gates on Idle and compares watermark`() {
        val wm = mapOf("s1" to 2000L)
        assertTrue(UnreadBadgeService.isUnread("s1", wm, emptyMap(), status = SessionStatus.Idle))
        assertFalse(UnreadBadgeService.isUnread("s1", wm, mapOf("s1" to 2000L), status = SessionStatus.Idle))
        assertFalse(UnreadBadgeService.isUnread("s1", wm, emptyMap(), status = SessionStatus.Busy))
    }
}
