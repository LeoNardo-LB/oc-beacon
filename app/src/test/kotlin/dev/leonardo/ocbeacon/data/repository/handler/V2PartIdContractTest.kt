package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.api.v2.V2MessageMapper
import dev.leonardo.ocbeacon.data.api.v2.V2SseMapper
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #109（D2-01）：V2 part 身份契约 = (messageID, type, ordinal)。
 *
 * 实测依据（2026-08-14 真机抓帧 next-17430 + 服务器二进制 TUI 片段键
 * k(messageID,"text",ordinal)）：ordinal 按类型独立计数——同一消息
 * reasoning[0] 与 text[0] 并存。旧 derivePartId 漏 type → id 碰撞：
 * text.started 按 id 命中 Reasoning part 并替换（推理内容丢失）；
 * REST（id=""）与 SSE（派生 id）契约错位 → mergePartsList 双保留（双份渲染）。
 */
class V2PartIdContractTest {

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
            else -> throw AssertionError("unexpected event: " + e::class.simpleName)
        }
    }

    /** 真实事件序列：同消息 reasoning[ordinal=0] + text[ordinal=0]（按类型计数）。 */
    private fun streamReasoningThenText() {
        emit("session.step.started", """{"sessionID":"ses_1","assistantMessageID":"msg_a","agent":"build"}""")
        emit("session.reasoning.started", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0}""")
        emit("session.reasoning.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"delta":"think"}""")
        emit("session.reasoning.ended", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"text":"full-thought"}""")
        emit("session.text.started", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0}""")
        emit("session.text.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"delta":"ans"}""")
        emit("session.text.delta", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"delta":"wer"}""")
        emit("session.text.ended", """{"sessionID":"ses_1","assistantMessageID":"msg_a","ordinal":0,"text":"answer"}""")
    }

    @Test
    fun `reasoning and text with same ordinal do not collide`() {
        streamReasoningThenText()
        val parts = handler.parts.value["msg_a"].orEmpty()
        assertEquals("expect 2 parts (reasoning + text)", 2, parts.size)
        val reasoning = parts.filterIsInstance<Part.Reasoning>().singleOrNull()
        val text = parts.filterIsInstance<Part.Text>().singleOrNull()
        assertNotNull(reasoning)
        assertNotNull(text)
        assertEquals("reasoning must survive text.started", "full-thought", reasoning!!.text)
        assertEquals("answer", text!!.text)
        assertTrue("part ids must differ by type", reasoning.id != text.id)
    }

    @Test
    fun `rest merge after sse stream does not duplicate text`() {
        streamReasoningThenText()
        val restJson = """{"id":"msg_a","type":"assistant","time":{"created":100,"completed":200},"content":[{"type":"reasoning","text":"full-thought","time":{"created":100,"completed":150}},{"type":"text","text":"answer","time":{"created":150,"completed":200}}]}"""
        val obj = json.parseToJsonElement(restJson).jsonObject
        val rest = V2MessageMapper.toMessageWithParts(obj, "ses_1")!!
        handler.upsertMessages("ses_1", listOf(rest), MergeStrategy.REST_AUTHORITY)
        val parts = handler.parts.value["msg_a"].orEmpty()
        assertEquals("no duplicate text/reasoning after REST merge (D2-01)", 2, parts.size)
        assertEquals("answer", parts.filterIsInstance<Part.Text>().single().text)
        assertEquals("full-thought", parts.filterIsInstance<Part.Reasoning>().single().text)
    }

    @Test
    fun `legacy blank id rest parts dedup against derived id sse parts`() {
        streamReasoningThenText()
        val legacy = MessageWithParts(
            info = Message.Assistant(id = "msg_a", sessionId = "ses_1", parentId = "", time = TimeInfo(created = 100, completed = 200)),
            parts = listOf(
                Part.Text(id = "", sessionId = "ses_1", messageId = "msg_a", text = "answer")
            )
        )
        handler.upsertMessages("ses_1", listOf(legacy), MergeStrategy.REST_AUTHORITY)
        val texts = handler.parts.value["msg_a"].orEmpty().filterIsInstance<Part.Text>()
        assertEquals("blank-id legacy part dedups with derived-id part", 1, texts.size)
    }

    @Test
    fun `reasoning ended part time start is plausible timestamp`() {
        streamReasoningThenText()
        val reasoning = handler.parts.value["msg_a"].orEmpty()
            .filterIsInstance<Part.Reasoning>().single()
        val start = reasoning.time?.start ?: 0L
        assertTrue("time.start should be a plausible epoch ms, got " + start, start > 1577836800000L)
    }
}