package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent.ToolOutputContent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionNextEventHandlerTest {

    private lateinit var handler: SessionNextEventHandler

    @Before
    fun setup() {
        handler = SessionNextEventHandler()
    }

    // ============ Agent / Model State ============

    @Test
    fun `AgentSwitched updates currentAgent`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        assertEquals("code", handler.currentAgent.value["s1"])
    }

    @Test
    fun `ModelSwitched updates currentModel`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s1", providerId = "anthropic", modelId = "claude-4-sonnet")
        )
        val model = handler.currentModel.value["s1"]
        assertNotNull(model)
        assertEquals("anthropic", model!!.first)
        assertEquals("claude-4-sonnet", model.second)
    }

    // ============ Tool Progress ============

    @Test
    fun `ToolInputStarted tracks running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]
        assertNotNull(tools)
        assertEquals(1, tools!!.size)
        assertEquals("bash", tools[0].tool)
        assertEquals("c1", tools[0].callId)
        assertEquals("started", tools[0].status)
    }

    @Test
    fun `ToolProgress updates running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "50%", title = "Running ls"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]!!
        assertEquals("50%", tools[0].progress)
        assertEquals("Running ls", tools[0].title)
    }

    @Test
    fun `ToolProgress accumulates content into output`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1", callId = "c1",
                content = listOf(ToolOutputContent(text = "line1\n"))
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1", callId = "c1",
                content = listOf(ToolOutputContent(text = "line2\n"))
            )
        )
        val tool = handler.activeToolProgress.value["s1"]!![0]
        assertEquals("line1\nline2\n", tool.output)
    }

    @Test
    fun `ToolSuccess removes running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", output = "done"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]
        assertEquals(0, tools!!.size)
    }

    @Test
    fun `ToolFailed removes running tool`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolFailed(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", error = "crashed"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]
        assertEquals(0, tools!!.size)
    }

    // ============ Step Progress ============

    @Test
    fun `StepStarted tracks current step`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        val progress = handler.stepProgress.value["s1"]
        assertNotNull(progress)
        assertEquals(1, progress!!.step)
        assertEquals("code", progress.agent)
        assertEquals("claude-4-sonnet", progress.model)
    }

    @Test
    fun `StepEnded clears step progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepEnded(sessionId = "s1", messageId = "m1", step = 1)
        )
        assertNull(handler.stepProgress.value["s1"])
    }

    @Test
    fun `StepFailed clears step progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepFailed(sessionId = "s1", messageId = "m1", step = 1, error = "timeout")
        )
        assertNull(handler.stepProgress.value["s1"])
    }

    // ============ Compaction State ============

    @Test
    fun `CompactionStarted sets compaction progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "context full")
        )
        val state = handler.compactionState.value["s1"]
        assertNotNull(state)
        assertTrue(state!!.isActive)
        assertEquals("context full", state.reason)
    }

    @Test
    fun `CompactionEnded clears compaction progress`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "context full")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionEnded(sessionId = "s1", messageId = "m1")
        )
        assertNull(handler.compactionState.value["s1"])
    }

    // ============ Shell State ============

    @Test
    fun `ShellStarted sets shell state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s1", messageId = "m1", partId = "p4", command = "npm test")
        )
        val state = handler.shellState.value["s1"]
        assertNotNull(state)
        assertEquals("npm test", state!!.command)
    }

    @Test
    fun `ShellEnded clears shell state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s1", messageId = "m1", partId = "p4", command = "npm test")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellEnded(sessionId = "s1", messageId = "m1", partId = "p4", exitCode = 0)
        )
        assertNull(handler.shellState.value["s1"])
    }

    // ============ SseEventHandler Integration ============

    @Test
    fun `handle returns true for SessionNext event`() {
        val event = dev.leonardo.ocbeacon.domain.model.SseEvent.SessionNext(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        assertTrue(handler.handle(event, "server1"))
    }

    @Test
    fun `handle returns false for non-SessionNext event`() {
        val event = dev.leonardo.ocbeacon.domain.model.SseEvent.ServerHeartbeat
        assertFalse(handler.handle(event, "server1"))
    }

    // ============ Cleanup ============

    @Test
    fun `clearForSession removes all session state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(sessionId = "s1", messageId = "m1", step = 1)
        )

        handler.clearForSession("s1")

        assertNull(handler.currentAgent.value["s1"])
        assertNull(handler.stepProgress.value["s1"])
    }

    @Test
    fun `clearAll removes everything`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )

        handler.clearAll()

        assertTrue(handler.currentAgent.value.isEmpty())
        assertTrue(handler.currentModel.value.isEmpty())
        assertTrue(handler.activeToolProgress.value.isEmpty())
        assertTrue(handler.stepProgress.value.isEmpty())
        assertTrue(handler.compactionState.value.isEmpty())
        assertTrue(handler.shellState.value.isEmpty())
    }
}
