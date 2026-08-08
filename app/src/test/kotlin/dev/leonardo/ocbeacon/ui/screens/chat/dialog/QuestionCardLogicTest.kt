package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionCardLogicTest {

    @Test
    fun `unansweredQuestionIndexes - empty answers returns all`() {
        val idx = unansweredQuestionIndexes(listOf(emptyList(), emptyList()), questionCount = 2)
        assertEquals(listOf(1, 2), idx)
    }

    @Test
    fun `unansweredQuestionIndexes - some answered returns only unanswered`() {
        val idx = unansweredQuestionIndexes(listOf(listOf("A"), emptyList(), listOf("B")), questionCount = 3)
        assertEquals(listOf(2), idx)
    }

    @Test
    fun `unansweredQuestionIndexes - all answered returns empty`() {
        val idx = unansweredQuestionIndexes(listOf(listOf("A"), listOf("B")), questionCount = 2)
        assertEquals(emptyList<Int>(), idx)
    }

    @Test
    fun `unansweredQuestionIndexes - short answers list pads with unanswered`() {
        val idx = unansweredQuestionIndexes(listOf(listOf("A")), questionCount = 3)
        assertEquals(listOf(2, 3), idx)
    }
}
