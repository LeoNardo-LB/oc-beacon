package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.MiscEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.PermissionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.QuestionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.SessionNextEventHandler
import dev.leonardo.ocbeacon.data.repository.handler.ShellJobsHandler
import dev.leonardo.ocbeacon.domain.model.CommandFeedback
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * #323 三步接线钉死（#311 同款纪律）：
 * ① mapper 产事件（DshEventMapperCommand323Test）
 * ② EventDispatcher bind 到 MiscEventHandler（漏 bind 即「No handler registered」
 *    静默丢弃，goal 前车之鉴）
 * ③ handler 经 CommandFeedbackFolder 折叠——commandId 配对原位更新。
 */
class EventDispatcherCommand323Test {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var stateServiceScope: TestScope

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
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
            pendingInteractionStore = mockk(relaxed = true),
            stackedMessageStore = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.StackedMessageStore>(relaxed = true),
            historySyncManagerProvider = Provider { mockk<HistorySyncManager>(relaxed = true) },
            dshJobsHandler = mockk(relaxed = true),
            dshQueueHandler = mockk(relaxed = true),
            dshWorkspaceHandler = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    @Test
    fun `command run then done folded into single in-place terminal card`() = runTest {
        dispatcher.processEvent(
            SseEvent.CommandRunStarted(
                sessionId = "s1", commandId = "cmd-1", name = "compact",
                args = "--keep 5", seq = 6, time = 100L,
            ),
            "srv-1",
        )
        assertEquals(
            listOf(CommandFeedback(commandId = "cmd-1", name = "compact", args = "--keep 5", seq = 6, startedAt = 100L)),
            dispatcher.commandFeedback.value["s1"],
        )

        dispatcher.processEvent(
            SseEvent.CommandDone(
                sessionId = "s1", commandId = "cmd-1", kind = "success",
                text = "done", sourceEventSeq = 42, seq = 7, time = 150L,
            ),
            "srv-1",
        )
        // commandId 配对原位更新：仍是单卡，run 建的 name/args 保真，终态刷新
        val cards = dispatcher.commandFeedback.value["s1"]!!
        assertEquals(1, cards.size)
        assertEquals(
            CommandFeedback.Done(kind = "success", text = "done", sourceEventSeq = 42, time = 150L),
            cards.single().done,
        )
        assertEquals("compact", cards.single().name)
    }

    @Test
    fun `command feedback state is per-session isolated`() = runTest {
        dispatcher.processEvent(
            SseEvent.CommandRunStarted(sessionId = "s1", commandId = "c1", name = "new", seq = 1),
            "srv-1",
        )
        dispatcher.processEvent(
            SseEvent.CommandRunStarted(sessionId = "s2", commandId = "c2", name = "compact", seq = 2),
            "srv-1",
        )
        assertEquals(setOf("c1"), dispatcher.commandFeedback.value["s1"]!!.map { it.commandId }.toSet())
        assertEquals(setOf("c2"), dispatcher.commandFeedback.value["s2"]!!.map { it.commandId }.toSet())
    }

    @Test
    fun `session deleted cascades command feedback clear`() = runTest {
        dispatcher.processEvent(
            SseEvent.CommandRunStarted(sessionId = "s1", commandId = "c1", name = "new", seq = 1),
            "srv-1",
        )
        dispatcher.processEvent(
            SseEvent.SessionDeleted(Session(id = "s1", time = Session.Time(0L, 0L))),
            "srv-1",
        )
        assertNull(dispatcher.commandFeedback.value["s1"])
    }
}
