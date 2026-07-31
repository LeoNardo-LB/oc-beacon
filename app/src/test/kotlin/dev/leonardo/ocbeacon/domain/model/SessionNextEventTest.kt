package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionNextEventTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ============ Agent/Model Switching ============

    @Test
    fun `AgentSwitched parses correctly`() {
        val eventJson = """{"type":"session.next.agent.switched","sessionID":"s1","agent":"code"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.AgentSwitched)
        assertEquals("s1", (event as SessionNextEvent.AgentSwitched).sessionId)
        assertEquals("code", event.agent)
    }

    @Test
    fun `ModelSwitched parses correctly`() {
        val eventJson = """{"type":"session.next.model.switched","sessionID":"s1","providerID":"anthropic","modelID":"claude-4-sonnet"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ModelSwitched)
        assertEquals("anthropic", (event as SessionNextEvent.ModelSwitched).providerId)
        assertEquals("claude-4-sonnet", event.modelId)
    }

    // ============ Text Streaming ============

    @Test
    fun `TextStarted parses correctly`() {
        val eventJson = """{"type":"session.next.text.started","sessionID":"s1","messageID":"m1","partID":"p1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.TextStarted)
        assertEquals("p1", (event as SessionNextEvent.TextStarted).partId)
    }

    @Test
    fun `TextDelta parses correctly`() {
        val eventJson = """{"type":"session.next.text.delta","sessionID":"s1","messageID":"m1","partID":"p1","delta":"hello"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.TextDelta)
        assertEquals("hello", (event as SessionNextEvent.TextDelta).delta)
    }

    @Test
    fun `TextEnded parses correctly`() {
        val eventJson = """{"type":"session.next.text.ended","sessionID":"s1","messageID":"m1","partID":"p1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.TextEnded)
        assertEquals("p1", (event as SessionNextEvent.TextEnded).partId)
    }

    // ============ Reasoning Streaming ============

    @Test
    fun `ReasoningStarted parses correctly`() {
        val eventJson = """{"type":"session.next.reasoning.started","sessionID":"s1","messageID":"m1","partID":"p2"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ReasoningStarted)
        assertEquals("p2", (event as SessionNextEvent.ReasoningStarted).partId)
    }

    @Test
    fun `ReasoningDelta parses correctly`() {
        val eventJson = """{"type":"session.next.reasoning.delta","sessionID":"s1","messageID":"m1","partID":"p2","delta":"thinking..."}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ReasoningDelta)
        assertEquals("thinking...", (event as SessionNextEvent.ReasoningDelta).delta)
    }

    @Test
    fun `ReasoningEnded parses correctly`() {
        val eventJson = """{"type":"session.next.reasoning.ended","sessionID":"s1","messageID":"m1","partID":"p2"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ReasoningEnded)
        assertEquals("p2", (event as SessionNextEvent.ReasoningEnded).partId)
    }

    // ============ Tool Execution ============

    @Test
    fun `ToolInputStarted parses correctly`() {
        val eventJson = """{"type":"session.next.tool.input.started","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","tool":"bash"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolInputStarted)
        assertEquals("bash", (event as SessionNextEvent.ToolInputStarted).tool)
        assertEquals("c1", event.callId)
    }

    @Test
    fun `ToolInputDelta parses correctly`() {
        val eventJson = """{"type":"session.next.tool.input.delta","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","delta":"{\"command\":\"ls\"}"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolInputDelta)
        assertEquals("c1", (event as SessionNextEvent.ToolInputDelta).callId)
    }

    @Test
    fun `ToolCalled parses correctly`() {
        val eventJson = """{"type":"session.next.tool.called","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","tool":"bash","input":{"command":"ls"}}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolCalled)
        assertEquals("bash", (event as SessionNextEvent.ToolCalled).tool)
    }

    @Test
    fun `ToolProgress parses correctly`() {
        val eventJson = """{"type":"session.next.tool.progress","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","progress":"50%","title":"Running..."}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolProgress)
        assertEquals("50%", (event as SessionNextEvent.ToolProgress).progress)
        assertEquals("Running...", event.title)
    }

    @Test
    fun `ToolProgress parses with content`() {
        val eventJson = """{"type":"session.next.tool.progress","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","progress":"50%","title":"Running...","content":[{"type":"text","text":"line1\n"}]}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolProgress)
        val progress = event as SessionNextEvent.ToolProgress
        assertEquals("c1", progress.callId)
        assertEquals(1, progress.content.size)
        assertEquals("text", progress.content[0].type)
        assertEquals("line1\n", progress.content[0].text)
    }

    @Test
    fun `ToolProgress without content defaults to empty`() {
        val eventJson = """{"type":"session.next.tool.progress","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","progress":"50%"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        val progress = event as SessionNextEvent.ToolProgress
        assertTrue(progress.content.isEmpty())
    }

    @Test
    fun `ToolSuccess parses correctly`() {
        val eventJson = """{"type":"session.next.tool.success","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","output":"done"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolSuccess)
        assertEquals("done", (event as SessionNextEvent.ToolSuccess).output)
    }

    @Test
    fun `ToolFailed parses correctly`() {
        val eventJson = """{"type":"session.next.tool.failed","sessionID":"s1","messageID":"m1","partID":"p3","callID":"c1","error":"crashed"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ToolFailed)
        assertEquals("crashed", (event as SessionNextEvent.ToolFailed).error)
    }

    // ============ Step Lifecycle ============

    @Test
    fun `StepStarted parses correctly`() {
        val eventJson = """{"type":"session.next.step.started","sessionID":"s1","messageID":"m1","step":1,"agent":"code","model":"claude-4-sonnet"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.StepStarted)
        assertEquals(1, (event as SessionNextEvent.StepStarted).step)
        assertEquals("code", event.agent)
        assertEquals("claude-4-sonnet", event.model)
    }

    @Test
    fun `StepEnded parses correctly`() {
        val eventJson = """{"type":"session.next.step.ended","sessionID":"s1","messageID":"m1","step":1}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.StepEnded)
        assertEquals(1, (event as SessionNextEvent.StepEnded).step)
    }

    @Test
    fun `StepFailed parses correctly`() {
        val eventJson = """{"type":"session.next.step.failed","sessionID":"s1","messageID":"m1","step":1,"error":"timeout"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.StepFailed)
        assertEquals("timeout", (event as SessionNextEvent.StepFailed).error)
    }

    // ============ Shell ============

    @Test
    fun `ShellStarted parses correctly`() {
        val eventJson = """{"type":"session.next.shell.started","sessionID":"s1","messageID":"m1","partID":"p4","command":"npm test"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ShellStarted)
        assertEquals("npm test", (event as SessionNextEvent.ShellStarted).command)
    }

    @Test
    fun `ShellEnded parses correctly`() {
        val eventJson = """{"type":"session.next.shell.ended","sessionID":"s1","messageID":"m1","partID":"p4","exitCode":0}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.ShellEnded)
        assertEquals(0, (event as SessionNextEvent.ShellEnded).exitCode)
    }

    // ============ Compaction ============

    @Test
    fun `CompactionStarted parses correctly`() {
        val eventJson = """{"type":"session.next.compaction.started","sessionID":"s1","messageID":"m1","reason":"context full"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.CompactionStarted)
        assertEquals("context full", (event as SessionNextEvent.CompactionStarted).reason)
    }

    @Test
    fun `CompactionDelta parses correctly`() {
        val eventJson = """{"type":"session.next.compaction.delta","sessionID":"s1","messageID":"m1","delta":"compacting..."}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.CompactionDelta)
        assertEquals("compacting...", (event as SessionNextEvent.CompactionDelta).delta)
    }

    @Test
    fun `CompactionEnded parses correctly`() {
        val eventJson = """{"type":"session.next.compaction.ended","sessionID":"s1","messageID":"m1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.CompactionEnded)
        assertEquals("s1", (event as SessionNextEvent.CompactionEnded).sessionId)
    }

    // ============ Other ============

    @Test
    fun `Prompted parses correctly`() {
        val eventJson = """{"type":"session.next.prompted","sessionID":"s1","messageID":"m1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.Prompted)
    }

    @Test
    fun `Retried parses correctly`() {
        val eventJson = """{"type":"session.next.retried","sessionID":"s1","attempt":2,"error":"rate limited"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.Retried)
        assertEquals(2, (event as SessionNextEvent.Retried).attempt)
        assertEquals("rate limited", event.error)
    }

    @Test
    fun `Synthetic parses correctly`() {
        val eventJson = """{"type":"session.next.synthetic","sessionID":"s1","messageID":"m1"}"""
        val event = json.decodeFromString<SessionNextEvent>(eventJson)
        assertTrue(event is SessionNextEvent.Synthetic)
    }

    @Test
    fun `Unknown event preserves raw type`() {
        // Unknown events are created by the parser, not directly deserialized
        val event = SessionNextEvent.Unknown("session.next.foo.bar", "{}")
        assertEquals("session.next.foo.bar", event.rawType)
        assertEquals("{}", event.rawJson)
    }

    // ============ Discriminator ============

    @Test
    fun `type discriminator selects correct variant`() {
        val json1 = """{"type":"session.next.agent.switched","sessionID":"s1","agent":"code"}"""
        val event1 = json.decodeFromString<SessionNextEvent>(json1)
        assertTrue(event1 is SessionNextEvent.AgentSwitched)
    }

    @Test
    fun `sessionId is present on all events`() {
        val variants = listOf(
            """{"type":"session.next.agent.switched","sessionID":"s1","agent":"code"}""",
            """{"type":"session.next.model.switched","sessionID":"s1","providerID":"p","modelID":"m"}""",
            """{"type":"session.next.text.started","sessionID":"s1","messageID":"m1","partID":"p1"}"""
        )
        for (jsonStr in variants) {
            val event = json.decodeFromString<SessionNextEvent>(jsonStr)
            assertEquals("s1", event.sessionId)
        }
    }
}
