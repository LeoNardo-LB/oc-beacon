package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionReplacementTest {

    private fun q(id: String) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "ses_1",
        questions = emptyList()
    )

    @Test
    fun `rest result replaces previous snapshot entirely`() {
        val rest = listOf(q("que_2"), q("que_3"))
        assertEquals(listOf("que_2", "que_3"), resolvePendingQuestionReplacement(rest).map { it.id })
    }

    @Test
    fun `empty rest result clears session`() {
        assertEquals(emptyList<SseEvent.QuestionAsked>(), resolvePendingQuestionReplacement(emptyList()))
    }

    @Test
    fun `rest result drops questions no longer pending`() {
        val rest = listOf(q("que_3"))
        assertEquals(listOf("que_3"), resolvePendingQuestionReplacement(rest).map { it.id })
    }
}
