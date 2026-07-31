package dev.leonardo.octether.ui.screens.chat.tools

import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartGrouperTest {

    private fun tool(id: String, toolName: String, input: Map<String, JsonElement> = emptyMap()): Part.Tool {
        return Part.Tool(
            id = id,
            sessionId = "sess-1",
            messageId = "msg-1",
            callId = "call-$id",
            tool = toolName,
            state = ToolState.Completed(input = input, output = "ok"),
        )
    }

    private fun text(id: String): Part.Text {
        return Part.Text(id = id, sessionId = "sess-1", messageId = "msg-1", text = "hello")
    }

    @Test
    fun `two consecutive reads are grouped`() {
        val parts = listOf(
            tool("p1", "read", mapOf("filePath" to JsonPrimitive("/a.kt"))),
            tool("p2", "read", mapOf("filePath" to JsonPrimitive("/b.kt"))),
        )
        val groups = groupContextParts(parts)
        assertEquals(1, groups.size)
        assertTrue(groups[0] is PartGroup.Context)
        assertEquals(2, (groups[0] as PartGroup.Context).parts.size)
    }

    @Test
    fun `single read is not grouped`() {
        val parts = listOf(tool("p1", "read"))
        val groups = groupContextParts(parts)
        assertEquals(1, groups.size)
        assertTrue(groups[0] is PartGroup.Single)
    }

    @Test
    fun `read glob grep are grouped together`() {
        val parts = listOf(
            tool("p1", "read"),
            tool("p2", "glob"),
            tool("p3", "grep"),
        )
        val groups = groupContextParts(parts)
        assertEquals(1, groups.size)
        assertTrue(groups[0] is PartGroup.Context)
        assertEquals(3, (groups[0] as PartGroup.Context).parts.size)
    }

    @Test
    fun `bash splits context groups`() {
        val parts = listOf(
            tool("p1", "read"),
            tool("p2", "read"),
            tool("p3", "bash"),
            tool("p4", "read"),
        )
        val groups = groupContextParts(parts)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is PartGroup.Context)
        assertTrue(groups[1] is PartGroup.Single)
        assertTrue(groups[2] is PartGroup.Single)
    }

    @Test
    fun `text part splits context groups`() {
        val parts = listOf(
            tool("p1", "read"),
            text("p2"),
            tool("p3", "read"),
            tool("p4", "read"),
        )
        val groups = groupContextParts(parts)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is PartGroup.Single)
        assertTrue(groups[1] is PartGroup.Single)
        assertTrue(groups[2] is PartGroup.Context)
    }

    @Test
    fun `summary counts read glob grep correctly`() {
        val parts = listOf(
            tool("p1", "read"),
            tool("p2", "read"),
            tool("p3", "glob"),
            tool("p4", "grep"),
            tool("p5", "list"),
        )
        val summary = contextToolSummary(parts)
        assertEquals(2, summary.read)
        assertEquals(2, summary.search)
    }

    @Test
    fun `tool names are case insensitive`() {
        val parts = listOf(
            tool("p1", "READ"),
            tool("p2", "Glob"),
        )
        val groups = groupContextParts(parts)
        assertEquals(1, groups.size)
        assertTrue(groups[0] is PartGroup.Context)
    }

    @Test
    fun `empty list returns empty`() {
        val groups = groupContextParts(emptyList())
        assertTrue(groups.isEmpty())
    }
}
