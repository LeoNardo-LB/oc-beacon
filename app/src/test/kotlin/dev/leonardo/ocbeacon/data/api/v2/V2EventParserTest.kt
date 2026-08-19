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
    fun `instructions updated maps to Unknown without throwing`() {
        // 实测（2026-08-11）：data 可能是数组（多条指令）——jsonObject 扩展会抛异常
        val event = parser.parse(
            "session.instructions.updated",
            props("""{"sessionID":"ses_1","data":[{"type":"text","text":"instruction"}]}""")
        )
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionNext)
        val unknown = (event as SseEvent.SessionNext).event as? dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Unknown
        assertEquals("session.instructions.updated", unknown?.rawType)
    }

    @Test
    fun `usage updated maps to UsageUpdated with tokens`() {
        // 2026-08-15：session.usage.updated 已识别（此前 Unknown 丢弃）——
        // 实测 payload {sessionID, cost, tokens:{...}} → UsageUpdated
        // （顶部 context 指示器实时数据源）
        val event = parser.parse(
            "session.usage.updated",
            props("""{"sessionID":"ses_1","cost":0.042,"tokens":{"input":100,"output":20,"reasoning":5,"cache":{"read":300,"write":0}}}""")
        )
        assertNotNull(event)
        val usage = (event as SseEvent.SessionNext).event as dev.leonardo.ocbeacon.domain.model.SessionNextEvent.UsageUpdated
        assertEquals("ses_1", usage.sessionId)
        assertEquals(0.042, usage.cost, 1e-9)
        assertEquals(100, usage.tokens.input)
        assertEquals(125, usage.tokens.contextTotal)
    }

    @Test
    fun `usage updated with object cost or missing tokens does not throw`() {
        // 防御：cost 为对象（历史样本形态）或 tokens 缺失时不抛异常
        val event = parser.parse(
            "session.usage.updated",
            props("""{"sessionID":"ses_1","cost":{"total":1.5}}""")
        )
        assertNotNull(event)
        val usage = (event as SseEvent.SessionNext).event as dev.leonardo.ocbeacon.domain.model.SessionNextEvent.UsageUpdated
        assertEquals(1.5, usage.cost, 1e-9)
        assertEquals(0, usage.tokens.contextTotal)
    }

    @Test
    fun `compaction ended maps to SessionCompacted`() {
        // 2026-08-19：beta-17639 细粒度压缩事件——.ended = 服务器真实完成信号，
        // 映射 SessionCompacted（驱动完成 snackbar + 消息刷新链），而非
        // SessionNext(CompactionEnded)（那是 HTTP 回调合成注入的类型，
        // 复用会导致"本地幂等结束"冒充"真实完成"触发 premature snackbar）
        val event = parser.parse(
            "session.compaction.ended",
            props("""{"sessionID":"ses_1","messageID":"msg_1"}""")
        )
        assertNotNull(event)
        assertTrue("应为 SessionCompacted，实际 ${event!!::class.simpleName}", event is SseEvent.SessionCompacted)
        assertEquals("ses_1", (event as SseEvent.SessionCompacted).sessionId)
    }

    @Test
    fun `compaction delta maps to CompactionDelta`() {
        // 2026-08-19：压缩摘要流式增量（此前落入 Unknown——Unhandled 日志噪音）
        val event = parser.parse(
            "session.compaction.delta",
            props("""{"sessionID":"ses_1","messageID":"msg_1","delta":"summarizing..."}""")
        )
        assertNotNull(event)
        val delta = (event as SseEvent.SessionNext).event as dev.leonardo.ocbeacon.domain.model.SessionNextEvent.CompactionDelta
        assertEquals("ses_1", delta.sessionId)
        assertEquals("msg_1", delta.messageId)
        assertEquals("summarizing...", delta.delta)
    }

    @Test
    fun `unhandled event falls back to SessionNext Unknown`() {
        val event = parser.parse(
            "session.usage.something.else",
            props("""{"sessionID":"ses_1"}""")
        )
        assertNotNull(event)
        assertTrue(event is SseEvent.SessionNext)
        val unknown = (event as SseEvent.SessionNext).event as? dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Unknown
        assertEquals("session.usage.something.else", unknown?.rawType)
    }
}
