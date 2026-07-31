package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionNextEventHandlerFullTest {

    private lateinit var handler: SessionNextEventHandler

    @Before
    fun setup() {
        handler = SessionNextEventHandler()
    }

    // ============ Agent Switching - Multi-session & Overwrite ============

    @Test
    fun agentSwitched_multipleSessions_independentState() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )
        assertEquals("code", handler.currentAgent.value["s1"])
        assertEquals("build", handler.currentAgent.value["s2"])
        assertEquals(2, handler.currentAgent.value.size)
    }

    @Test
    fun agentSwitched_sameSessionOverwrites() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "build")
        )
        assertEquals("build", handler.currentAgent.value["s1"])
        assertEquals(1, handler.currentAgent.value.size)
    }

    @Test
    fun modelSwitched_multipleSessions_independentState() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s1", providerId = "anthropic", modelId = "claude-4-sonnet")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s2", providerId = "openai", modelId = "gpt-4o")
        )
        assertEquals("anthropic", handler.currentModel.value["s1"]!!.first)
        assertEquals("openai", handler.currentModel.value["s2"]!!.first)
        assertEquals(2, handler.currentModel.value.size)
    }

    @Test
    fun modelSwitched_sameSessionOverwrites() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s1", providerId = "anthropic", modelId = "claude-4-sonnet")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s1", providerId = "openai", modelId = "gpt-4o")
        )
        assertEquals("openai", handler.currentModel.value["s1"]!!.first)
        assertEquals("gpt-4o", handler.currentModel.value["s1"]!!.second)
    }

    // ============ Tool Progress - Full Lifecycle ============

    @Test
    fun toolProgress_fullLifecycle_startedToRunningToSuccess() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        assertEquals("started", handler.activeToolProgress.value["s1"]!![0].status)

        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "75%", title = "Running tests"
            )
        )
        val updated = handler.activeToolProgress.value["s1"]!![0]
        assertEquals("running", updated.status)
        assertEquals("75%", updated.progress)
        assertEquals("Running tests", updated.title)

        handler.handleSessionNextEvent(
            SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", output = "all passed"
            )
        )
        assertTrue(handler.activeToolProgress.value["s1"]!!.isEmpty())
    }

    @Test
    fun toolProgress_afterSuccess_subsequentUpdateIsNoOp() {
        // Start and complete tool c1
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
        // Try to update already-removed tool
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "still going", title = "ignored"
            )
        )
        // c1 was removed, but s1 entry still exists as empty list
        assertTrue(handler.activeToolProgress.value["s1"]!!.isEmpty())
    }

    @Test
    fun toolProgress_nonExistentCallId_noOp() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "nonexistent", progress = "50%"
            )
        )
        // No session entry should exist since no tool was started
        assertNull(handler.activeToolProgress.value["s1"])
    }

    @Test
    fun toolProgress_nonExistentSession_noOp() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "nonexistent_session", messageId = "m1", partId = "p1",
                callId = "c1", progress = "50%"
            )
        )
        assertNull(handler.activeToolProgress.value["nonexistent_session"])
    }

    @Test
    fun toolProgress_onlyTitle_noProgressString() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = null, title = "Running ls"
            )
        )
        val tool = handler.activeToolProgress.value["s1"]!![0]
        assertEquals("running", tool.status)
        assertNull(tool.progress)
        assertEquals("Running ls", tool.title)
    }

    @Test
    fun toolProgress_onlyProgressString_noTitle() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "60%", title = null
            )
        )
        val tool = handler.activeToolProgress.value["s1"]!![0]
        assertEquals("running", tool.status)
        assertEquals("60%", tool.progress)
        assertNull(tool.title)
    }

    @Test
    fun toolProgress_noProgressAndNoTitle() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1"
            )
        )
        val tool = handler.activeToolProgress.value["s1"]!![0]
        assertEquals("running", tool.status)
        assertNull(tool.progress)
        assertNull(tool.title)
    }

    @Test
    fun toolProgress_multipleSimultaneousTools_sameSession() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p2",
                callId = "c2", tool = "read_file"
            )
        )
        val tools = handler.activeToolProgress.value["s1"]!!
        assertEquals(2, tools.size)
        assertEquals("c1", tools[0].callId)
        assertEquals("bash", tools[0].tool)
        assertEquals("c2", tools[1].callId)
        assertEquals("read_file", tools[1].tool)

        // Progress on first tool only
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "50%", title = "Running"
            )
        )
        val updated = handler.activeToolProgress.value["s1"]!!
        assertEquals(2, updated.size)
        assertEquals("running", updated[0].status)
        assertEquals("started", updated[1].status) // c2 unchanged
    }

    @Test
    fun toolProgress_multipleSimultaneousTools_removeOneKeepsOther() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p2",
                callId = "c2", tool = "read_file"
            )
        )
        // Complete c1 only
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", output = "done"
            )
        )
        val remaining = handler.activeToolProgress.value["s1"]!!
        assertEquals(1, remaining.size)
        assertEquals("c2", remaining[0].callId)
    }

    @Test
    fun toolFailed_fullLifecycle_startedToFailed() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "90%", title = "Almost done"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolFailed(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", error = "timeout"
            )
        )
        assertTrue(handler.activeToolProgress.value["s1"]!!.isEmpty())
    }

    // ============ Tool No-op Events ============

    @Test
    fun toolInputDelta_noStateChange() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputDelta(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", delta = "writing..."
            )
        )
        assertNull(handler.activeToolProgress.value["s1"])
    }

    @Test
    fun toolCalled_noStateChange() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolCalled(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        assertNull(handler.activeToolProgress.value["s1"])
    }

    // ============ Step Progress - Edge Cases ============

    @Test
    fun stepStarted_stepZero_edgeCase() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 0, agent = "init", model = "small"
            )
        )
        val progress = handler.stepProgress.value["s1"]!!
        assertEquals(0, progress.step)
        assertEquals("init", progress.agent)
        assertEquals("small", progress.model)
    }

    @Test
    fun stepStarted_withAgentAndModel() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        val progress = handler.stepProgress.value["s1"]!!
        assertEquals(1, progress.step)
        assertEquals("code", progress.agent)
        assertEquals("claude-4-sonnet", progress.model)
    }

    @Test
    fun stepStarted_thenStepEnded_clearsState() {
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
    fun stepStarted_thenStepFailed_clearsState() {
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

    @Test
    fun stepStarted_multipleOverwrites_sameSession() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m2",
                step = 2, agent = "build", model = "gpt-4o"
            )
        )
        val progress = handler.stepProgress.value["s1"]!!
        assertEquals(2, progress.step)
        assertEquals("build", progress.agent)
        assertEquals("gpt-4o", progress.model)
    }

    @Test
    fun stepEnded_nonExistentSession_safeNoOp() {
        // Should not throw
        handler.handleSessionNextEvent(
            SessionNextEvent.StepEnded(sessionId = "nonexistent", messageId = "m1", step = 1)
        )
        assertNull(handler.stepProgress.value["nonexistent"])
    }

    @Test
    fun stepFailed_nonExistentSession_safeNoOp() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepFailed(sessionId = "nonexistent", messageId = "m1", step = 1, error = "err")
        )
        assertNull(handler.stepProgress.value["nonexistent"])
    }

    @Test
    fun stepStarted_multipleSessions_independent() {
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(
                sessionId = "s2", messageId = "m1",
                step = 3, agent = "build", model = "gpt-4o"
            )
        )
        assertEquals(1, handler.stepProgress.value["s1"]!!.step)
        assertEquals(3, handler.stepProgress.value["s2"]!!.step)
        assertEquals(2, handler.stepProgress.value.size)
    }

    // ============ Compaction State - Edge Cases ============

    @Test
    fun compactionStarted_withReason() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "context full")
        )
        val state = handler.compactionState.value["s1"]!!
        assertTrue(state.isActive)
        assertEquals("context full", state.reason)
    }

    @Test
    fun compactionStarted_emptyReason() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "")
        )
        val state = handler.compactionState.value["s1"]!!
        assertTrue(state.isActive)
        assertEquals("", state.reason)
    }

    @Test
    fun compactionStarted_defaultReason() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1")
        )
        val state = handler.compactionState.value["s1"]!!
        assertTrue(state.isActive)
        assertEquals("", state.reason)
    }

    @Test
    fun compactionStarted_thenCompactionEnded_clearsState() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "context full")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionEnded(sessionId = "s1", messageId = "m1")
        )
        assertNull(handler.compactionState.value["s1"])
    }

    @Test
    fun compactionEnded_nonExistentSession_safeNoOp() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionEnded(sessionId = "nonexistent", messageId = "m1")
        )
        assertNull(handler.compactionState.value["nonexistent"])
    }

    @Test
    fun compactionDelta_noStateChange() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionDelta(sessionId = "s1", messageId = "m1", delta = "compacting...")
        )
        assertNull(handler.compactionState.value["s1"])
    }

    // ============ Shell State ============

    @Test
    fun shellStarted_thenShellEnded_fullLifecycle() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s1", messageId = "m1", partId = "p1", command = "npm test")
        )
        assertEquals("npm test", handler.shellState.value["s1"]!!.command)

        handler.handleSessionNextEvent(
            SessionNextEvent.ShellEnded(sessionId = "s1", messageId = "m1", partId = "p1", exitCode = 0)
        )
        assertNull(handler.shellState.value["s1"])
    }

    @Test
    fun shellEnded_nonExistentSession_safeNoOp() {
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellEnded(sessionId = "nonexistent", messageId = "m1", partId = "p1", exitCode = 1)
        )
        assertNull(handler.shellState.value["nonexistent"])
    }

    // ============ Retried ============

    @Test
    fun retried_setsRetryCount() {
        handler.handleSessionNextEvent(
            SessionNextEvent.Retried(sessionId = "s1", attempt = 3, error = "rate limited")
        )
        assertEquals(3, handler.retryState.value["s1"])
    }

    @Test
    fun retried_defaultAttempt_zero() {
        handler.handleSessionNextEvent(
            SessionNextEvent.Retried(sessionId = "s1")
        )
        assertEquals(0, handler.retryState.value["s1"])
    }

    @Test
    fun retried_multipleAttempts_overwrites() {
        handler.handleSessionNextEvent(
            SessionNextEvent.Retried(sessionId = "s1", attempt = 1)
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.Retried(sessionId = "s1", attempt = 2)
        )
        assertEquals(2, handler.retryState.value["s1"])
        assertEquals(1, handler.retryState.value.size)
    }

    // ============ No-op Events ============

    @Test
    fun prompted_noStateChange() {
        handler.handleSessionNextEvent(
            SessionNextEvent.Prompted(sessionId = "s1", messageId = "m1")
        )
        // Just verify nothing crashes and state is clean
        assertTrue(handler.currentAgent.value.isEmpty())
        assertTrue(handler.activeToolProgress.value.isEmpty())
    }

    @Test
    fun synthetic_noStateChange() {
        handler.handleSessionNextEvent(
            SessionNextEvent.Synthetic(sessionId = "s1", messageId = "m1")
        )
        assertTrue(handler.currentAgent.value.isEmpty())
    }

    @Test
    fun unknown_noStateChange() {
        handler.handleSessionNextEvent(
            SessionNextEvent.Unknown(rawType = "session.next.weird", rawJson = "{}")
        )
        assertTrue(handler.currentAgent.value.isEmpty())
    }

    // ============ Sequence Tracking ============

    @Test
    fun trackSequence_sequentialValues_noGap() {
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 2)
        handler.trackSequence("s1", 3)
        assertEquals(3L, handler.lastEventSeq.value["s1"])
        assertFalse(handler.gapDetected.value.contains("s1"))
    }

    @Test
    fun trackSequence_gapDetected() {
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 2)
        // Skip 3 — should detect gap
        handler.trackSequence("s1", 5)
        assertTrue(handler.gapDetected.value.contains("s1"))
        assertEquals(5L, handler.lastEventSeq.value["s1"])
    }

    @Test
    fun trackSequence_gapDetected_multipleSessions() {
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 3) // gap of 1
        handler.trackSequence("s2", 10)
        handler.trackSequence("s2", 11)
        handler.trackSequence("s2", 20) // gap

        assertTrue(handler.gapDetected.value.contains("s1"))
        assertTrue(handler.gapDetected.value.contains("s2"))
        assertEquals(2, handler.gapDetected.value.size)
    }

    @Test
    fun trackSequence_firstEvent_noGap() {
        handler.trackSequence("s1", 100)
        assertEquals(100L, handler.lastEventSeq.value["s1"])
        assertFalse(handler.gapDetected.value.contains("s1"))
    }

    @Test
    fun clearGap_removesGapFlag() {
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 5) // gap
        assertTrue(handler.gapDetected.value.contains("s1"))

        handler.clearGap("s1")
        assertFalse(handler.gapDetected.value.contains("s1"))
    }

    @Test
    fun clearGap_nonExistentSession_safeNoOp() {
        handler.clearGap("nonexistent")
        assertFalse(handler.gapDetected.value.contains("nonexistent"))
    }

    // ============ Cleanup - clearForServer ============

    @Test
    fun clearForServer_removesMultipleSessions() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s3", agent = "review")
        )

        handler.clearForServer(setOf("s1", "s2"))

        assertNull(handler.currentAgent.value["s1"])
        assertNull(handler.currentAgent.value["s2"])
        assertEquals("review", handler.currentAgent.value["s3"])
    }

    @Test
    fun clearForServer_emptySet_noOp() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.clearForServer(emptySet())
        assertEquals("code", handler.currentAgent.value["s1"])
    }

    // ============ Cleanup - clearForSession comprehensive ============

    @Test
    fun clearForSession_clearsAllStateTypes() {
        // Populate all state types for session "s1"
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ModelSwitched(sessionId = "s1", providerId = "anthropic", modelId = "claude-4-sonnet")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(sessionId = "s1", messageId = "m1", step = 1)
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s1", messageId = "m1", partId = "p1", command = "npm test")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "full")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.Retried(sessionId = "s1", attempt = 2)
        )
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 5) // creates gap

        handler.clearForSession("s1")

        assertNull(handler.currentAgent.value["s1"])
        assertNull(handler.currentModel.value["s1"])
        assertNull(handler.activeToolProgress.value["s1"])
        assertNull(handler.stepProgress.value["s1"])
        assertNull(handler.shellState.value["s1"])
        assertNull(handler.compactionState.value["s1"])
        assertNull(handler.retryState.value["s1"])
        assertNull(handler.lastEventSeq.value["s1"])
        assertFalse(handler.gapDetected.value.contains("s1"))
    }

    @Test
    fun clearForSession_doesNotAffectOtherSessions() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )

        handler.clearForSession("s1")

        assertNull(handler.currentAgent.value["s1"])
        assertEquals("build", handler.currentAgent.value["s2"])
    }

    // ============ Cleanup - clearAll comprehensive ============

    @Test
    fun clearAll_resetsAllStateIncludingRetryAndSequence() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.Retried(sessionId = "s1", attempt = 3)
        )
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 5)

        handler.clearAll()

        assertTrue(handler.currentAgent.value.isEmpty())
        assertTrue(handler.currentModel.value.isEmpty())
        assertTrue(handler.activeToolProgress.value.isEmpty())
        assertTrue(handler.stepProgress.value.isEmpty())
        assertTrue(handler.compactionState.value.isEmpty())
        assertTrue(handler.shellState.value.isEmpty())
        assertTrue(handler.retryState.value.isEmpty())
        assertTrue(handler.lastEventSeq.value.isEmpty())
        assertTrue(handler.gapDetected.value.isEmpty())
    }

    // ============ Cross-cutting: Multiple sessions independent ============

    @Test
    fun multipleSessions_haveIndependentState() {
        // Session 1: agent, tool, step
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.StepStarted(sessionId = "s1", messageId = "m1", step = 1)
        )

        // Session 2: different state
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.ShellStarted(sessionId = "s2", messageId = "m1", partId = "p1", command = "ls")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s2", messageId = "m1", reason = "full")
        )

        // Verify independence
        assertEquals("code", handler.currentAgent.value["s1"])
        assertEquals("build", handler.currentAgent.value["s2"])
        assertEquals(1, handler.activeToolProgress.value["s1"]!!.size)
        assertNull(handler.activeToolProgress.value["s2"])
        assertNotNull(handler.stepProgress.value["s1"])
        assertNull(handler.stepProgress.value["s2"])
        assertNull(handler.shellState.value["s1"])
        assertNotNull(handler.shellState.value["s2"])
        assertNull(handler.compactionState.value["s1"])
        assertNotNull(handler.compactionState.value["s2"])
    }

    @Test
    fun multipleSessions_clearOneDoesNotAffectOther() {
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "build")
        )
        handler.trackSequence("s1", 1)
        handler.trackSequence("s2", 10)

        handler.clearForSession("s1")

        assertNull(handler.currentAgent.value["s1"])
        assertNull(handler.lastEventSeq.value["s1"])
        assertEquals("build", handler.currentAgent.value["s2"])
        assertEquals(10L, handler.lastEventSeq.value["s2"])
    }

    // ============ Tool complete removes tool but leaves session entry ============

    @Test
    fun toolComplete_allToolsRemoved_sessionEntryStillPresentAsEmptyList() {
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
        // Session entry should still exist but be empty
        assertNotNull(handler.activeToolProgress.value["s1"])
        assertTrue(handler.activeToolProgress.value["s1"]!!.isEmpty())
    }

    // ============ Track sequence per session ============

    @Test
    fun trackSequence_perSessionIndependent() {
        handler.trackSequence("s1", 1)
        handler.trackSequence("s1", 2)
        handler.trackSequence("s2", 100)

        assertEquals(2L, handler.lastEventSeq.value["s1"])
        assertEquals(100L, handler.lastEventSeq.value["s2"])
    }

    @Test
    fun trackSequence_singleEvent_noGap() {
        handler.trackSequence("s1", 42)
        assertEquals(42L, handler.lastEventSeq.value["s1"])
        assertFalse(handler.gapDetected.value.contains("s1"))
    }

    @Test
    fun trackSequence_adjacentNumbers_noGap() {
        handler.trackSequence("s1", 10)
        handler.trackSequence("s1", 11)
        assertFalse(handler.gapDetected.value.contains("s1"))
    }
}
