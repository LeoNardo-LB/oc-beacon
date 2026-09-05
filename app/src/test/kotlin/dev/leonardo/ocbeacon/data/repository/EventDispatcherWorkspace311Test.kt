package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.DshJobsHandler
import dev.leonardo.ocbeacon.data.repository.handler.DshQueueHandler
import dev.leonardo.ocbeacon.data.repository.handler.DshWorkspaceHandler
import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.Workspace
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
 * #311 Task1 三步接线钉死（#310③ 同款纪律）：
 * ① mapper 产事件（DshEventMapperWorkspace311Test）
 * ② EventDispatcher 注册到 DshWorkspaceHandler（本测试——漏 bind 即「No handler
 *    registered」静默丢弃，goal 前车之鉴）
 * ③ handler 折叠进 DshWorkspaceStore（baseline 整替换 + archived 集合替换）。
 */
class EventDispatcherWorkspace311Test {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var store: DshWorkspaceStore
    private lateinit var stateServiceScope: TestScope

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        store = DshWorkspaceStore()
        dispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
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
            permissionAutoApprover = mockk(relaxed = true),
            historySyncManagerProvider = Provider { mockk<HistorySyncManager>(relaxed = true) },
            dshJobsHandler = mockk(relaxed = true),
            dshQueueHandler = DshQueueHandler(mockk(relaxed = true)),
            dshWorkspaceHandler = DshWorkspaceHandler(store),
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    @Test
    fun `workspace baseline and archived events routed to handler and folded into store`() = runTest {
        // 预置会话（serverSessions 键建立——与 workspace 域无关，仅验证事件不串扰）
        dispatcher.processEvent(
            SseEvent.SessionCreated(Session(id = "s1", title = "T", time = Session.Time(1000L, 2000L))),
            "srv-1",
        )

        // baseline：注册表 + 归档集合同帧入 store
        dispatcher.processEvent(
            SseEvent.WorkspaceSnapshotChanged(
                workspaces = listOf(Workspace(workspaceId = "ws-1", path = "/w", title = "W", sessionIds = listOf("s-1"))),
                archivedSessionIds = listOf("s-9"),
            ),
            "srv-1",
        )
        assertEquals(listOf("ws-1"), store.snapshotFor("srv-1").workspaces.map { it.workspaceId })
        assertEquals(listOf("s-9"), store.snapshotFor("srv-1").archivedSessionIds)

        // archived 增量：集合替换（workspaces 保持）
        dispatcher.processEvent(
            SseEvent.WorkspaceArchivedChanged(archivedSessionIds = listOf("s-2", "s-9")),
            "srv-1",
        )
        assertEquals(listOf("s-2", "s-9"), store.snapshotFor("srv-1").archivedSessionIds)
        assertEquals(listOf("ws-1"), store.snapshotFor("srv-1").workspaces.map { it.workspaceId })
    }

    /** #311 Task3：upsert 增量路由折叠——漏 bind 即静默丢弃（goal 前车之鉴同款纪律）。 */
    @Test
    fun `workspace upsert event routed to handler and folded into store`() = runTest {
        dispatcher.processEvent(
            SseEvent.WorkspaceSnapshotChanged(
                workspaces = listOf(
                    Workspace(workspaceId = "ws-1", path = "/w", title = "W", sessionIds = listOf("s-1")),
                    Workspace(workspaceId = "ws-2", path = "/w2", title = "W2"),
                ),
                archivedSessionIds = listOf("s-9"),
            ),
            "srv-1",
        )
        dispatcher.processEvent(
            SseEvent.WorkspaceUpserted(
                workspace = Workspace(workspaceId = "ws-1", path = "/w", title = "Renamed", sessionIds = listOf("s-1", "s-2")),
            ),
            "srv-1",
        )
        val snapshot = store.snapshotFor("srv-1")
        // 原位替换 + 未知 workspace 追加语义由 DshWorkspaceStoreTest 覆盖；此处钉 bind 链
        assertEquals(listOf("ws-1", "ws-2"), snapshot.workspaces.map { it.workspaceId })
        assertEquals("Renamed", snapshot.workspaces[0].title)
        assertEquals(listOf("s-1", "s-2"), snapshot.workspaces[0].sessionIds)
        assertEquals(listOf("s-9"), snapshot.archivedSessionIds)
    }
}
