package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #182：Task 卡片全量输出拉取纯函数（part 优先 → 子智能体会话 transcript 降级）。
 */
class TaskOutputFetchTest {

    private fun toolPart(id: String, output: String): Part.Tool {
        val state = mockk<ToolState.Completed>(relaxed = true)
        every { state.output } returns output
        return Part.Tool(id = id, sessionId = "s1", messageId = "m1", tool = "task", state = state)
    }

    private fun userMsg(id: String, text: String) = MessageWithParts(
        info = Message.User(id = id, sessionId = "s1", role = "user", time = TimeInfo(created = 0)),
        parts = if (text.isEmpty()) emptyList() else listOf(
            Part.Text(id = "", sessionId = "s1", messageId = id, text = text),
        ),
    )

    private fun assistantMsg(id: String, text: String) = MessageWithParts(
        info = mockk<Message.Assistant>(),
        parts = if (text.isEmpty()) emptyList() else listOf(
            Part.Text(id = "", sessionId = "s1", messageId = id, text = text),
        ),
    )

    @Test
    fun findToolOutputByIdMatchesPartId() {
        val msgs = listOf(userMsg("m1", "hi"), MessageWithParts(
            info = mockk(),
            parts = listOf(toolPart("tp_9", "full server output".repeat(100))),
        ))
        val out = TaskOutputFetch.findToolOutputById(msgs, "tp_9")
        assertEquals("full server output".repeat(100), out)
    }

    @Test
    fun findToolOutputByIdMissReturnsNullAndBlankIdSkips() {
        assertNull(TaskOutputFetch.findToolOutputById(emptyList(), "nope"))
        assertNull(TaskOutputFetch.findToolOutputById(emptyList(), ""))
    }

    @Test
    fun childTranscriptJoinsRolesAndSkipsEmpty() {
        val msgs = listOf(
            userMsg("u1", "start the task"),
            assistantMsg("a1", ""),          // 无文本 part → 跳过
            assistantMsg("a2", "did the work"),
            MessageWithParts(info = mockk<Message.Assistant>(), parts = emptyList()), // 空 → 跳过
        )
        val t = TaskOutputFetch.buildChildTranscript(msgs)!!
        assertEquals("[user]\nstart the task\n\n[assistant]\ndid the work", t)
    }

    @Test
    fun childTranscriptCapsAtRenderLimit() {
        val big = "x".repeat(TaskOutputFetch.MAX_RENDER_CHARS + 500)
        val t = TaskOutputFetch.buildChildTranscript(listOf(userMsg("u1", big)))!!
        assertEquals(TaskOutputFetch.MAX_RENDER_CHARS, t.length)
    }

    @Test
    fun childTranscriptAllEmptyReturnsNull() {
        assertNull(TaskOutputFetch.buildChildTranscript(listOf(assistantMsg("a1", ""))))
    }

    @Test
    fun pickLongerPrefersMoreInformation() {
        assertEquals("longer content", TaskOutputFetch.pickLonger("longer content", "short"))
        assertEquals("longer content", TaskOutputFetch.pickLonger("short", "longer content"))
        assertEquals("only", TaskOutputFetch.pickLonger("only", null))
        assertEquals("only", TaskOutputFetch.pickLonger(null, "only"))
        assertNull(TaskOutputFetch.pickLonger(null, null))
    }

    @Test
    fun slicingSplitsWithinBudget() {
        val src = "y".repeat(TaskOutputFetch.SLICE_CHARS * 2 + 10)
        val slices = src.take(TaskOutputFetch.MAX_RENDER_CHARS).chunked(TaskOutputFetch.SLICE_CHARS)
        assertEquals(3, slices.size)
        assertEquals(src, slices.joinToString(""))
    }

    @Test
    fun metadataChildIdIsReadByCardContract() {
        // #180 契约：metadata.childID 与 sessionId/sessionID/jobId 同等可读
        // （V2Mappers 归一 + 卡片直读双保险）。此处验证 JSON 形状解析。
        val meta = buildJsonObject {
            put("source", JsonPrimitive("subagent"))
            put("childID", JsonPrimitive("ses_child_1"))
            put("agent", JsonPrimitive("Explore"))
            put("state", JsonPrimitive("running"))
        }
        val id = meta["childID"]!!.jsonPrimitiveContent()
        assertEquals("ses_child_1", id)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String =
        (this as kotlinx.serialization.json.JsonPrimitive).content
}
