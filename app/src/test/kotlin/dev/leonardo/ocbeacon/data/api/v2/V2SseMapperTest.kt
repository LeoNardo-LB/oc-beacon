package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2SseMapper 映射测试——用 2026-08-11 实测样本（docs/superpowers/specs §3.2）。
 */
class V2SseMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun props(text: String) = json.parseToJsonElement(text).jsonObject

    @Test
    fun `input admitted seeds user message`() {
        val event = V2SseMapper.map(
            "session.input.admitted",
            props("""{"sessionID":"ses_1","inputID":"msg_user_1","input":{"type":"user","data":{"text":"hello"},"delivery":"steer"}}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val user = updated.info as Message.User
        assertEquals("msg_user_1", user.id)
        assertEquals("ses_1", user.sessionId)
        assertEquals("hello", user.summary?.body)
    }

    @Test
    fun `input admitted seeds user message with new contract (id + prompt)`() {
        // 2026-08-14 过渡契约（官方 schema 实证，next-171xx）：
        // {admittedSeq, id, sessionID, prompt:{text,files,agents}, delivery, timeCreated}
        val event = V2SseMapper.map(
            "session.input.admitted",
            props("""{"admittedSeq":1,"id":"msg_user_new","sessionID":"ses_1","prompt":{"text":"新契约消息","files":[],"agents":[]},"delivery":{},"timeCreated":1755000000000}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val user = updated.info as Message.User
        assertEquals("msg_user_new", user.id)
        assertEquals("ses_1", user.sessionId)
        assertEquals("新契约消息", user.summary?.body)
    }

    @Test
    fun `inbox enqueued seeds user message with latest contract`() {
        // 2026-08-14 最新契约（实测抓帧，next-17403+）：
        // session.inbox.enqueued {sessionID, inboxID, item:{type, payload:{text,agents}, delivery}}
        val event = V2SseMapper.map(
            "session.inbox.enqueued",
            props("""{"sessionID":"ses_1","inboxID":"msg_inbox_1","item":{"type":"user","payload":{"text":"inbox消息","agents":[{"name":"build"}]},"delivery":"steer"}}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val user = updated.info as Message.User
        assertEquals("msg_inbox_1", user.id)
        assertEquals("ses_1", user.sessionId)
        assertEquals("inbox消息", user.summary?.body)
    }

    @Test
    fun `inbox enqueued with missing inboxID returns null`() {
        // 必须有 inboxID——字段缺失时不播种（避免空 id 幽灵消息）
        val event = V2SseMapper.map(
            "session.inbox.enqueued",
            props("""{"sessionID":"ses_1","item":{"type":"user","payload":{"text":"无id"}}}""")
        )
        assertNull(event)
    }

    @Test
    fun `input admitted with new contract but missing id returns null`() {
        // 新契约必须有 id——字段缺失时不应播种（避免空 id 幽灵消息）
        val event = V2SseMapper.map(
            "session.input.admitted",
            props("""{"admittedSeq":1,"sessionID":"ses_1","prompt":{"text":"无id"}}""")
        )
        assertNull(event)
    }

    @Test
    fun `step started creates assistant message`() {
        val event = V2SseMapper.map(
            "session.step.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","agent":"build","model":"glm-5.2","snapshot":"snap_1"}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val assistant = updated.info as Message.Assistant
        assertEquals("msg_asst_1", assistant.id)
        assertEquals("build", assistant.agent)
        assertEquals("glm-5.2", assistant.modelId)
    }

    @Test
    fun `step started parses new model object contract (id + providerID)`() {
        // 2026-08-14 抓帧实证：新版 model 是 {id, providerID, variant} 对象
        val event = V2SseMapper.map(
            "session.step.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","agent":"build","model":{"id":"deepseek-v4-flash","providerID":"deepseek","variant":"max"}}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val assistant = updated.info as Message.Assistant
        assertEquals("deepseek-v4-flash", assistant.modelId)
        assertEquals("deepseek", assistant.providerId)
    }

    @Test
    fun `step ended parses tokens`() {
        // 2026-08-14 抓帧实证：{finish, cost, tokens:{input,output,reasoning,cache:{read,write}}}
        val event = V2SseMapper.map(
            "session.step.ended",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","finish":"tool-calls","cost":0.00099,"tokens":{"input":84,"output":294,"reasoning":866,"cache":{"read":234368,"write":0}}}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val assistant = updated.info as Message.Assistant
        assertEquals(84, assistant.tokens?.input)
        assertEquals(294, assistant.tokens?.output)
        assertEquals(866, assistant.tokens?.reasoning)
        assertEquals(234368, assistant.tokens?.cache?.read)
        assertEquals(0.00099, assistant.cost ?: 0.0, 1e-9)
    }

    @Test
    fun `text delta uses derived partId`() {
        val event = V2SseMapper.map(
            "session.text.delta",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"delta":"Hello"}""")
        )
        assertNotNull(event)
        val delta = event as SseEvent.MessagePartDelta
        assertEquals("msg_asst_1_text_ord_2", delta.partId)
        assertEquals("text", delta.field)
        assertEquals("Hello", delta.delta)
    }

    @Test
    fun `reasoning started creates part with derived id`() {
        val event = V2SseMapper.map(
            "session.reasoning.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1}""")
        )
        assertNotNull(event)
        val partEvent = event as SseEvent.MessagePartUpdated
        val part = partEvent.part as Part.Reasoning
        assertEquals("msg_asst_1_reasoning_ord_1", part.id)
        assertEquals("msg_asst_1", part.messageId)
    }

    @Test
    fun `text ended overwrites with authoritative text`() {
        val event = V2SseMapper.map(
            "session.text.ended",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"text":"Full text"}""")
        )
        assertNotNull(event)
        val partEvent = event as SseEvent.MessagePartUpdated
        val part = partEvent.part as Part.Text
        assertEquals("msg_asst_1_text_ord_2", part.id)
        assertEquals("Full text", part.text)
    }

    @Test
    fun `tool input started creates pending tool part`() {
        val event = V2SseMapper.map(
            "session.tool.input.started",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_1","name":"bash"}""")
        )
        assertNotNull(event)
        val partEvent = event as SseEvent.MessagePartUpdated
        val part = partEvent.part as Part.Tool
        assertEquals("call_1", part.id)
        assertEquals("bash", part.tool)
        assertTrue(part.state is ToolState.Pending)
    }

    @Test
    fun `tool success maps to completed with metadata`() {
        val event = V2SseMapper.map(
            "session.tool.success",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_2",
                "content":[{"type":"text","text":"done"}],
                "metadata":{"sessionID":"ses_child_1","agent":"general"},
                "executed":true,"resultState":{"status":"completed"}}""")
        )
        assertNotNull(event)
        val partEvent = event as SseEvent.MessagePartUpdated
        val part = partEvent.part as Part.Tool
        assertEquals("call_2", part.id)
        assertTrue(part.state is ToolState.Completed)
        val completed = part.state as ToolState.Completed
        assertEquals("done", completed.output)
        // 双写 sessionId/sessionID（subagent 子会话跳转兼容）
        assertEquals(
            "ses_child_1",
            completed.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
        )
        assertEquals(
            "ses_child_1",
            completed.metadata?.get("sessionID")?.jsonPrimitive?.contentOrNull
        )
    }

    @Test
    fun `tool failed maps to error state`() {
        val event = V2SseMapper.map(
            "session.tool.failed",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_3",
                "error":{"type":"aborted","message":"interrupted"}}""")
        )
        assertNotNull(event)
        val partEvent = event as SseEvent.MessagePartUpdated
        val part = partEvent.part as Part.Tool
        assertTrue(part.state is ToolState.Error)
        val error = part.state as ToolState.Error
        assertTrue(error.error.contains("interrupted"))
    }

    @Test
    fun `step ended updates cost without completing`() {
        val event = V2SseMapper.map(
            "session.step.ended",
            props("""{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","finish":"tool-calls",
                "cost":{"total":1.25},"tokens":{"input":100,"output":50}}""")
        )
        assertNotNull(event)
        val updated = event as SseEvent.MessageUpdated
        val assistant = updated.info as Message.Assistant
        assertEquals(1.25, assistant.cost ?: 0.0, 0.001)
        assertNull(assistant.time.completed)
    }

    @Test
    fun `unmapped event returns null`() {
        assertNull(V2SseMapper.map("session.usage.updated", props("""{"sessionID":"ses_1"}""")))
    }
}
