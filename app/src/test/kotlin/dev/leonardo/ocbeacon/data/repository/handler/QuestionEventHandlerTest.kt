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
