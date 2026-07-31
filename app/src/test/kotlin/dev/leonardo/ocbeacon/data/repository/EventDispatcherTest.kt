package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

class EventDispatcherTest {

    private lateinit var dispatcher: EventDispatcher
    private lateinit var sessionHandler: SessionEventHandler
    private lateinit var messageHandler: MessageEventHandler
    private lateinit var permissionHandler: PermissionEventHandler
    private lateinit var questionHandler: QuestionEventHandler
    private lateinit var miscHandler: MiscEventHandler
    private lateinit var sessionNextHandler: SessionNextEventHandler
    private lateinit var stateServiceScope: TestScope
    private lateinit var sessionStateService: SessionStateService

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        sessionHandler = SessionEventHandler()
        messageHandler = MessageEventHandler()
        permissionHandler = PermissionEventHandler()
        questionHandler = QuestionEventHandler()
        miscHandler = MiscEventHandler()
        sessionNextHandler = SessionNextEventHandler()
        sessionStateService = SessionStateService(
            appScope = stateServiceScope,
            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
        )

        dispatcher = EventDispatcher(
            sessionHandler = sessionHandler,
            messageHandler = messageHandler,
            messagePartHandler = MessagePartHandler(messageHandler),
            messageUpdatedHandler = MessageUpdatedHandler(messageHandler),
            messageRemovedHandler = MessageRemovedHandler(messageHandler),
            permissionHandler = permissionHandler,
            questionHandler = questionHandler,
            miscHandler = miscHandler,
            sessionNextHandler = sessionNextHandler,
            sessionStateService = sessionStateService,
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    private fun testSession(id: String) = Session(
        id = id, title = "Test", time = Session.Time(created = 1000L, updated = 2000L)
    )

    // ============ Event Dispatching ============

    @Test
    fun `processEvent dispatches session events to SessionHandler`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        assertEquals(listOf(session), dispatcher.sessions.value)
    }

    @Test
    fun `processEvent dispatches message events to MessageHandler`() = runTest {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        dispatcher.processEvent(SseEvent.MessageUpdated(msg), "server1")
        assertEquals(listOf(msg), dispatcher.messages.value["s1"])
    }

    @Test
    fun `processEvent dispatches permission events to PermissionHandler`() = runTest {
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        dispatcher.processEvent(perm, "server1")
        assertEquals(listOf(perm), dispatcher.permissions.value["s1"])
    }

    @Test
    fun `processEvent dispatches question events to QuestionHandler`() = runTest {
        val q = SseEvent.QuestionAsked(id = "q1", sessionId = "s1", questions = listOf(
            SseEvent.QuestionAsked.Question("H", "Q?", options = emptyList())
        ))
        dispatcher.processEvent(q, "server1")
        assertEquals(listOf(q), dispatcher.questions.value["s1"])
    }

    @Test
    fun `processEvent dispatches todo events to MiscHandler`() = runTest {
        val todos = listOf(SseEvent.TodoUpdated.Todo("Task", "pending", "high"))
        dispatcher.processEvent(SseEvent.TodoUpdated("s1", todos), "server1")
        assertEquals(todos, dispatcher.todos.value["s1"])
    }

    // ============ Cross-handler: SessionDeleted cascades cleanup ============

    @Test
    fun `SessionDeleted cascades cleanup to all handlers`() = runTest {
        val session = testSession("s1")
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        val q = SseEvent.QuestionAsked(id = "q1", sessionId = "s1", questions = listOf(
            SseEvent.QuestionAsked.Question("H", "Q?", options = emptyList())
        ))
        val todos = listOf(SseEvent.TodoUpdated.Todo("Task", "pending", "high"))

        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        dispatcher.processEvent(SseEvent.MessageUpdated(msg), "server1")
        dispatcher.processEvent(perm, "server1")
        dispatcher.processEvent(q, "server1")
        dispatcher.processEvent(SseEvent.TodoUpdated("s1", todos), "server1")

        // Now delete the session
        dispatcher.processEvent(SseEvent.SessionDeleted(session), "server1")

        // All state for session s1 should be cleaned up
        assertTrue(dispatcher.sessions.value.isEmpty())
        assertFalse(dispatcher.sessionStatuses.value.containsKey("s1"))
        assertFalse(dispatcher.messages.value.containsKey("s1"))
        assertFalse(dispatcher.permissions.value.containsKey("s1"))
        assertFalse(dispatcher.questions.value.containsKey("s1"))
        assertFalse(dispatcher.todos.value.containsKey("s1"))
    }

