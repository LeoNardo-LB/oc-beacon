package dev.leonardo.ocbeacon.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #79 P0（2026-08-18）：tool part 落库截断——payload JSON 层重写 state.output。
 */
class ToolOutputTruncatorTest {

    private val dq = '"'

    private fun toolPayload(output: String): String =
        "{" + dq + "id" + dq + ":" + dq + "p1" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "bash" + dq + 
            "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "output" + dq + ":" + dq + output + dq + "}}"

    @Test
    fun `short output passes through unchanged`() {
        val payload = toolPayload("hello")
        assertEquals(payload, ToolOutputTruncator.truncateIfNeeded(payload))
    }

    @Test
    fun `long output truncated to preview plus marker`() {
        val long = "x".repeat(5000)
        val result = ToolOutputTruncator.truncateIfNeeded(toolPayload(long))
        assertTrue(result.contains("x".repeat(500)))
        assertTrue(result.contains("[truncated, full output on server]"))
        assertTrue("截断后长度远小于原", result.length < 1000)
    }

    @Test
    fun `non-tool payload without state passthrough`() {
        val payload = "{" + dq + "id" + dq + ":" + dq + "t1" + dq + "," + dq + "type" + dq + ":" + dq + "text" + dq + "," + dq + "text" + dq + ":" + dq + "hi" + dq + "}"
        assertEquals(payload, ToolOutputTruncator.truncateIfNeeded(payload))
    }

    @Test
    fun `malformed json passthrough unchanged`() {
        val bad = "not-json-at-all"
        assertEquals(bad, ToolOutputTruncator.truncateIfNeeded(bad))
    }

    @Test
    fun `other state fields preserved`() {
        val long = "y".repeat(2000)
        val payload = ("{" + dq + "id" + dq + ":" + dq + "p2" + dq + "," + dq + "type" + dq + ":" + dq + "tool" + dq + "," + dq + "tool" + dq + ":" + dq + "read" + dq + 
                "," + dq + "state" + dq + ":{" + dq + "status" + dq + ":" + dq + "completed" + dq + "," + dq + "output" + dq + ":" + dq + long + dq + "," + 
                dq + "title" + dq + ":" + dq + "file.ts" + dq + "," + dq + "input" + dq + ":{" + dq + "path" + dq + ":" + dq + "/a/b" + dq + "}}}")
        val result = ToolOutputTruncator.truncateIfNeeded(payload)
        assertTrue(result.contains("file.ts"))
        assertTrue(result.contains("/a/b"))
    }
}
