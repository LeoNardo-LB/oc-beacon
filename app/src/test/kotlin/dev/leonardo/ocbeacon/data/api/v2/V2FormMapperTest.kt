package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2FormMapper mapping tests - using 2026-08-14 real capture (next-17430).
 */
class V2FormMapperTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun props(text: String) = json.parseToJsonElement(text).jsonObject

    // Real form.created frame (2026-08-14 capture, two questions: single + multi)
    private val formCreatedJson = """{"form":{"id":"frm_00033307b001qKsOZcHoEWf9Q9","sessionID":"ses_fffccfb23ffeuwKb5IHtkqiUuK","title":"Questions","metadata":{"kind":"question","tool":{"messageID":"msg_0003319d4001vQ6DyeLnVu2l8b","id":"call_c0efa0c5b11a4ce3999f84a1"}},"fields":[{"key":"q0","title":"today-eat","description":"Q1 what to eat","type":"string","options":[{"value":"rice","label":"rice","description":"staple"},{"value":"noodle","label":"noodle","description":"soup"},{"value":"dumpling","label":"dumpling","description":"filled"}],"custom":true},{"key":"q1","title":"fav-langs","description":"Q2 favorite languages","type":"multiselect","options":[{"value":"Kotlin","label":"Kotlin","description":"jvm"},{"value":"TypeScript","label":"TypeScript","description":"typed js"},{"value":"Rust","label":"Rust","description":"systems"},{"value":"Python","label":"Python","description":"simple"}],"custom":true}]}}"""

    @Test
    fun `form created maps to QuestionAsked with keys and option values`() {
        val event = V2FormMapper.map("form.created", props(formCreatedJson))
        assertNotNull(event)
        val asked = event as SseEvent.QuestionAsked
        assertEquals("frm_00033307b001qKsOZcHoEWf9Q9", asked.id)
        assertEquals("ses_fffccfb23ffeuwKb5IHtkqiUuK", asked.sessionId)
        assertEquals(2, asked.questions.size)
        assertNotNull(asked.tool)
        assertEquals("msg_0003319d4001vQ6DyeLnVu2l8b", asked.tool!!.messageId)
        assertEquals("call_c0efa0c5b11a4ce3999f84a1", asked.tool!!.callId)

        // q0: single (string), key=q0
        val q0 = asked.questions[0]
        assertEquals("q0", q0.key)
        assertEquals("today-eat", q0.header)
        assertEquals("Q1 what to eat", q0.question)
        assertEquals(false, q0.multiple)
        assertEquals(true, q0.custom)
        assertEquals(3, q0.options.size)
        assertEquals("rice", q0.options[0].label)
        assertEquals("rice", q0.options[0].value)
        assertEquals("staple", q0.options[0].description)

        // q1: multi (multiselect), key=q1
        val q1 = asked.questions[1]
        assertEquals("q1", q1.key)
        assertEquals("fav-langs", q1.header)
        assertEquals("Q2 favorite languages", q1.question)
        assertEquals(true, q1.multiple)
        assertEquals(4, q1.options.size)
        assertEquals("Kotlin", q1.options[0].value)
    }

    @Test
    fun `form created with non-question kind is ignored`() {
        val event = V2FormMapper.map(
            "form.created",
            props("""{"form":{"id":"frm_1","sessionID":"ses_1","title":"T","metadata":{"kind":"permission"},"fields":[{"key":"q0","type":"string"}]}}""")
        )
        assertNull(event)
    }

    @Test
    fun `form replied maps to QuestionReplied`() {
        val event = V2FormMapper.map(
            "form.replied",
            props("""{"id":"frm_00033307b001qKsOZcHoEWf9Q9","sessionID":"ses_fffccfb23ffeuwKb5IHtkqiUuK","answer":{"q0":"rice","q1":["Kotlin","Rust"]}}""")
        )
        assertNotNull(event)
        val replied = event as SseEvent.QuestionReplied
        assertEquals("ses_fffccfb23ffeuwKb5IHtkqiUuK", replied.sessionId)
        assertEquals("frm_00033307b001qKsOZcHoEWf9Q9", replied.requestId)
    }

    @Test
    fun `form cancelled maps to QuestionRejected`() {
        val event = V2FormMapper.map(
            "form.cancelled",
            props("""{"id":"frm_000347a100019SJHkctfuefGw2","sessionID":"ses_fffccfb23ffeuwKb5IHtkqiUuK"}""")
        )
        assertNotNull(event)
        val rejected = event as SseEvent.QuestionRejected
        assertEquals("ses_fffccfb23ffeuwKb5IHtkqiUuK", rejected.sessionId)
        assertEquals("frm_000347a100019SJHkctfuefGw2", rejected.requestId)
    }

    @Test
    fun `unrelated event returns null`() {
        assertNull(V2FormMapper.map("session.text.delta", props("""{"delta":"x"}""")))
    }

    // ============ REST polling mapping ============

    @Test
    fun `form request REST maps to QuestionRequest DTO`() {
        // GET /api/form/request data array element (same shape as form.created form)
        val req = V2FormMapper.toQuestionRequest(
            json.parseToJsonElement(formCreatedJson).jsonObject["form"]!!.jsonObject
        )
        assertNotNull(req)
        assertEquals("frm_00033307b001qKsOZcHoEWf9Q9", req!!.id)
        assertEquals("ses_fffccfb23ffeuwKb5IHtkqiUuK", req.sessionId)
        assertEquals(2, req.questions.size)
        assertEquals("q0", req.questions[0].key)
        assertEquals("Q1 what to eat", req.questions[0].question)
        assertEquals("rice", req.questions[0].options[0].value)
        assertEquals(true, req.questions[1].multiple)
        assertNotNull(req.tool)
        assertEquals("call_c0efa0c5b11a4ce3999f84a1", req.tool!!.callId)
    }

    // ============ reply body construction ============

    @Test
    fun `build answer body maps labels to values with keys`() {
        val event = V2FormMapper.map("form.created", props(formCreatedJson)) as SseEvent.QuestionAsked
        // UI submits labels (incl custom input); single takes first, multi is array
        val body = V2FormMapper.buildAnswerBody(
            answers = listOf(listOf("rice"), listOf("Kotlin", "Rust")),
            questions = event.questions
        )
        val answer = body["answer"]!!.jsonObject
        assertEquals("rice", answer["q0"]!!.jsonPrimitive.content)
        val q1 = answer["q1"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Kotlin", "Rust"), q1)
    }

    @Test
    fun `build answer body falls back to label for custom input`() {
        val event = V2FormMapper.map("form.created", props(formCreatedJson)) as SseEvent.QuestionAsked
        // custom input (not in options) -> submitted as-is
        val body = V2FormMapper.buildAnswerBody(
            answers = listOf(listOf("my-custom-dish"), emptyList()),
            questions = event.questions
        )
        val answer = body["answer"]!!.jsonObject
        assertEquals("my-custom-dish", answer["q0"]!!.jsonPrimitive.content)
        // q1 unanswered -> absent
        assertNull(answer["q1"])
    }

    @Test
    fun `build answer body skips questions without key`() {
        // V1 style (no key) -> no answer constructed (V1 uses /api/question old path)
        val q = SseEvent.QuestionAsked.Question(
            header = "h", question = "q", options = emptyList(), key = null
        )
        val body = V2FormMapper.buildAnswerBody(
            answers = listOf(listOf("x")),
            questions = listOf(q)
        )
        assertTrue(body["answer"]!!.jsonObject.isEmpty())
    }
}
