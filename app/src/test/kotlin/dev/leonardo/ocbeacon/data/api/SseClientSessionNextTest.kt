package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SseClientSessionNextTest {

    private lateinit var json: Json
    private lateinit var sseClient: SseClient

    @Before
    fun setup() {
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        sseClient = SseClient(
            httpClient = io.ktor.client.HttpClient(),
            json = json
        )
    }

    // ============ session.next.* type routing ============

    @Test
    fun `parseSessionNextEvent routes agent switched`() {
        val result = sseClient.parseSessionNextEvent("session.next.agent.switched", buildProps(
            "sessionID" to "s1",
            "agent" to "code"
        ))
        assertTrue(result is SessionNextEvent.AgentSwitched)
        assertEquals("s1", (result as SessionNextEvent.AgentSwitched).sessionId)
        assertEquals("code", result.agent)
    }

    @Test
    fun `parseSessionNextEvent routes text delta`() {
        val result = sseClient.parseSessionNextEvent("session.next.text.delta", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p1",
            "delta" to "hello world"
        ))
        assertTrue(result is SessionNextEvent.TextDelta)
        assertEquals("hello world", (result as SessionNextEvent.TextDelta).delta)
    }

    @Test
    fun `parseSessionNextEvent routes tool progress`() {
        val result = sseClient.parseSessionNextEvent("session.next.tool.progress", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p1",
            "callID" to "c1",
            "progress" to "50%",
            "title" to "Running..."
        ))
        assertTrue(result is SessionNextEvent.ToolProgress)
        assertEquals("50%", (result as SessionNextEvent.ToolProgress).progress)
    }

    @Test
    fun `parseSessionNextEvent routes step started`() {
        val result = sseClient.parseSessionNextEvent("session.next.step.started", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "step" to 1,
            "agent" to "code",
            "model" to "claude-4-sonnet"
        ))
        assertTrue(result is SessionNextEvent.StepStarted)
        assertEquals(1, (result as SessionNextEvent.StepStarted).step)
        assertEquals("code", result.agent)
    }

    @Test
    fun `parseSessionNextEvent returns Unknown for unrecognized type`() {
        val result = sseClient.parseSessionNextEvent("session.next.foo.bar", buildProps(
            "sessionID" to "s1"
        ))
        assertTrue(result is SessionNextEvent.Unknown)
        assertEquals("session.next.foo.bar", (result as SessionNextEvent.Unknown).rawType)
    }

    @Test
    fun `parseSessionNextEvent handles shell events`() {
        val started = sseClient.parseSessionNextEvent("session.next.shell.started", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p4",
            "command" to "npm test"
        ))
        assertTrue(started is SessionNextEvent.ShellStarted)
        assertEquals("npm test", (started as SessionNextEvent.ShellStarted).command)

        val ended = sseClient.parseSessionNextEvent("session.next.shell.ended", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "partID" to "p4",
            "exitCode" to 0
        ))
        assertTrue(ended is SessionNextEvent.ShellEnded)
        assertEquals(0, (ended as SessionNextEvent.ShellEnded).exitCode)
    }

    @Test
    fun `parseSessionNextEvent handles compaction events`() {
        val started = sseClient.parseSessionNextEvent("session.next.compaction.started", buildProps(
            "sessionID" to "s1",
            "messageID" to "m1",
            "reason" to "context full"
        ))
        assertTrue(started is SessionNextEvent.CompactionStarted)
        assertEquals("context full", (started as SessionNextEvent.CompactionStarted).reason)
    }

    @Test
    fun `parseSessionNextEvent handles retried`() {
        val result = sseClient.parseSessionNextEvent("session.next.retried", buildProps(
            "sessionID" to "s1",
            "attempt" to 2,
            "error" to "rate limited"
        ))
        assertTrue(result is SessionNextEvent.Retried)
        assertEquals(2, (result as SessionNextEvent.Retried).attempt)
    }

    private fun buildProps(vararg pairs: Pair<String, Any>): kotlinx.serialization.json.JsonObject {
        val map = pairs.toMap().mapValues { (_, v) ->
            when (v) {
                is String -> kotlinx.serialization.json.JsonPrimitive(v)
                is Int -> kotlinx.serialization.json.JsonPrimitive(v)
                is Long -> kotlinx.serialization.json.JsonPrimitive(v)
                is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
            }
        }
        return kotlinx.serialization.json.JsonObject(map)
    }
}