    // ============ Cross-handler: CommandExecuted resets session status ============

    @Test
    fun `CommandExecuted does NOT reset session status to Idle`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        // Set status to Busy via SSE (the authoritative path)
        dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "server1")
        stateServiceScope.runCurrent()

        dispatcher.processEvent(SseEvent.CommandExecuted(name = "bash", sessionId = "s1"), "server1")
        stateServiceScope.runCurrent()

        // P0-4 fix: CommandExecuted no longer forces Idle — session.status SSE event controls state
        assertEquals(SessionStatus.Busy, dispatcher.sessionStatuses.value["s1"])
    }

    // ============ Clear Operations ============

    @Test
    fun `clearAll resets all state`() = runTest {
        dispatcher.processEvent(SseEvent.SessionCreated(
            Session(id = "s1", time = Session.Time(1000L, 2000L))
        ), "server1")
        dispatcher.processEvent(SseEvent.VcsBranchUpdated("main"), "server1")

        dispatcher.clearAll()

        assertTrue(dispatcher.sessions.value.isEmpty())
        assertNull(dispatcher.vcsBranch.value)
        assertNull(dispatcher.projectInfo.value)
        assertTrue(dispatcher.messages.value.isEmpty())
        assertTrue(dispatcher.permissions.value.isEmpty())
        assertTrue(dispatcher.questions.value.isEmpty())
        assertTrue(dispatcher.todos.value.isEmpty())
    }

    @Test
    fun `clearForServer removes only target server data`() = runTest {
        dispatcher.processEvent(SseEvent.SessionCreated(
            Session(id = "s1", time = Session.Time(1000L, 2000L))
        ), "server1")
        dispatcher.processEvent(SseEvent.SessionCreated(
            Session(id = "s2", time = Session.Time(1000L, 2000L))
        ), "server2")

        dispatcher.clearForServer("server1")

        assertEquals(1, dispatcher.sessions.value.size)
        assertEquals("s2", dispatcher.sessions.value[0].id)
    }

    @Test
    fun `clearForServer removes messages and parts for server sessions`() = runTest {
        val session = testSession("s1")
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        dispatcher.processEvent(SseEvent.MessageUpdated(msg), "server1")
        dispatcher.processEvent(SseEvent.MessagePartUpdated(part), "server1")

        dispatcher.clearForServer("server1")

        assertTrue(dispatcher.messages.value.isEmpty())
        assertTrue(dispatcher.parts.value.isEmpty())
    }

    @Test
    fun `clearForServer with no sessions removes server entry`() = runTest {
        dispatcher.clearForServer("nonexistent-server")
        assertFalse(dispatcher.serverSessions.value.containsKey("nonexistent-server"))
    }

    // ============ Delegated Operations ============

    @Test
    fun `delegated setMessages works`() {
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        val part = Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "hi")

        dispatcher.setMessages("s1", listOf(MessageWithParts(msg, listOf(part))))

        assertEquals(listOf(msg), dispatcher.messages.value["s1"])
        assertEquals(listOf(part), dispatcher.parts.value["m1"])
    }

    @Test
    fun `delegated mergeMessages works`() {
        val existing = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        dispatcher.setMessages("s1", listOf(MessageWithParts(existing, emptyList())))

        val newMsg = Message.User(id = "m2", sessionId = "s1", time = TimeInfo(2000L))
        dispatcher.mergeMessages("s1", listOf(MessageWithParts(newMsg, emptyList())))

        assertEquals(1, dispatcher.messages.value["s1"]!!.size)
        assertEquals("m2", dispatcher.messages.value["s1"]!![0].id)
    }

    @Test
    fun `delegated removePermission works`() {
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        dispatcher.processEvent(perm, "server1")

        dispatcher.removePermission("p1")

        assertTrue(dispatcher.permissions.value["s1"]!!.isEmpty())
    }

    @Test
    fun `delegated removeQuestion works`() {
        val q = SseEvent.QuestionAsked(id = "q1", sessionId = "s1", questions = listOf(
            SseEvent.QuestionAsked.Question("H", "Q?", options = emptyList())
        ))
        dispatcher.processEvent(q, "server1")

        dispatcher.removeQuestion("q1")

        assertTrue(dispatcher.questions.value["s1"]!!.isEmpty())
    }

    @Test
    fun `delegated setPermissions works`() {
        val perm = SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash")
        dispatcher.setPermissions("s1", listOf(perm))
        assertEquals(listOf(perm), dispatcher.permissions.value["s1"])
    }

    @Test
    fun `delegated setQuestions works`() {
        val q = SseEvent.QuestionAsked(id = "q1", sessionId = "s1", questions = listOf(
            SseEvent.QuestionAsked.Question("H", "Q?", options = emptyList())
        ))
        dispatcher.setQuestions("s1", listOf(q))
        assertEquals(listOf(q), dispatcher.questions.value["s1"])
    }

    @Test
    fun `delegated setSessions works`() {
        val s1 = testSession("s1")
        val s2 = testSession("s2")
        dispatcher.setSessions("server1", listOf(s1, s2))
        assertEquals(2, dispatcher.sessions.value.size)
        assertTrue(dispatcher.serverSessions.value["server1"]!!.contains("s1"))
    }

    // ============ Initial State ============

    @Test
    fun `initial state is empty`() = runTest {
        assertTrue(dispatcher.sessions.value.isEmpty())
        assertTrue(dispatcher.sessionStatuses.value.isEmpty())
        assertTrue(dispatcher.messages.value.isEmpty())
        assertTrue(dispatcher.parts.value.isEmpty())
        assertTrue(dispatcher.serverSessions.value.isEmpty())
        assertTrue(dispatcher.permissions.value.isEmpty())
        assertTrue(dispatcher.questions.value.isEmpty())
        assertTrue(dispatcher.todos.value.isEmpty())
        assertNull(dispatcher.vcsBranch.value)
        assertNull(dispatcher.projectInfo.value)
    }

    // ============ No-op Events ============

    @Test
    fun `ServerHeartbeat does not change state`() = runTest {
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "server1")
        dispatcher.processEvent(SseEvent.ServerHeartbeat, "server1")
        assertEquals(1, dispatcher.sessions.value.size)
    }

    @Test
    fun `LspUpdated does not change state`() = runTest {
        dispatcher.processEvent(SseEvent.LspUpdated, "server1")
        assertTrue(dispatcher.sessions.value.isEmpty())
    }

    @Test
    fun `SessionError does not change sessions`() = runTest {
        dispatcher.processEvent(SseEvent.SessionError("s1", "something failed"), "server1")
        assertTrue(dispatcher.sessions.value.isEmpty())
    }

    // ============ SessionNext Event Integration ============

    @Test
    fun `SessionNext event routed to SessionNextHandler`() = runTest {
        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        assertEquals("code", dispatcher.currentAgent.value["s1"])
    }

    @Test
    fun `SessionDeleted cascades cleanup to SessionNextHandler`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")

        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        dispatcher.processEvent(SseEvent.SessionDeleted(session), "server1")

        assertNull(dispatcher.currentAgent.value["s1"])
    }

    @Test
    fun `SessionNext tool progress tracked`() = runTest {
        val toolEvent = SessionNextEvent.ToolInputStarted(
            sessionId = "s1", messageId = "m1", partId = "p1",
            callId = "c1", tool = "bash"
        )
        dispatcher.processEvent(SseEvent.SessionNext(toolEvent), "server1")

        val tools = dispatcher.activeToolProgress.value["s1"]
        assertNotNull(tools)
        assertEquals(1, tools!!.size)
        assertEquals("bash", tools[0].tool)
    }

    @Test
    fun `clearAll resets SessionNextHandler`() = runTest {
        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        dispatcher.clearAll()

        assertTrue(dispatcher.currentAgent.value.isEmpty())
        assertTrue(dispatcher.activeToolProgress.value.isEmpty())
    }

    @Test
    fun `clearForServer resets SessionNextHandler for server sessions`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        val agentEvent = SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        dispatcher.processEvent(SseEvent.SessionNext(agentEvent), "server1")

        dispatcher.clearForServer("server1")

        assertNull(dispatcher.currentAgent.value["s1"])
    }

    // ============ Multi-Server Deduplication (Same Backend) ============

    @Test
    fun `second server events for claimed session are skipped`() = runTest {
        val session = testSession("s1")
        // Server1 claims ownership
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")

        // Server2 sends update for same session — should be skipped
        dispatcher.processEvent(
            SseEvent.SessionUpdated(session.copy(title = "From Server2")), "server2"
        )

        assertEquals("Test", dispatcher.sessions.value.first().title)
    }

    @Test
    fun `MessagePartDelta not doubled from second server`() = runTest {
        // Server1 claims ownership and sends a delta
        dispatcher.processEvent(
            SseEvent.MessagePartDelta(
                sessionId = "s1", messageId = "m1", partId = "p1",
                field = "text", delta = "Hello"
            ), "server1"
        )
        messageHandler.forceFlushDeltas()

        // Server2 sends same delta — should be skipped by ownership check
        dispatcher.processEvent(
            SseEvent.MessagePartDelta(
                sessionId = "s1", messageId = "m1", partId = "p1",
                field = "text", delta = "Hello"
            ), "server2"
        )
        messageHandler.forceFlushDeltas()

        val parts = dispatcher.parts.value["m1"].orEmpty()
        val textPart = parts.firstOrNull { it is Part.Text } as? Part.Text
        assertNotNull(textPart)
        // Text must be "Hello" (single application), NOT "HelloHello" (doubled)
        assertEquals("Hello", textPart!!.text)
    }

    @Test
    fun `clearForServer releases ownership allowing another server to claim`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")

        // Server2 blocked while server1 owns s1
        val msg = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        dispatcher.processEvent(SseEvent.MessageUpdated(msg), "server2")
        assertNull(dispatcher.messages.value["s1"])

        // Server1 disconnects → ownership released
        dispatcher.clearForServer("server1")

        // Server2 can now claim s1
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server2")
        dispatcher.processEvent(SseEvent.MessageUpdated(msg), "server2")
        assertEquals(1, dispatcher.messages.value["s1"]?.size)
    }

    @Test
    fun `events without sessionId bypass ownership check`() = runTest {
        // Server1 claims a session
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "server1")

        // Server2's session-less events should NOT be skipped
        dispatcher.processEvent(SseEvent.ServerHeartbeat, "server2")
        dispatcher.processEvent(SseEvent.ServerConnected, "server2")
        dispatcher.processEvent(SseEvent.VcsBranchUpdated("main"), "server2")

        // Server2 can still send events for a DIFFERENT session
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s2")), "server2")
        assertTrue(dispatcher.sessions.value.any { it.id == "s2" })
    }

    @Test
    fun `SessionDeleted releases ownership for that session`() = runTest {
        val session = testSession("s1")
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server1")
        dispatcher.processEvent(SseEvent.SessionDeleted(session), "server1")

        // After deletion, server2 can create a new session with same ID
        dispatcher.processEvent(SseEvent.SessionCreated(session), "server2")
        assertTrue(dispatcher.sessions.value.any { it.id == "s1" })
    }
}
