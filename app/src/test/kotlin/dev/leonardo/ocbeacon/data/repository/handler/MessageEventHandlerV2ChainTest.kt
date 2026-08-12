package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.api.v2.V2SseMapper
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V2 事件链集成测试——v2 生命周期事件经 V2SseMapper + MessageEventHandler
 * 的端到端状态（docs/superpowers/specs 2026-08-11 §3.2 实测序列）。
 */
class MessageEventHandlerV2ChainTest {

    private lateinit var handler: MessageEventHandler
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        handler = MessageEventHandler()
    }

    private fun emit(type: String, payload: String) {
        val props = json.parseToJsonElement(payload).jsonObject
        val event = V2SseMapper.map(type, props)!!
        when (val e = event) {
            is SseEvent.MessageUpdated -> handler.handleMessageUpdated(e)
            is SseEvent.MessagePartUpdated -> handler.handleMessagePartUpdated(e)
            is SseEvent.MessagePartDelta -> {
                handler.handleMessagePartDelta(e)
                handler.forceFlushDeltas()
            }
            else -> throw AssertionError("意外事件类型: ${e::class.simpleName}")
        }
    }

    @Test
    fun `full v2 event chain produces message and parts`() {
        // 1. 用户消息播种
        emit("session.input.admitted", """{"sessionID":"ses_1","inputID":"msg_user_1","input":{"type":"user","data":{"text":"你好"},"delivery":"steer"}}""")
        // 2. assistant 消息创建
        emit("session.step.started", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","agent":"build","model":"glm-5.2"}""")
        // 3. reasoning 流
        emit("session.reasoning.started", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1}""")
        emit("session.reasoning.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1,"delta":"思考"}""")
        emit("session.reasoning.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1,"delta":"中"}""")
        emit("session.reasoning.ended", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":1,"text":"思考中完整"}""")
        // 4. text 流
        emit("session.text.started", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2}""")
        emit("session.text.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"delta":"Hello"}""")
        emit("session.text.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"delta":" world"}""")
        emit("session.text.ended", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","ordinal":2,"text":"Hello world"}""")
        // 5. tool 生命周期
        emit("session.tool.input.started", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_9","name":"bash"}""")
        emit("session.tool.input.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_9","delta":"out"}""")
        emit("session.tool.input.ended", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_9","text":"output text"}""")
        emit("session.tool.called", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_9","input":{"command":"ls"},"executed":true}""")
        emit("session.tool.success", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","id":"call_9","content":[{"type":"text","text":"done"}],"metadata":{"sessionID":"ses_child_9"}}""")
        // 6. step 结束（成本更新）
        emit("session.step.ended", """{"sessionID":"ses_1","assistantMessageID":"msg_asst_1","finish":"stop","cost":{"total":0.5}}""")

        // ============ 断言最终状态 ============

        // 消息：user + assistant
        val messages = handler.messages.value["ses_1"].orEmpty()
        assertEquals("应有 2 条消息", 2, messages.size)
        assertTrue(messages[0] is Message.User)
        assertTrue(messages[1] is Message.Assistant)
        val assistant = messages[1] as Message.Assistant
        assertEquals("msg_asst_1", assistant.id)
        assertEquals(0.5, assistant.cost ?: 0.0, 0.001)

        // 用户消息播种 part
        val userParts = handler.parts.value["msg_user_1"].orEmpty()
        assertEquals(1, userParts.size)
        assertEquals("你好", (userParts[0] as Part.Text).text)
        // assistant parts：reasoning + text + tool（按 ordinal 派生 id）
        val parts = handler.parts.value["msg_asst_1"].orEmpty()
        assertEquals("应有 3 个 part（reasoning/text/tool）", 3, parts.size)

        val reasoning = parts.first { it.id == "msg_asst_1_ord_1" } as Part.Reasoning
        assertEquals("思考中完整", reasoning.text)

        val text = parts.first { it.id == "msg_asst_1_ord_2" } as Part.Text
        assertEquals("Hello world", text.text)

        val tool = parts.first { it.id == "call_9" } as Part.Tool
        assertEquals("bash", tool.tool)
        assertTrue("tool 应为 Completed", tool.state is ToolState.Completed)
        val completed = tool.state as ToolState.Completed
        assertEquals("done", completed.output)
        assertEquals(
            "ses_child_9",
            completed.metadata?.get("sessionId")?.jsonPrimitive?.contentOrNull
        )
    }

    @Test
    fun `synthetic message updated seeds message and text part for realtime notification`() {
        // 2026-08-12：SseClientV2 消费 session.input.promoted 后构造的
        // synthetic MessageUpdated（role="synthetic" + summary.body=完整标记文本）
        // → handleMessageUpdated 播种 Part.Text → SyntheticNotificationCard 实时渲染。
        // 对应实测服务器 payload：input.type="synthetic"，text 为
        // <subagent id=... state=completed description=...>结果</subagent>
        val synthetic = SseEvent.MessageUpdated(
            Message.User(
                id = "msg_syn_1",
                sessionId = "ses_1",
                role = "synthetic",
                time = dev.leonardo.ocbeacon.domain.model.TimeInfo(created = 2000L),
                summary = Message.User.UserSummary(
                    body = """<subagent id="ses_child_42" state="completed" description="测试任务">42</subagent>""",
                    title = "测试任务"
                )
            )
        )
        handler.handleMessageUpdated(synthetic)

        // 消息入库 + role 标记
        val messages = handler.messages.value["ses_1"].orEmpty()
        assertEquals(1, messages.size)
        val msg = messages.single()
        assertTrue(msg is Message.User)
        assertEquals("synthetic", (msg as Message.User).role)

        // parts 播种（summary.body → Part.Text）
        val parts = handler.parts.value["msg_syn_1"].orEmpty()
        assertEquals(1, parts.size)
        val textPart = parts.single() as Part.Text
        assertTrue(textPart.text.contains("<subagent"))
        assertTrue(textPart.text.contains("42"))
    }
}
