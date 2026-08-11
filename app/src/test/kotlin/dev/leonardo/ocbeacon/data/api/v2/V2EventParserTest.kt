package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2EventParser 专项测试——execution 生命周期与 shell 事件映射。
 */
class V2EventParserTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val parser = V2EventParser(json)

    private fun props(jsonText: String) = json.parseToJsonElement(jsonText).jsonObject

    @Test
    fun `execution started maps to SessionStatus Busy`() {
        val event = parser.parse(
            "session.execution.started",
            props("""{"sessionID":"ses_1"}""")
        )
        assertNotNull(event)
        assertTrue("应为 SessionStatus，实际 ${event!!::class.simpleName}", event is SseEvent.SessionStatus)
        val status = event as SseEvent.SessionStatus
        assertEquals("ses_1", status.sessionId)
        assertEquals(SessionStatus.Busy, status.status)
    }

    @Test
    fun `execution succeeded maps to SessionIdle`() {
        val event = parser.parse(
            "session.execution.succeeded",
            props("""{"sessionID":"ses_1"}""")
        )
        assertNotNull(event)
        assertTrue("应为 SessionIdle，实际 ${event!!::class.simpleName}", event is SseEvent.SessionIdle)
        assertEquals("ses_1", (event as SseEvent.SessionIdle).sessionId)
    }

    @Test
    fun `shell created maps to ShellJobStarted`() {
        // V2 实测：服务器广播 shell.created（旧事件名），payload {info: Shell.Info}
        val event = parser.parse(
            "shell.created",
            props("""{"info":{"id":"sh_1","status":"running","command":"echo hi","cwd":"/home","shell":"bash","file":"/tmp/out","time":{"start":1000}}}""")
        )
        assertNotNull(event)
        assertTrue("应为 ShellJobStarted，实际 ${event!!::class.simpleName}", event is SseEvent.ShellJobStarted)
        val started = event as SseEvent.ShellJobStarted
        assertEquals("sh_1", started.info.id)
        assertEquals("running", started.info.status)
        assertEquals("echo hi", started.info.command)
    }

    @Test
    fun `shell exited maps to ShellJobEnded with exit code`() {
        val event = parser.parse(
            "shell.exited",
            props("""{"id":"sh_1","exit":0,"status":"exited"}""")
        )
        assertNotNull(event)
        assertTrue("应为 ShellJobEnded，实际 ${event!!::class.simpleName}", event is SseEvent.ShellJobEnded)
        val ended = event as SseEvent.ShellJobEnded
        assertEquals("sh_1", ended.info.id)
        assertEquals(0, ended.info.exit)
        assertEquals("exited", ended.info.status)
    }

    @Test
    fun `session shell started maps to ShellJobStarted`() {
        // 新命名事件（兼容路径）：{shell: Shell.Info}
        val event = parser.parse(
            "session.shell.started",
            props("""{"sessionID":"ses_1","shell":{"id":"sh_2","status":"running","command":"npm test","cwd":"/p","shell":"bash","file":"/o","metadata":{"sessionID":"ses_1"},"time":{"start":2000}}}""")
        )
        assertNotNull(event)
        val started = event as SseEvent.ShellJobStarted
        assertEquals("sh_2", started.info.id)
        assertEquals("ses_1", started.info.sessionId)
    }

    @Test
    fun `unhandled event falls back to SessionNext Unknown`() {
        val event = parser.parse(
            "session.usage.updated",
            props("""{"sessionID":"ses_1","cost":{"total":1.5}}""")
        )
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionNext)
        val unknown = (event as SseEvent.SessionNext).event as? dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Unknown
        assertEquals("session.usage.updated", unknown?.rawType)
    }
}
