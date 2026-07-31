package dev.leonardo.octether.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [appendDataLine] — the SSE event-level OOM protection.
 *
 * Uses a small [maxEventSize] parameter (instead of the production 1MB constant)
 * to test boundary behavior quickly without allocating large buffers.
 *
 * Coverage:
 * - Normal append (under limit)
 * - Overflow triggers clear + drop
 * - Recovery after clear (next frame resumes normally)
 * - Multi-line accumulation with separator accounting
 * - Boundary: exactly at limit (should be accepted, off-by-one guard)
 * - Edge: empty payload
 */
class SseOOMProtectionTest {

    @Test
    fun `appends payload when under limit`() {
        val buffer = mutableListOf<List<Byte>>()
        appendDataLine(buffer, byteListOf(1, 2, 3), maxEventSize = 10)

        assertEquals(1, buffer.size)
        assertEquals(listOf<Byte>(1, 2, 3), buffer[0])
    }

    @Test
    fun `clears buffer and drops payload when single payload exceeds limit`() {
        val buffer = mutableListOf<List<Byte>>()
        // payload size 3 > maxEventSize 2 → clear + drop
        appendDataLine(buffer, byteListOf(1, 2, 3), maxEventSize = 2)

        assertTrue("buffer should be empty after overflow", buffer.isEmpty())
    }

    @Test
    fun `clears buffer when accumulated size exceeds limit`() {
        val buffer = mutableListOf<List<Byte>>()
        appendDataLine(buffer, byteListOf(1, 2, 3), maxEventSize = 10)  // projected = 3
        appendDataLine(buffer, byteListOf(4, 5, 6, 7, 8), maxEventSize = 10)  // projected = 3 + 1 + 5 = 9
        assertEquals(2, buffer.size)

        // Third append: projected = 3 + 5 + 1 + 3 = 12 > 10 → clear all
        appendDataLine(buffer, byteListOf(9, 10, 11), maxEventSize = 10)
        assertTrue("buffer should be cleared when accumulated size exceeds limit", buffer.isEmpty())
    }

    @Test
    fun `resumes normally after clear`() {
        val buffer = mutableListOf<List<Byte>>()
        appendDataLine(buffer, byteListOf(1, 2, 3), maxEventSize = 2)  // overflow → clear
        appendDataLine(buffer, byteListOf(1), maxEventSize = 10)       // normal resume

        assertEquals("should resume normal operation after clear", 1, buffer.size)
    }

    @Test
    fun `accumulates multiple lines with separator accounting`() {
        val buffer = mutableListOf<List<Byte>>()
        // Line 1: projected = 0 + 2 + 0 = 2 (buffer empty, no separator)
        appendDataLine(buffer, byteListOf(1, 2), maxEventSize = 10)
        // Line 2: projected = 2 + 2 + 1 = 5 (buffer has 1 line → +1 separator)
        appendDataLine(buffer, byteListOf(3, 4), maxEventSize = 10)
        // Line 3: projected = 4 + 2 + 2 = 8 (buffer has 2 lines → +2 separators)
        appendDataLine(buffer, byteListOf(5, 6), maxEventSize = 10)

        assertEquals(3, buffer.size)
    }

    @Test
    fun `boundary exactly at limit is accepted`() {
        val buffer = mutableListOf<List<Byte>>()
        // projected = 0 + 3 + 0 = 3, maxEventSize = 3 → NOT exceed (>), boundary accepted
        appendDataLine(buffer, byteListOf(1, 2, 3), maxEventSize = 3)

        assertEquals("boundary exactly at limit should be accepted (off-by-one guard)", 1, buffer.size)
    }

    @Test
    fun `empty payload under limit is appended`() {
        val buffer = mutableListOf<List<Byte>>()
        appendDataLine(buffer, emptyList(), maxEventSize = 1)
        // projected = 0 + 0 + 0 = 0 ≤ 1 → append
        assertEquals(1, buffer.size)
    }

    @Test
    fun `empty payload on empty buffer with zero limit is accepted`() {
        val buffer = mutableListOf<List<Byte>>()
        // projected = 0, maxEventSize = 0 → 0 > 0 is false → append
        appendDataLine(buffer, emptyList(), maxEventSize = 0)
        assertEquals("zero projected should pass zero-limit boundary", 1, buffer.size)
    }

    private fun byteListOf(vararg bytes: Int): List<Byte> = bytes.map { it.toByte() }
}
