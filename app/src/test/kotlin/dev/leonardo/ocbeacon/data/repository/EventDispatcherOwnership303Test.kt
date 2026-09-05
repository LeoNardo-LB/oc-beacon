package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.logging.AppLogger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #303：同一物理后端双配置（reverse 隧道 + LAN 直连同一实例）下，会话生命周期
 * 事件（created/updated/deleted——幂等语义）不得被 ownership 单飞门吞掉——
 * 直连物理快几 ms 永赢 claim → 展示服务器的列表永不实时（真机打点三轮
 * 定罪，全 248 直连赢）。高频流式事件保留去重。
 */
class EventDispatcherOwnership303Test {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var sessionHandler: SessionEventHandler

    @Before
    fun setup() {
        sessionHandler = SessionEventHandler()
        val messageHandler = MessageEventHandler()
        val sessionStateRepository = mockk<SessionStateService>(relaxed = true)
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
        dispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(TokenStatsTracker()),
            sessionStateRepository = sessionStateRepository,
            unreadBadgeService = UnreadBadgeService(
                mockk(relaxed = true),
                CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
            ),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), messageHandler),
            ownershipRegistry = StreamingOwnershipRegistry(),
            permissionAutoApprover = mockk(relaxed = true),
            pendingInteractionStore = mockk(relaxed = true),
            historySyncManagerProvider = { mockk(relaxed = true) },
            dshJobsHandler = mockk(relaxed = true),
            dshQueueHandler = DshQueueHandler(mockk(relaxed = true)),
            dshWorkspaceHandler = DshWorkspaceHandler(DshWorkspaceStore()),
        )
        AppLogger.d("test", "setup done")
    }

    private fun session(id: String) = Session(
        id = id, title = "t-$id", directory = "/p",
        time = Session.Time(created = 1L, updated = 2L),
    )

    @Test
    fun `session created reaches both servers sharing same backend`() {
        val s = session("ses_dual")
        // 两条连接投递同一事件（serverA=直连先到、serverB=隧道后到）
        dispatcher.processEvent(SseEvent.SessionCreated(s), "serverA")
        dispatcher.processEvent(SseEvent.SessionCreated(s), "serverB")

        // 幂等生命周期事件：两台映射都必须含该会话（各自列表呈现）
        assertEquals(setOf("ses_dual"), sessionHandler.serverSessions.value["serverA"])
        assertEquals(setOf("ses_dual"), sessionHandler.serverSessions.value["serverB"])
    }

    @Test
    fun `session deleted applies on both servers`() {
        val s = session("ses_del")
        dispatcher.processEvent(SseEvent.SessionCreated(s), "serverA")
        dispatcher.processEvent(SseEvent.SessionCreated(s), "serverB")
        dispatcher.processEvent(SseEvent.SessionDeleted(s), "serverA")

        // 物理会话已删——handleSessionDeleted 清所有服务器集合（跨配置同后端语义正确）
        assertEquals(null, sessionHandler.serverSessions.value["serverA"])
        assertEquals(null, sessionHandler.serverSessions.value["serverB"])
    }
}
