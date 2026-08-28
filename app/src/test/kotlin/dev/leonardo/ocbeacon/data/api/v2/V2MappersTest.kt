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

    // #226：zhipu 构建的 compaction 消息 summary 为嵌套对象 {body}——
    // 此前 jsonPrimitive 强转抛异常被吞 → parts 整体丢失。
    // #230：服务器 content 携带 SSE started 残留的空 text/reasoning item
    //（实测单消息 210 个空 reasoning）——REST 映射在源头丢弃，ordinal 照常
    // 计数保 id 契约与 SSE 派生对齐。
    @Test
    fun `toMessageWithParts drops empty text and reasoning content items preserving ordinals`() {
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_e1","time":{"created":1000,"completed":2000},
             "content":[
               {"type":"reasoning","text":""},
               {"type":"reasoning","text":""},
               {"type":"text","text":""},
               {"type":"text","text":"Real answer"},
               {"type":"reasoning","text":"Thought process"}
             ]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val texts = result.parts.filterIsInstance<Part.Text>()
        val reasons = result.parts.filterIsInstance<Part.Reasoning>()
        // 三个空 item 全部丢弃，只剩非空
        assertEquals(1, texts.size)
        assertEquals(1, reasons.size)
        assertEquals("Real answer", texts[0].text)
        assertEquals("Thought process", reasons[0].text)
        // ordinal 按服务器 content 出现序计数（空 item 占位）：real text 是
        // 第 2 个 text（ord=1）、thought 是第 3 个 reasoning（ord=2）
        assertEquals(V2SseMapper.derivePartId("msg_e1", "text", 1), texts[0].id)
        assertEquals(V2SseMapper.derivePartId("msg_e1", "reasoning", 2), reasons[0].id)
    }

    @Test
    fun `toMessageWithParts maps compaction message with nested summary body`() {
        val obj = json.parseToJsonElement("""
            {"type":"compaction","id":"msg_c1","time":{"created":1000},
             "summary":{"body":"Summary of the conversation so far"}}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertEquals("msg_c1", result.info.id)
        assertTrue(result.info is Message.User)
        assertEquals("compaction", (result.info as Message.User).role)
        assertEquals(1, result.parts.size)
        val compactionPart = result.parts[0] as Part.Compaction
        assertEquals("Summary of the conversation so far", compactionPart.summary)
        assertFalse(compactionPart.failed)
    }

    @Test
    fun `toMessageWithParts maps compaction message with primitive summary fallback`() {
        val obj = json.parseToJsonElement("""
            {"type":"compaction","id":"msg_c2","time":{"created":1000},
             "summary":"Legacy flat summary"}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val compactionPart = result.parts[0] as Part.Compaction
        assertEquals("Legacy flat summary", compactionPart.summary)
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
    fun `toMessageWithParts maps shell message with full payload`() {
        // #252 时间线化：type='shell' 条目（REST 实测形态）必须映射为带 Part.Shell
        // 载荷的消息——此前只读 text/summary 字段 → command/status/exit/output 全部
        // 丢弃 → 空壳信封（渲染层只能钉底横幅，不随时间线滚动、跨进程即失）。
        val obj = json.parseToJsonElement("""
            {"type":"shell","id":"msg_sh1","time":{"created":1787894297522,"completed":1787894297530},
             "shellID":"sh_046cdf3ab001bMzQ7R2hMK2HZy","command":"echo-inflow",
             "status":"exited","exit":127,
             "output":{"output":"/bin/bash: 行 1: echo-inflow: 未找到命令\n","cursor":47,"size":47,"truncated":false}}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        assertEquals("msg_sh1", result.info.id)
        assertEquals("shell", (result.info as Message.User).role)
        assertEquals(1, result.parts.size)
        val shell = result.parts[0] as Part.Shell
        assertEquals("sh_046cdf3ab001bMzQ7R2hMK2HZy", shell.shellId)
        assertEquals("echo-inflow", shell.command)
        assertEquals("exited", shell.status)
        assertEquals(127, shell.exit)
        assertEquals("/bin/bash: 行 1: echo-inflow: 未找到命令\n", shell.output)
        assertEquals("msg_sh1", shell.messageId)
    }

    @Test
    fun `toMessageWithParts maps running shell message without exit`() {
        val obj = json.parseToJsonElement("""
            {"type":"shell","id":"msg_sh2","time":{"created":1000},
             "shellID":"sh_2","command":"sleep 5","status":"running"}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val shell = result.parts[0] as Part.Shell
        assertEquals("running", shell.status)
        assertNull(shell.exit)
        assertNull(shell.output)
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
        // V2 subagent 工具实际结构（REST 实测）：metadata.sessionID 是子智能体会话 ID
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

        // 关键断言 1：metadata 必须包含子智能体会话 ID（TaskToolCard 跳转依赖）
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
    fun `toMessageWithParts maps subagent tool metadata childID variant running`() {
        // #180（2026-08-21 宿主机 SSE 抓帧实证）：subagent Running 期 metadata
        // 可能以 childID 命名（synthetic 消息同源 {source:"subagent", childID,...}）
        // ——归一后 Running 态也要能拿到 sessionId/sessionID 双写（卡片跳转依赖）
        val obj = json.parseToJsonElement("""
            {"type":"assistant","id":"msg_a7","time":{"created":1000},
             "agent":"build","model":{"id":"m","providerID":"p"},
             "content":[{"type":"tool","id":"call_789","name":"subagent",
               "state":{"status":"running",
                 "metadata":{"childID":"ses_child_3","status":"running"}}}]}
        """).jsonObject

        val result = V2MessageMapper.toMessageWithParts(obj, "sess_1")!!
        val toolPart = result.parts[0] as Part.Tool
        val running = toolPart.state as dev.leonardo.ocbeacon.domain.model.ToolState.Running
        assertEquals("ses_child_3", running.metadata?.get("sessionId")?.jsonPrimitive?.content)
        assertEquals("ses_child_3", running.metadata?.get("sessionID")?.jsonPrimitive?.content)
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

    // ============ HTML 防御（SPA fallback 回归） ============

    @Test
    fun `flexibleList throws NonJsonResponseException on HTML response`() {
        // 回归：1.18.18 过渡形态对不存在的 /api/* 路径返回 <!doctype html>（HTTP 200）
        val html = "<!doctype html><html lang=\"en\"><body>opencode web ui</body></html>"
        try {
            V2ResponseWrapper.flexibleList(html, json)
            fail("应抛出 NonJsonResponseException")
        } catch (e: dev.leonardo.ocbeacon.data.api.NonJsonResponseException) {
            assertTrue(e.message!!.contains("HTML"))
        }
    }

    @Test
    fun `flexibleObject throws NonJsonResponseException on HTML response`() {
        val html = "<html><body>404 page</body></html>"
        try {
            V2ResponseWrapper.flexibleObject(html, json)
            fail("应抛出 NonJsonResponseException")
        } catch (e: dev.leonardo.ocbeacon.data.api.NonJsonResponseException) {
            assertTrue(e.message!!.contains("HTML"))
        }
    }

    @Test
    fun `flexibleList parses wrapped data array`() {
        val items = V2ResponseWrapper.flexibleList(
            """{"location":{},"data":[{"id":"1"},{"id":"2"}],"cursor":{}}""",
            json
        )
        assertEquals(2, items.size)
    }

    @Test
    fun `flexibleList parses bare array`() {
        val items = V2ResponseWrapper.flexibleList("""[{"id":"1"}]""", json)
        assertEquals(1, items.size)
    }

    @Test
    fun `flexibleObject unwraps data object`() {
        val obj = V2ResponseWrapper.flexibleObject("""{"data":{"id":"x"}}""", json)
        assertEquals("x", obj["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `flexibleObject handles non object root`() {
        val obj = V2ResponseWrapper.flexibleObject("""[1,2,3]""", json)
        assertTrue(obj.isEmpty())
    }

    // ============ #206 V2 中断标记合成（finish=error + error.type=aborted） ============

    /** 服务器中断唯一表示（Tier C 探针实测，2026-08-24）：无 abort part，全历史 0 个。 */
    private fun interruptedAssistantJson(finish: String? = "\"error\"", errorBlock: String? =
        "{\"type\":\"aborted\",\"message\":\"Step interrupted\"}"): String = """
        {"type":"assistant","id":"msg_abort_1","sessionID":"ses_1",
         "time":{"created":1000},
         "finish":$finish,"error":$errorBlock,
         "content":[{"type":"reasoning","text":"thinking..."}]}
    """

    @Test
    fun `aborted assistant message synthesizes Part-Abort marker`() {
        val obj = json.parseToJsonElement(interruptedAssistantJson()).jsonObject
        val result = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        val abort = result.parts.filterIsInstance<Part.Abort>()
        assertEquals(1, abort.size)
        assertEquals("msg_abort_1_abort", abort[0].id)
        assertEquals("ses_1", abort[0].sessionId)
        assertEquals("msg_abort_1", abort[0].messageId)
        assertEquals("Step interrupted", abort[0].reason)
        // 合成标记位于 parts 尾部（reasoning 之后）——时间顺序语义
        assertTrue(result.parts.last() is Part.Abort)
    }

    @Test
    fun `aborted assistant message with empty content still gets marker`() {
        // 1.2s 早期中断实测形态：content 仅剩 reasoning；极端情况 content 空
        val raw = """
            {"type":"assistant","id":"msg_abort_2","sessionID":"ses_1",
             "time":{"created":1000},
             "finish":"error","error":{"type":"aborted","message":"Step interrupted"},
             "content":[]}
        """
        val obj = json.parseToJsonElement(raw).jsonObject
        val result = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        assertEquals(1, result.parts.size)
        assertTrue(result.parts[0] is Part.Abort)
    }

    @Test
    fun `normally completed assistant message has no abort marker`() {
        val obj = json.parseToJsonElement(
            interruptedAssistantJson(finish = "\"stop\"", errorBlock = "null")
        ).jsonObject
        val result = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        assertTrue(result.parts.filterIsInstance<Part.Abort>().isEmpty())
    }

    @Test
    fun `non-aborted error does not synthesize abort marker`() {
        // finish=error 但 type!=aborted（如 provider 错误）不冒充中断——
        // 错误展示走 Message.Assistant.error 独立通道，不属 #206 范围
        val obj = json.parseToJsonElement(
            interruptedAssistantJson(errorBlock = "{\"type\":\"rate_limit\",\"message\":\"too fast\"}")
        ).jsonObject
        val result = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        assertTrue(result.parts.filterIsInstance<Part.Abort>().isEmpty())
    }

    @Test
    fun `aborted marker id is stable for refetch dedup`() {
        // 稳定 id：REST 重取走 mergePartsList 按 id 去重——两次映射同 id 不双显
        val obj = json.parseToJsonElement(interruptedAssistantJson()).jsonObject
        val a = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        val b = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        assertEquals(
            a.parts.filterIsInstance<Part.Abort>().map { it.id },
            b.parts.filterIsInstance<Part.Abort>().map { it.id }
        )
    }
}
