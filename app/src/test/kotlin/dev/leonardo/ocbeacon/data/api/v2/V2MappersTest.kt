package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * V2 JSON → 域模型映射测试。
 * 验证 V2SessionMapper 和 V2MessageMapper 的字段映射正确性。
 */
class V2MappersTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ============ V2ResponseWrapper ============

    @Test
    fun `unwrap extracts data field from V2 response`() {
        val root = json.parseToJsonElement("""{"data":{"id":"x"}}""").jsonObject
        val unwrapped = V2ResponseWrapper.unwrap(root)
        assertEquals("x", unwrapped["id"]?.toString()?.trim('"'))
    }

    @Test
    fun `unwrap returns root when no data field`() {
        val root = json.parseToJsonElement("""{"id":"x"}""").jsonObject
        val unwrapped = V2ResponseWrapper.unwrap(root)
        assertEquals(root, unwrapped)
    }

    @Test
    fun `unwrapList extracts data array and cursor next`() {
        val root = json.parseToJsonElement(
            """{"data":[{"id":"1"},{"id":"2"}],"cursor":{"previous":null,"next":"abc"}}"""
        ).jsonObject
        val (items, nextCursor) = V2ResponseWrapper.unwrapList(root)
        assertEquals(2, items.size)
        assertEquals("abc", nextCursor)
    }

    @Test
    fun `unwrapList handles empty data array`() {
        val root = json.parseToJsonElement("""{"data":[]}""").jsonObject
        val (items, nextCursor) = V2ResponseWrapper.unwrapList(root)
        assertTrue(items.isEmpty())
        assertNull(nextCursor)
    }

    // ============ V2SessionMapper ============

    @Test
    fun `toSession maps all V2 session fields`() {
        val obj = json.parseToJsonElement("""
            {"id":"sess_123","projectID":"prj_1","title":"Test Session",
             "time":{"created":1000,"updated":2000},
             "location":{"directory":"/home/user/project"},
             "agent":"build","cost":0.05,
             "model":{"id":"gpt-4","providerID":"openai","variant":"reasoning"},
             "tokens":{"input":100,"output":200,"reasoning":50,"cache":{"read":10,"write":5}}}
        """).jsonObject

        val session = V2SessionMapper.toSession(obj)
        assertEquals("sess_123", session.id)
        assertEquals("prj_1", session.projectId)
        assertEquals("Test Session", session.title)
        assertEquals("/home/user/project", session.directory)
        assertEquals(1000L, session.time.created)
        assertEquals(2000L, session.time.updated)
        assertEquals("build", session.agent)
        assertEquals(0.05, session.cost!!, 0.001)
        assertEquals("gpt-4", session.model?.id)
        assertEquals("openai", session.model?.providerId)
        assertEquals(100, session.tokens?.input)
        assertEquals(200, session.tokens?.output)
        assertEquals(10, session.tokens?.cache?.read)
    }

    @Test
    fun `toSession handles minimal session with only required fields`() {
        val obj = json.parseToJsonElement("""
            {"id":"sess_min","time":{"created":0,"updated":0},"location":{"directory":""}}
        """).jsonObject

        val session = V2SessionMapper.toSession(obj)
        assertEquals("sess_min", session.id)
        assertEquals(0L, session.time.created)
        assertNull(session.title)
        assertNull(session.agent)
        assertNull(session.cost)
        assertNull(session.model)
        assertNull(session.tokens)
    }

    @Test
    fun `toSession maps archived time`() {
        val obj = json.parseToJsonElement("""
            {"id":"sess_1","time":{"created":1000,"updated":2000,"archived":3000},"location":{"directory":"/p"}}
        """).jsonObject

        val session = V2SessionMapper.toSession(obj)
        assertEquals(3000L, session.time.archived)
        assertTrue(session.isArchived)
    }

    // ============ V2MessageMapper ============

    @Test
    fun `toMessageWithParts maps user message with text`() {
        val obj = json.parseToJsonElement("""
            {"type":"user","id":"msg_u1","time":{"created":1000},"text":"Hello world"}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertEquals("msg_u1", result.info.id)
        assertEquals("sess_1", result.info.sessionId)
        assertTrue(result.info is Message.User)
        // V2 用户消息文本映射为 Part.Text
        assertEquals(1, result.parts.size)
        val textPart = result.parts[0] as Part.Text
        assertEquals("Hello world", textPart.text)
    }

    @Test
    fun `toMessageWithParts maps assistant message with content array`() {
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a1","time":{"created":1000},
             "agent":"build","model":{"id":"gpt-4","providerID":"openai"},
             "content":[
               {"type":"text","text":"Here is the answer"},
               {"type":"reasoning","text":"Thinking about it"},
               {"type":"tool","id":"tool_1","name":"read_file","state":{"status":"completed"}}
             ]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertEquals("msg_a1", result.info.id)
        assertTrue(result.info is Message.Assistant)
        val assistant = result.info as Message.Assistant
        assertEquals("build", assistant.agent)
        assertEquals("gpt-4", assistant.modelId)

        // 3 content items → 3 parts
        assertEquals(3, result.parts.size)
        assertTrue(result.parts[0] is Part.Text)
        assertTrue(result.parts[1] is Part.Reasoning)
        assertTrue(result.parts[2] is Part.Tool)

        val textPart = result.parts[0] as Part.Text
        assertEquals("Here is the answer", textPart.text)
        val reasoningPart = result.parts[1] as Part.Reasoning
        assertEquals("Thinking about it", reasoningPart.text)
        val toolPart = result.parts[2] as Part.Tool
        assertEquals("read_file", toolPart.tool)
        assertTrue(toolPart.state is dev.leonardo.ocbeacon.domain.model.ToolState.Completed)
    }

    @Test
    fun `toMessageWithParts maps system message`() {
        val obj = json.parseToJsonElement("""
            {"type":"system","id":"msg_s1","time":{"created":1000},"text":"System notification"}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertEquals("msg_s1", result.info.id)
    }

    @Test
    fun `toMessageWithParts maps synthetic message`() {
        val obj = json.parseToJsonElement("""
            {"type":"synthetic","id":"msg_syn1","time":{"created":1000},"text":"Generated text"}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertNotNull(result)
        assertEquals("msg_syn1", result.info.id)
    }

    @Test
    fun `toMessageWithParts returns null for missing type`() {
        val obj = json.parseToJsonElement("""{"id":"msg_x"}""").jsonObject
        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")
        assertNull(result)
    }

    @Test
    fun `toMessageWithParts returns null for missing id`() {
        val obj = json.parseToJsonElement("""{"type":"user"}""").jsonObject
        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")
        assertNull(result)
    }

    @Test
    fun `toMessageWithParts handles assistant with empty content array`() {
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a2","time":{"created":1000},
             "agent":"build","model":{"id":"gpt-4","providerID":"openai"},"content":[]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertEquals(0, result.parts.size)
    }

    @Test
    fun `toMessageWithParts maps tool with running state`() {
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a3","time":{"created":1000},
             "agent":"build","model":{"id":"m","providerID":"p"},
             "content":[{"type":"tool","id":"t1","name":"exec","state":{"status":"running"}}]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val toolPart = result.parts[0] as Part.Tool
        assertTrue(toolPart.state is dev.leonardo.ocbeacon.domain.model.ToolState.Running)
    }

    @Test
    fun `toMessageWithParts maps tool with error state`() {
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a4","time":{"created":1000},
             "agent":"build","model":{"id":"m","providerID":"p"},
             "content":[{"type":"tool","id":"t2","name":"exec","state":{"status":"error"}}]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val toolPart = result.parts[0] as Part.Tool
        assertTrue(toolPart.state is dev.leonardo.ocbeacon.domain.model.ToolState.Error)
    }

    @Test
    fun `toMessageWithParts maps subagent tool with metadata sessionID`() {
        // V2 subagent 工具实际结构（REST 实测）：metadata.sessionID 是子会话 ID
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a5","time":{"created":1000},
             "agent":"build","model":{"id":"m","providerID":"p"},
             "content":[{"type":"tool","id":"call_123","name":"subagent",
               "state":{"status":"completed",
                 "input":{"description":"验证功能","prompt":"请验证"},
                 "metadata":{"sessionID":"ses_child_1","status":"completed","truncated":false},
                 "content":[{"type":"text","text":"验证完成"}]}}]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val toolPart = result.parts[0] as Part.Tool
        assertEquals("subagent", toolPart.tool)
        assertTrue(toolPart.state is dev.leonardo.ocbeacon.domain.model.ToolState.Completed)
        val completed = toolPart.state as dev.leonardo.ocbeacon.domain.model.ToolState.Completed

        // 关键断言 1：metadata 必须包含子会话 ID（TaskToolCard 跳转依赖）
        assertNotNull(completed.metadata)
        assertEquals("ses_child_1", completed.metadata?.get("sessionId")?.jsonPrimitive?.content)
        // 双写兼容（V2 大写 / V1 小写）
        assertEquals("ses_child_1", completed.metadata?.get("sessionID")?.jsonPrimitive?.content)

        // 关键断言 2：input 必须包含 description（TaskToolCard 显示描述依赖）
        assertEquals("验证功能", completed.input["description"]?.jsonPrimitive?.content)

        // 关键断言 3：output 必须包含工具输出（TaskToolCard 显示输出依赖）
        assertTrue(completed.output.contains("验证完成"))
    }

    @Test
    fun `toMessageWithParts maps subagent tool metadata sessionId lowercase variant`() {
        // V1 风格 metadata 键名（sessionId 小写）也应兼容
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a6","time":{"created":1000},
             "agent":"build","model":{"id":"m","providerID":"p"},
             "content":[{"type":"tool","id":"call_456","name":"subagent",
               "state":{"status":"completed",
                 "metadata":{"sessionId":"ses_child_2"}}}]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val toolPart = result.parts[0] as Part.Tool
        val completed = toolPart.state as dev.leonardo.ocbeacon.domain.model.ToolState.Completed
        assertEquals("ses_child_2", completed.metadata?.get("sessionId")?.jsonPrimitive?.content)
        assertEquals("ses_child_2", completed.metadata?.get("sessionID")?.jsonPrimitive?.content)
    }
}
