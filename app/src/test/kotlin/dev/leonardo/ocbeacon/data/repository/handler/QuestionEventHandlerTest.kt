package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class QuestionEventHandlerTest {

    private lateinit var handler: QuestionEventHandler

    @Before
    fun setup() {
        handler = QuestionEventHandler()
    }

    private fun testQuestion(id: String, sessionId: String) = SseEvent.QuestionAsked(
        id = id, sessionId = sessionId,
        questions = listOf(SseEvent.QuestionAsked.Question(
            header = "Q", question = "Yes or No?",
            options = listOf(SseEvent.QuestionAsked.Option("Yes", "Proceed"))
        ))
    )

    @Test
    fun `handles QuestionAsked`() {
        val q = testQuestion("q1", "s1")
        assertTrue(handler.handle(q, "server1"))
        assertEquals(listOf(q), handler.questions.value["s1"])
    }

    @Test
    fun `handles QuestionReplied`() {
        handler.handle(testQuestion("q1", "s1"), "server1")

        handler.handle(SseEvent.QuestionReplied(sessionId = "s1", requestId = "q1"), "server1")

        assertTrue(handler.questions.value["s1"]!!.isEmpty())
    }

    @Test
    fun `handles QuestionRejected`() {
        handler.handle(testQuestion("q1", "s1"), "server1")

        handler.handle(SseEvent.QuestionRejected(sessionId = "s1", requestId = "q1"), "server1")

        assertTrue(handler.questions.value["s1"]!!.isEmpty())
    }

    @Test
    fun `removeQuestion removes across all sessions`() {
        handler.handle(testQuestion("target", "s1"), "server1")
        handler.handle(testQuestion("target", "s2"), "server1")

        handler.removeQuestion("target")

        assertTrue(handler.questions.value["s1"]!!.isEmpty())
        assertTrue(handler.questions.value["s2"]!!.isEmpty())
    }

    @Test
    fun `setQuestions replaces existing`() {
        handler.handle(testQuestion("old", "s1"), "server1")
        val newQ = testQuestion("new", "s1")

        handler.setQuestions("s1", listOf(newQ))

        assertEquals(listOf(newQ), handler.questions.value["s1"])
    }

    @Test
    fun `setQuestions with empty list removes session entry`() {
        handler.handle(testQuestion("q1", "s1"), "server1")
        handler.setQuestions("s1", emptyList())
        assertFalse(handler.questions.value.containsKey("s1"))
    }

    @Test
    fun `mergeFromREST backfills tool when SSE lacks it`() {
        // SSE 版：无 tool（V1 question.asked 事件缺 tool 字段）
        val sseQ = testQuestion("q1", "s1")
        handler.handle(sseQ, "server1")
        assertNull(handler.questions.value["s1"]!!.first().tool)

        // REST 版：带 tool.messageID
        val restQ = sseQ.copy(tool = dev.leonardo.ocbeacon.domain.model.ToolRef(
            messageId = "msg_123", callId = "call_456"
        ))
        handler.mergeFromREST("s1", listOf(restQ))

        assertEquals("msg_123", handler.questions.value["s1"]!!.first().tool?.messageId)
    }

    @Test
    fun `mergeFromREST keeps SSE tool when present`() {
        val sseQ = testQuestion("q1", "s1").copy(tool = dev.leonardo.ocbeacon.domain.model.ToolRef(
            messageId = "msg_sse", callId = "call_sse"
        ))
        handler.handle(sseQ, "server1")
        val restQ = testQuestion("q1", "s1").copy(tool = dev.leonardo.ocbeacon.domain.model.ToolRef(
            messageId = "msg_rest", callId = "call_rest"
        ))

        handler.mergeFromREST("s1", listOf(restQ))

        assertEquals("msg_sse", handler.questions.value["s1"]!!.first().tool?.messageId)
    }

    @Test
    fun `mergeFromREST adds REST-only question and keeps SSE extra`() {
        val sseQ = testQuestion("sse_only", "s1")
        handler.handle(sseQ, "server1")
        val restQ = testQuestion("rest_only", "s1").copy(tool = dev.leonardo.ocbeacon.domain.model.ToolRef(
            messageId = "msg_rest", callId = "call_rest"
        ))

        handler.mergeFromREST("s1", listOf(restQ))

        val ids = handler.questions.value["s1"]!!.map { it.id }.toSet()
        assertEquals(setOf("sse_only", "rest_only"), ids)
    }

    @Test
    fun `mergeFromREST with empty list keeps SSE entries`() {
        // 并集语义：REST 空列表（轮询延迟窗口）不删除 SSE 已有条目——
        // 删除由 SSE QuestionReplied/Rejected/removeQuestion 驱动
        handler.handle(testQuestion("q1", "s1"), "server1")
        handler.mergeFromREST("s1", emptyList())
        assertTrue(handler.questions.value.containsKey("s1"))
        assertEquals(listOf("q1"), handler.questions.value["s1"]!!.map { it.id })
    }

    @Test
    fun `returns false for non-question events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
    }

    @Test
    fun `clearForSession removes for single session`() {
        handler.handle(testQuestion("q1", "s1"), "server1")
        handler.handle(testQuestion("q2", "s2"), "server1")

        handler.clearForSession("s1")

        assertFalse(handler.questions.value.containsKey("s1"))
        assertTrue(handler.questions.value.containsKey("s2"))
    }

    @Test
    fun `clearAll resets everything`() {
        handler.handle(testQuestion("q1", "s1"), "server1")
        handler.clearAll()
        assertTrue(handler.questions.value.isEmpty())
    }
}
