package dev.leonardo.ocbeacon.ui.screens.chat.util

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class QuestionParserTest {

    // ===== parseQuestionContent =====

    @Test
    fun `opencode text format - extracts question field and quoted answers`() {
        val raw = """Asked 3 questions. questions: [{"question":"Pick a language"}]
            |User has answered: "Kotlin", "Python". You can continue.""".trimMargin()

        val r = QuestionParser.parseQuestionContent(raw)

        assertEquals("Pick a language", r.displayText)
        assertTrue("Kotlin" in r.answers)
        assertTrue("Python" in r.answers)
    }

    @Test
    fun `opencode text format - plain answer without quotes`() {
        val raw = "User has answered: yes I want to proceed"

        val r = QuestionParser.parseQuestionContent(raw)

        assertTrue(r.displayText.isNotBlank())
        assertTrue(r.answers.isNotEmpty())
    }

    @Test
    fun `JSON format - single answer field`() {
        val r = QuestionParser.parseQuestionContent("""{"question":"Continue?","answer":"yes"}""")

        assertEquals("Continue?", r.displayText)
        assertEquals(listOf("yes"), r.answers)
    }

    @Test
    fun `JSON format - answers array`() {
        val r = QuestionParser.parseQuestionContent("""{"question":"Which?","answers":["A","B","C"]}""")

        assertEquals("Which?", r.displayText)
        assertEquals(listOf("A", "B", "C"), r.answers)
    }

    @Test
    fun `plain text fallback - no markers`() {
        val r = QuestionParser.parseQuestionContent("Just a simple question")

        assertEquals("Just a simple question", r.displayText)
        assertTrue(r.answers.isEmpty())
    }

    @Test
    fun `blank input returns raw as displayText`() {
        val r = QuestionParser.parseQuestionContent("   ")

        assertEquals("   ", r.displayText)
        assertTrue(r.answers.isEmpty())
    }

    // ===== parseQuestionFromToolData =====

    @Test
    fun `structured input extracts questions and options`() {
        val input = mapOf(
            "questions" to buildJsonArray {
                add(buildJsonObject {
                    put("question", "Pick a language")
                    put("options", buildJsonArray {
                        add(buildJsonObject { put("label", "Kotlin"); put("description", "JVM") })
                        add(buildJsonObject { put("label", "Python") })
                    })
                })
            }
        )

        val items = QuestionParser.parseQuestionFromToolData("t1", input, "")

        assertEquals(1, items.size)
        assertEquals("Pick a language", items[0].text)
        assertEquals(2, items[0].options.size)
        assertEquals("Kotlin", items[0].options[0].label)
        assertEquals("JVM", items[0].options[0].description)
    }

    @Test
    fun `answer pairs mapped from output`() {
        val input = mapOf(
            "questions" to buildJsonArray {
                add(buildJsonObject { put("question", "Q1") })
                add(buildJsonObject { put("question", "Q2") })
            }
        )
        val output = """User has answered: "Q1"="A1, A2", "Q2"="B1". You can continue."""

        val items = QuestionParser.parseQuestionFromToolData("t1", input, output)

        assertEquals(listOf("A1", "A2"), items[0].answers)
        assertEquals(listOf("B1"), items[1].answers)
    }

    @Test
    fun `fallback plain answer after equals`() {
        val input = mapOf(
            "questions" to buildJsonArray {
                add(buildJsonObject { put("question", "Continue?") })
            }
        )
        val output = "User has answered: result = yes, ok"

        val items = QuestionParser.parseQuestionFromToolData("t1", input, output)

        assertEquals(1, items.size)
        assertEquals(listOf("yes", "ok"), items[0].answers)
    }

    @Test
    fun `empty input and output returns single blank item`() {
        val items = QuestionParser.parseQuestionFromToolData("t1", emptyMap(), "")
        assertEquals(1, items.size)
        assertEquals("", items[0].text)
    }
}
