package dev.leonardo.ocbeacon.ui.screens.chat.tools

import org.junit.Assert.*
import org.junit.Test

class DiffHelpersTest {

    @Test
    fun `empty inputs produce empty diff`() {
        val result = computeSimpleDiff(emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `all added when before is empty`() {
        val result = computeSimpleDiff(emptyList(), listOf("a", "b"))
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == DiffLineType.ADDED })
    }

    @Test
    fun `all removed when after is empty`() {
        val result = computeSimpleDiff(listOf("a", "b"), emptyList())
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == DiffLineType.REMOVED })
    }

    @Test
    fun `identical lines are unchanged`() {
        val result = computeSimpleDiff(listOf("a", "b"), listOf("a", "b"))
        assertEquals(2, result.size)
        assertTrue(result.all { it.type == DiffLineType.UNCHANGED })
    }

    @Test
    fun `added and removed lines detected`() {
        val result = computeSimpleDiff(listOf("a", "b"), listOf("a", "c"))
        // Should have: unchanged(a), removed(b), added(c)
        assertEquals(3, result.size)
        assertEquals(DiffLineType.UNCHANGED, result[0].type)
        assertEquals("a", result[0].text)
    }
}
