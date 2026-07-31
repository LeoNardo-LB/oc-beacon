package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.QuestionRequest
import dev.leonardo.ocbeacon.data.dto.response.QuestionInfo
import dev.leonardo.ocbeacon.data.dto.response.QuestionOption
import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.*
import org.junit.Test

class QuestionMapperTest {

    @Test
    fun `toDomain maps all fields correctly`() {
        val dto = QuestionRequest(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                QuestionInfo(
                    question = "Which model?",
                    header = "Model Selection",
                    options = listOf(
                        QuestionOption("GPT-4", "Most capable"),
                        QuestionOption("GPT-3.5", "Faster")
                    ),
                    multiple = false,
                    custom = true
                )
            )
        )

        val domain = QuestionMapper.toDomain(dto)

        assertEquals("q1", domain.id)
        assertEquals("s1", domain.sessionId)
        assertEquals(1, domain.questions.size)
        val q = domain.questions[0]
        assertEquals("Which model?", q.question)
        assertEquals("Model Selection", q.header)
        assertEquals(2, q.options.size)
        assertEquals("GPT-4", q.options[0].label)
        assertFalse(q.multiple)
        assertTrue(q.custom)
    }

    @Test
    fun `toDto maps all fields correctly`() {
        val domain = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    question = "Confirm?",
                    header = "Action",
                    options = listOf(SseEvent.QuestionAsked.Option("Yes", "Proceed")),
                    multiple = false,
                    custom = false
                )
            )
        )

        val dto = QuestionMapper.toDto(domain)

        assertEquals("q1", dto.id)
        assertEquals("s1", dto.sessionId)
        assertEquals(1, dto.questions.size)
        assertEquals("Confirm?", dto.questions[0].question)
        assertFalse(dto.questions[0].custom)
    }

    @Test
    fun `round-trip preserves all data`() {
        val original = SseEvent.QuestionAsked(
            id = "q1",
            sessionId = "s1",
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    question = "Pick tools",
                    header = "Tools",
                    options = listOf(
                        SseEvent.QuestionAsked.Option("Tool A", "Desc A"),
                        SseEvent.QuestionAsked.Option("Tool B", "Desc B")
                    ),
                    multiple = true,
                    custom = true
                )
            )
        )

        val roundTripped = QuestionMapper.toDomain(QuestionMapper.toDto(original))

        assertEquals(original.id, roundTripped.id)
        assertEquals(original.questions.size, roundTripped.questions.size)
        assertEquals(original.questions[0].options.size, roundTripped.questions[0].options.size)
        assertEquals(original.questions[0].multiple, roundTripped.questions[0].multiple)
        assertEquals(original.questions[0].custom, roundTripped.questions[0].custom)
    }

    @Test
    fun `empty questions list maps correctly`() {
        val dto = QuestionRequest(id = "q1", sessionId = "s1", questions = emptyList())
        val domain = QuestionMapper.toDomain(dto)
        assertTrue(domain.questions.isEmpty())
    }
}
