package dev.leonardo.octether.ui.screens.chat

import dev.leonardo.octether.domain.model.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for Part rendering logic extracted from ChatScreen.kt.
 *
 * Core verification: the bug fix ensures that parts are rendered in their
 * original server-sent order (e.g. Text → Tool → Reasoning → Tool → Text)
 * rather than being split into separate groups and rendered out of order.
 */
class PartRenderLogicTest {

    // === isBubbleRenderablePart — renderable types ===

    @Test
    fun `isBubbleRenderablePart returns true for Text`() {
        val part = createTextPart("t1", "Hello")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Reasoning`() {
        val part = createReasoningPart("r1", "Thinking...")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Patch`() {
        val part = Part.Patch(id = "p1", sessionId = "s1", messageId = "m1")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for File`() {
        val part = Part.File(id = "f1", sessionId = "s1", messageId = "m1", mime = "text/plain")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Permission`() {
        val part = Part.Permission(id = "pm1", sessionId = "s1", messageId = "m1")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Question`() {
        val part = Part.Question(id = "q1", sessionId = "s1", messageId = "m1")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Abort`() {
        val part = Part.Abort(id = "a1", sessionId = "s1", messageId = "m1")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Retry`() {
        val part = Part.Retry(id = "rt1", sessionId = "s1", messageId = "m1")
        assertTrue(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns true for Tool`() {
        val part = createToolPart("tool1")
        assertTrue(isBubbleRenderablePart(part))
    }

    // === isBubbleRenderablePart — non-renderable types ===

    @Test
    fun `isBubbleRenderablePart returns false for StepStart`() {
        val part = Part.StepStart(id = "ss1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for StepFinish`() {
        val part = Part.StepFinish(id = "sf1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for Snapshot`() {
        val part = Part.Snapshot(id = "sn1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for Subtask`() {
        val part = Part.Subtask(id = "st1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for Compaction`() {
        val part = Part.Compaction(id = "c1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for Unknown`() {
        val part = Part.Unknown(id = "u1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for SessionTurn`() {
        val part = Part.SessionTurn(id = "turn1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    @Test
    fun `isBubbleRenderablePart returns false for Agent`() {
        val part = Part.Agent(id = "ag1", sessionId = "s1", messageId = "m1")
        assertFalse(isBubbleRenderablePart(part))
    }

    // === filterRenderableParts — order preservation (core bug fix) ===

    @Test
    fun `filterRenderableParts preserves original interleaved order of Text-Tool-Reasoning-Tool-Text`() {
        // This is the exact scenario the bug fix addresses:
        // Server sends: Text → Tool → Reasoning → Tool → Text
        // Before fix: rendered as Tool, Tool, Text, Reasoning, Text (grouped)
        // After fix: rendered as Text, Tool, Reasoning, Tool, Text (original order)
        val parts = listOf(
            createTextPart("t1", "Let me analyze this."),
            createToolPart("tool1", toolName = "read"),
            createReasoningPart("r1", "The file contains..."),
            createToolPart("tool2", toolName = "grep"),
            createTextPart("t2", "Here are the results."),
        )

        val filtered = filterRenderableParts(parts)

        assertEquals(5, filtered.size)
        // Verify the exact order is preserved
        assertEquals("t1", filtered[0].id)
        assertEquals("tool1", filtered[1].id)
        assertEquals("r1", filtered[2].id)
        assertEquals("tool2", filtered[3].id)
        assertEquals("t2", filtered[4].id)

        // Verify types are in the correct interleaved order
        assertTrue(filtered[0] is Part.Text)
        assertTrue(filtered[1] is Part.Tool)
        assertTrue(filtered[2] is Part.Reasoning)
        assertTrue(filtered[3] is Part.Tool)
        assertTrue(filtered[4] is Part.Text)
    }

    @Test
    fun `filterRenderableParts preserves order with Tools between Reasoning blocks`() {
        // Another common pattern: Reasoning → Tool → Reasoning
        val parts = listOf(
            createReasoningPart("r1", "First thought"),
            createToolPart("tool1", toolName = "bash"),
            createReasoningPart("r2", "Based on output..."),
            createToolPart("tool2", toolName = "write"),
            createReasoningPart("r3", "Final analysis"),
        )

        val filtered = filterRenderableParts(parts)

        assertEquals(5, filtered.size)
        assertTrue(filtered[0] is Part.Reasoning)
        assertTrue(filtered[1] is Part.Tool)
        assertTrue(filtered[2] is Part.Reasoning)
        assertTrue(filtered[3] is Part.Tool)
        assertTrue(filtered[4] is Part.Reasoning)
    }

    @Test
    fun `filterRenderableParts removes non-renderable parts while preserving renderable order`() {
        val parts = listOf(
            Part.StepStart(id = "ss1", sessionId = "s1", messageId = "m1"),
            createTextPart("t1", "Hello"),
            Part.StepFinish(id = "sf1", sessionId = "s1", messageId = "m1"),
            createToolPart("tool1"),
            Part.Snapshot(id = "sn1", sessionId = "s1", messageId = "m1"),
            createReasoningPart("r1", "Think"),
            Part.Agent(id = "ag1", sessionId = "s1", messageId = "m1"),
        )

        val filtered = filterRenderableParts(parts)

        assertEquals(3, filtered.size)
        assertEquals("t1", filtered[0].id)
        assertEquals("tool1", filtered[1].id)
        assertEquals("r1", filtered[2].id)
    }

    @Test
    fun `filterRenderableParts returns empty list when all parts are non-renderable`() {
        val parts = listOf(
            Part.StepStart(id = "ss1", sessionId = "s1", messageId = "m1"),
            Part.StepFinish(id = "sf1", sessionId = "s1", messageId = "m1"),
            Part.Snapshot(id = "sn1", sessionId = "s1", messageId = "m1"),
        )

        val filtered = filterRenderableParts(parts)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterRenderableParts returns all parts when all are renderable`() {
        val parts = listOf(
            createTextPart("t1", "A"),
            createToolPart("tool1"),
            createReasoningPart("r1", "B"),
        )

        val filtered = filterRenderableParts(parts)

        assertEquals(3, filtered.size)
        assertEquals(parts, filtered)
    }

    @Test
    fun `filterRenderableParts returns empty list for empty input`() {
        val filtered = filterRenderableParts(emptyList())
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `filterRenderableParts preserves order of Text-Patch-File-Tool mix`() {
        val parts = listOf(
            createTextPart("t1", "I made changes"),
            Part.Patch(id = "p1", sessionId = "s1", messageId = "m1", files = listOf("Main.kt")),
            createToolPart("tool1", toolName = "write"),
            Part.File(id = "f1", sessionId = "s1", messageId = "m1", mime = "image/png"),
            createTextPart("t2", "See screenshot above"),
        )

        val filtered = filterRenderableParts(parts)

        assertEquals(5, filtered.size)
        assertEquals(listOf("t1", "p1", "tool1", "f1", "t2"), filtered.map { it.id })
    }

    // === Helpers ===

    private fun createTextPart(id: String, text: String): Part.Text =
        Part.Text(id = id, sessionId = "s1", messageId = "m1", text = text)

    private fun createReasoningPart(id: String, text: String): Part.Reasoning =
        Part.Reasoning(id = id, sessionId = "s1", messageId = "m1", text = text)

    private fun createToolPart(
        id: String,
        toolName: String = "bash"
    ): Part.Tool = Part.Tool(
        id = id,
        sessionId = "s1",
        messageId = "m1",
        callId = "call-$id",
        tool = toolName,
        state = ToolState.Running()
    )
}
