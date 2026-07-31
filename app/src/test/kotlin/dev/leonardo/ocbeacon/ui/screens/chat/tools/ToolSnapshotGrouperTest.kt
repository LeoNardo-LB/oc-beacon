package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSnapshotGrouperTest {

    private fun makeTool(
        id: String,
        toolName: String,
        messageId: String = "msg-1",
        filePath: String,
        oldString: String = "",
        newString: String = "",
        content: String = "",
        before: String? = null,
        after: String? = null
    ): Part.Tool {
        val input = mutableMapOf<String, JsonElement>()
        input["filePath"] = JsonPrimitive(filePath)
        if (toolName.equals("write", true)) input["content"] = JsonPrimitive(content)
        if (toolName.equals("edit", true)) {
            input["oldString"] = JsonPrimitive(oldString)
            input["newString"] = JsonPrimitive(newString)
        }
        val metadata: Map<String, JsonElement>? = if (before != null || after != null) {
            mapOf("filediff" to buildJsonObject {
                if (before != null) put("before", JsonPrimitive(before))
                if (after != null) put("after", JsonPrimitive(after))
            })
        } else null
        return Part.Tool(
            id = id, sessionId = "sess-1", messageId = messageId,
            callId = "call-$id", tool = toolName,
            state = ToolState.Completed(
                input = input,
                output = "ok",
                metadata = metadata
            )
        )
    }

    @Test
    fun `empty list returns empty groups`() {
        assertTrue(ToolSnapshotGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `single Read tool produces single group with count 1`() {
        val tool = makeTool("p1", "read", filePath = "app/src/Main.kt", content = "class Main")
        val groups = ToolSnapshotGrouper.group(listOf(tool))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].toolParts.size)
        assertEquals("app/src/Main.kt", groups[0].normalizedFilePath)
    }

    @Test
    fun `three adjacent Edits same file produce single group`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "a", newString = "b", before = "a", after = "b"),
            makeTool("p2", "edit", filePath = "app/User.kt", oldString = "b", newString = "c", before = "b", after = "c"),
            makeTool("p3", "edit", filePath = "app/User.kt", oldString = "c", newString = "d", before = "c", after = "d")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(3, groups[0].toolParts.size)
    }

    @Test
    fun `Bash tool between two Edits same file does not break grouping`() {
        val bashTool = Part.Tool(
            id = "b1", sessionId = "s", messageId = "msg-1",
            callId = "cb1", tool = "bash",
            state = ToolState.Completed(input = mapOf("command" to JsonPrimitive("ls")), output = "")
        )
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "a", newString = "b"),
            bashTool,
            makeTool("p2", "edit", filePath = "app/User.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].toolParts.size)
    }

    @Test
    fun `two different files produce two groups preserving first-occurrence order`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/A.kt", oldString = "a", newString = "b"),
            makeTool("p2", "edit", filePath = "app/B.kt", oldString = "x", newString = "y"),
            makeTool("p3", "edit", filePath = "app/A.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(2, groups.size)
        assertEquals("app/A.kt", groups[0].normalizedFilePath)
        assertEquals(2, groups[0].toolParts.size)
        assertEquals("app/B.kt", groups[1].normalizedFilePath)
        assertEquals(1, groups[1].toolParts.size)
    }

    @Test
    fun `Write and Edit on same file in same message produce single group`() {
        val tools = listOf(
            makeTool("p1", "write", filePath = "app/New.kt", content = "initial"),
            makeTool("p2", "edit", filePath = "app/New.kt", oldString = "initial", newString = "updated", before = "initial", after = "updated")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].toolParts.size)
    }

    @Test
    fun `path normalization treats backslash and forward slash as same file`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app\\src\\User.kt", oldString = "a", newString = "b"),
            makeTool("p2", "edit", filePath = "app/src/User.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].toolParts.size)
    }

    @Test
    fun `same file across different messages produces two groups`() {
        val tools = listOf(
            makeTool("p1", "edit", messageId = "msg-1", filePath = "app/User.kt", oldString = "a", newString = "b"),
            makeTool("p2", "edit", messageId = "msg-2", filePath = "app/User.kt", oldString = "b", newString = "c")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals(2, groups.size)
    }

    @Test
    fun `cumulativeBefore is first part before cumulativeAfter is last part after`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "v0", newString = "v1", before = "v0", after = "v1"),
            makeTool("p2", "edit", filePath = "app/User.kt", oldString = "v1", newString = "v2", before = "v1", after = "v2"),
            makeTool("p3", "edit", filePath = "app/User.kt", oldString = "v2", newString = "v3", before = "v2", after = "v3")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals("v0", groups[0].cumulativeBefore)
        assertEquals("v3", groups[0].cumulativeAfter)
    }

    @Test
    fun `cumulativeBefore is empty for Write-only group`() {
        val tools = listOf(
            makeTool("p1", "write", filePath = "app/New.kt", content = "new content")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals("", groups[0].cumulativeBefore)
        assertEquals("new content", groups[0].cumulativeAfter)
    }

    @Test
    fun `cumulativeBefore falls back to oldString when metadata missing`() {
        val tools = listOf(
            makeTool("p1", "edit", filePath = "app/User.kt", oldString = "fallbackBefore", newString = "fallbackAfter")
        )
        val groups = ToolSnapshotGrouper.group(tools)
        assertEquals("fallbackBefore", groups[0].cumulativeBefore)
        assertEquals("fallbackAfter", groups[0].cumulativeAfter)
    }

    @Test
    fun `non Read Write Edit tools are ignored`() {
        val bashTool = Part.Tool(
            id = "b1", sessionId = "s", messageId = "msg-1",
            callId = "cb1", tool = "bash",
            state = ToolState.Completed(input = emptyMap(), output = "")
        )
        val globTool = Part.Tool(
            id = "g1", sessionId = "s", messageId = "msg-1",
            callId = "cg1", tool = "glob",
            state = ToolState.Completed(input = emptyMap(), output = "")
        )
        assertTrue(ToolSnapshotGrouper.group(listOf(bashTool, globTool)).isEmpty())
    }

    @Test
    fun `Running state tool is still grouped`() {
        val tool = Part.Tool(
            id = "p1", sessionId = "s", messageId = "msg-1",
            callId = "c1", tool = "edit",
            state = ToolState.Running(input = mapOf<String, JsonElement>("filePath" to JsonPrimitive("app/X.kt")))
        )
        val groups = ToolSnapshotGrouper.group(listOf(tool))
        assertEquals(1, groups.size)
    }

    @Test
    fun `normalizePath helper trims trailing slash and converts backslash`() {
        assertEquals("app/src/X.kt", ToolSnapshotGrouper.normalizePath("app\\src\\X.kt"))
        assertEquals("app/src/X.kt", ToolSnapshotGrouper.normalizePath("app/src/X.kt/"))
        assertEquals("X.kt", ToolSnapshotGrouper.normalizePath("X.kt"))
    }
}
