package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.DshQueueHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.domain.model.DshPlanProjection
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * #310③ 三步接线钉死：SessionPlanChanged 必须 ① mapper 产事件（DshEventMapperPlan310Test）
 * ② EventDispatcher 注册到 SessionHandler（本测试——漏 bind 即静默丢弃，goal 前车之鉴）
 * ③ handler 折叠进 Session.plan（SessionEventHandlerPlan310Test）。
 */
class EventDispatcherPlan310Test {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var sessionHandler: SessionEventHandler
    private lateinit var stateServiceScope: TestScope

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        sessionHandler = SessionEventHandler()
        dispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = MessageEventHandler(),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore(), MessageEventHandler()),
            sessionStateRepository = SessionStateService(
                appScope = stateServiceScope,
                sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
                collaborator = StubCollaborator(),
                cursorPolicyFactory = PaginationCursorPolicyFactory(
                    Provider { mockk<SessionRepository>(relaxed = true) },
                ),
            ),
            unreadBadgeService = UnreadBadgeService(
                mockk<UnreadStateStore>(relaxed = true),
                CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob()),
            ),
            ownershipRegistry = StreamingOwnershipRegistry(),
            permissionAutoApprover = mockk<PermissionAutoApprover>(relaxed = true),
            historySyncManagerProvider = Provider { mockk<HistorySyncManager>(relaxed = true) },
            dshJobsHandler = mockk(relaxed = true),
            dshQueueHandler = DshQueueHandler(mockk(relaxed = true)),
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    @Test
    fun `SessionPlanChanged routed to SessionHandler and folded into session plan`() = runTest {
        val session = Session(id = "s1", title = "T", time = Session.Time(1000L, 2000L))
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")

        dispatcher.processEvent(
            SseEvent.SessionPlanChanged("s1", DshPlanProjection(active = true, pending = false)),
            "server1",
        )

        // 漏 bind（registry 无注册）时事件被「No handler registered」静默丢弃 → plan 恒 null
        assertEquals(
            DshPlanProjection(active = true, pending = false),
            dispatcher.sessions.value.single().plan,
        )
    }
}