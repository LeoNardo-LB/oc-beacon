package dev.leonardo.ocbeacon.data.repository

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
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #311 Task4 接线钉死（漏接=静默丢弃——goal 前车之鉴，EventDispatcherWorkspace311Test 同款）：
 * PermissionAsked/QuestionAsked 分发点旁路记录 PendingInteractionStore + 三清除路径
 * （①removePermission/removeQuestion 委托同点 ②状态流转兜底 ③SessionDeleted 级联）。
 */
class EventDispatcherPendingInteraction311Test {

    private val permissionHandler = PermissionEventHandler()
    private val questionHandler = QuestionEventHandler()
    private val statuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    // EventDispatcher 注入具体类 SessionStateService（store 消费接口 statusFlow）
    private val stateRepo = mockk<SessionStateService>(relaxed = true) {
        every { statusFlow } returns statuses
    }
    private val testDispatcher = UnconfinedTestDispatcher()
    private val store = PendingInteractionStore(stateRepo, CoroutineScope(testDispatcher + SupervisorJob()))

    private val dispatcher = EventDispatcher(
        sessionHandler = SessionEventHandler(),
        messageHandler = MessageEventHandler(),
        permissionHandler = permissionHandler,
        questionHandler = questionHandler,
        miscHandler = MiscEventHandler(),
        sessionNextHandler = SessionNextEventHandler(TokenStatsTracker()),
        shellJobsHandler = ShellJobsHandler(ShellJobsStore(), MessageEventHandler()),
        dshJobsHandler = mockk(relaxed = true),
        dshQueueHandler = DshQueueHandler(mockk(relaxed = true)),
        dshWorkspaceHandler = DshWorkspaceHandler(DshWorkspaceStore()),
        sessionStateRepository = stateRepo,
        unreadBadgeService = mockk(relaxed = true),
        ownershipRegistry = StreamingOwnershipRegistry(),
        permissionAutoApprover = mockk(relaxed = true),
        pendingInteractionStore = store,
        historySyncManagerProvider = javax.inject.Provider { mockk<HistorySyncManager>(relaxed = true) },
    )

    private val asked = SseEvent.PermissionAsked(
        id = "perm-1",
        sessionId = "ses-1",
        permission = "bash",
    )

    private fun question(id: String, intentKind: String? = null) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "ses-1",
        questions = listOf(
            SseEvent.QuestionAsked.Question(
                header = "h",
                question = "q",
                options = listOf(SseEvent.QuestionAsked.Option(label = "a", description = "d")),
                intent = intentKind?.let { SseEvent.QuestionAsked.Intent(kind = it) },
            ),
        ),
    )

    // ============ 分发点记录 ============

    @Test
    fun `permission asked records approval indicator`() {
        dispatcher.processEvent(asked, "srv")
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["ses-1"])
    }

    @Test
    fun `question asked records question indicator`() {
        dispatcher.processEvent(question("q-1"), "srv")
        assertEquals(PendingInteractionKind.QUESTION, store.pendingBySession.value["ses-1"])
    }

    @Test
    fun `plan-review question records plan-review kind`() {
        dispatcher.processEvent(question("q-1", intentKind = "plan-review"), "srv")
        assertEquals(PendingInteractionKind.PLAN_REVIEW, store.pendingBySession.value["ses-1"])
    }

    // ============ 清除① 本地应答（removePermission/removeQuestion 委托同点）============

    @Test
    fun `removePermission after local answer clears approval indicator`() {
        dispatcher.processEvent(asked, "srv")
        dispatcher.removePermission("perm-1")
        assertNull(store.pendingBySession.value["ses-1"])
    }

    @Test
    fun `answering one of two approvals keeps indicator until last resolved`() {
        dispatcher.processEvent(asked, "srv")
        dispatcher.processEvent(asked.copy(id = "perm-2"), "srv")
        dispatcher.removePermission("perm-1")
        assertEquals(PendingInteractionKind.APPROVAL, store.pendingBySession.value["ses-1"])
        dispatcher.removePermission("perm-2")
        assertNull(store.pendingBySession.value["ses-1"])
    }

    @Test
    fun `removeQuestion after local answer clears question indicator`() {
        dispatcher.processEvent(question("q-1"), "srv")
        dispatcher.removeQuestion("q-1")
        assertNull(store.pendingBySession.value["ses-1"])
    }

    @Test
    fun `removeQuestion clears plan-review via question family`() {
        dispatcher.processEvent(question("q-1", intentKind = "plan-review"), "srv")
        dispatcher.removeQuestion("q-1")
        assertNull(store.pendingBySession.value["ses-1"])
    }

    // ============ 清除② 轮次结束兜底（状态流订阅，FSM 写入零接触）============

    @Test
    fun `busy to idle turn end clears indicator as fallback`() {
        dispatcher.processEvent(asked, "srv")
        statuses.value = mapOf("ses-1" to SessionStatus.Busy)
        statuses.value = mapOf("ses-1" to SessionStatus.Idle)
        assertNull(store.pendingBySession.value["ses-1"])
    }

    // ============ 清除③ SessionDeleted 级联 ============

    @Test
    fun `session deleted cascades indicator clear`() {
        dispatcher.processEvent(asked, "srv")
        dispatcher.processEvent(
            SseEvent.SessionDeleted(
                Session(id = "ses-1", directory = "/p", time = Session.Time(created = 0, updated = 0))
            ),
            "srv",
        )
        assertNull(store.pendingBySession.value["ses-1"])
    }
}
