package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 V1 PartSerializer 对 V2 tool part 结构的兼容性。
 *
 * V2 的 tool part 结构（REST/SSE 实测）：
 * {type, id, name, executed, state: {status, input, content, metadata}}
 *
 * V1 Part.Tool 字段：id, sessionID, messageID, callID, tool, state, metadata
 * 差异：V2 用 name（V1 用 tool）、V2 用 id（V1 也用 id 但 callID 独立）
 */
class PartV2CompatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val v2ToolJson = """
    {
      "type": "tool",
      "id": "call_00_ET_tIFDKUFnLV5WqSVn9z5H1978",
      "name": "subagent",
      "executed": false,
      "state": {
        "status": "completed",
        "input": {"description": "验证无IME环境", "prompt": "prompt...", "agent": "general-fast"},
        "content": [{"type": "text", "text": "验证完成"}],
        "metadata": {"sessionID": "ses_child_1", "status": "completed", "truncated": false}
      },
      "time": {"created": 1786403536578, "ran": 1786403539929, "completed": 1786403613729}
    }
    """

    @Test
    fun `V1 PartSerializer decodes V2 tool part`() {
        // 通过 MessageEventParser 解析（实际 SSE 路径），而非直接 PartSerializer
        val parser = dev.leonardo.ocbeacon.data.api.sse.parsers.MessageEventParser(json)
        val props = json.parseToJsonElement("""{"part": $v2ToolJson}""").jsonObject
        val event = parser.parse("message.part.updated", props)
        assertNotNull("MessagePartUpdated 应解析出事件", event)
        assertTrue("应为 MessagePartUpdated，实际是 ${event!!::class.simpleName}", event is dev.leonardo.ocbeacon.domain.model.SseEvent.MessagePartUpdated)
        val part = (event as dev.leonardo.ocbeacon.domain.model.SseEvent.MessagePartUpdated).part
        assertTrue("应该是 Part.Tool，实际是 ${part::class.simpleName}", part is Part.Tool)
        val tool = part as Part.Tool
        assertEquals("call_00_ET_tIFDKUFnLV5WqSVn9z5H1978", tool.id)
        assertTrue("state 应为 Completed，实际是 ${tool.state::class.simpleName}", tool.state is ToolState.Completed)
        val completed = tool.state as ToolState.Completed
        // V2 state.input → ToolState.input
        assertEquals("验证无IME环境", completed.input["description"]?.jsonPrimitive?.content)
        // V2 state.content → output
        assertTrue("output 应包含工具输出，实际: '${completed.output}'", completed.output.contains("验证完成"))
        // V2 state.metadata.sessionID → metadata
        assertNotNull("metadata 不应为 null", completed.metadata)
        val sid = completed.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
            ?: completed.metadata?.get("sessionID")?.jsonPrimitive?.contentOrNull
        assertEquals("ses_child_1", sid)
    }

    @Test
    fun `PartSerializer infers Text from payload without type field`() {
        // 2026-08-12 修复：旧数据/SSE 播种的 Part.Text 序列化省略默认值
        //（text="" 时不写 text 字段、从不写 type）→ 无 type 有 text 时按字段推断
        val payload = """{"id":"msg_x_summary","sessionID":"s1","messageID":"msg_x","text":"大致说下当前目录下有哪些内容"}"""
        val part = json.decodeFromString<Part>(payload)
        assertTrue("expected Text, got $part", part is Part.Text)
        assertEquals("大致说下当前目录下有哪些内容", (part as Part.Text).text)
    }

    @Test
    fun `PartSerializer maps textless summary payload to Unknown`() {
        // text="" 默认值被省略 → payload 无 type 无 text → Unknown（不误判为 Tool）
        val payload = """{"id":"msg_x_summary","sessionID":"s1","messageID":"msg_x"}"""
        val part = json.decodeFromString<Part>(payload)
        assertTrue("expected Unknown, got $part", part is Part.Unknown)
    }

    @Test
    fun `V1 PartSerializer decodes V2 tool with double-nested metadata`() {
        // V2 服务器实际返回的双层嵌套 metadata：{metadata: {sessionID: ...}}
        val parser = dev.leonardo.ocbeacon.data.api.sse.parsers.MessageEventParser(json)
        val v2DoubleNested = """
        {"type":"tool","id":"call_double","name":"subagent","executed":false,
         "state":{"status":"error",
           "input":{"description":"验证subagent卡片跳转","prompt":"prompt","agent":"general"},
           "metadata":{"metadata":{"sessionID":"ses_double_1","status":"error","truncated":false}},
           "error":{"type":"aborted","message":"Tool execution interrupted"}}}
        """
        val props = json.parseToJsonElement("""{"part": $v2DoubleNested}""").jsonObject
        val event = parser.parse("message.part.updated", props)
        assertNotNull("应解析出事件", event)
        val part = (event as dev.leonardo.ocbeacon.domain.model.SseEvent.MessagePartUpdated).part
        assertTrue("应为 Part.Tool", part is Part.Tool)
        val tool = part as Part.Tool
        assertEquals("subagent", tool.tool)
        assertTrue("state 应为 Error", tool.state is ToolState.Error)
        val error = tool.state as ToolState.Error
        // 双层 metadata 展平后应能提取 sessionID
        assertNotNull("metadata 不应为 null", error.metadata)
        val sid = error.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
            ?: error.metadata?.get("sessionID")?.jsonPrimitive?.contentOrNull
        assertEquals("ses_double_1", sid)
        // error 对象解析为字符串（V2 error 是 {type, message}）
        assertTrue("error 应包含消息，实际: '${error.error}'", error.error.contains("Tool execution interrupted"))
    }
}
