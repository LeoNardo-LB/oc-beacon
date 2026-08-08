package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionNotifyDiffTest {

    private fun q(id: String) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "ses_1",
        questions = emptyList()
    )

    @Test
    fun `new questions are detected per session`() {
        val previous = mapOf("ses_1" to setOf("que_1"))
        val current = mapOf(
            "ses_1" to listOf(q("que_1"), q("que_2")),
            "ses_2" to listOf(q("que_3"))
        )
        val diff = diffNewQuestionIds(previous, current)
        assertEquals(listOf("que_2"), diff["ses_1"]?.map { it.id })
        assertEquals(listOf("que_3"), diff["ses_2"]?.map { it.id })
    }

    @Test
    fun `known questions not re-notified`() {
        val previous = mapOf("ses_1" to setOf("que_1"))
        val current = mapOf("ses_1" to listOf(q("que_1")))
        assertEquals(emptyMap<String, List<SseEvent.QuestionAsked>>(), diffNewQuestionIds(previous, current))
    }

    @Test
    fun `empty previous notifies all`() {
        val current = mapOf("ses_1" to listOf(q("que_1")))
        val diff = diffNewQuestionIds(emptyMap(), current)
        assertEquals(listOf("que_1"), diff["ses_1"]?.map { it.id })
    }
}
