package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #247 回归：回合内连续同键 tool 卡折叠（首张 + ×N）。
 * 用户裁决（2026-08-28）：同 #243 合成卡去重交互。
 */
class RenderableTurnCollapseTest {

    private fun bashTool(id: String, command: String) = Part.Tool(
        id = id,
        tool = "bash",
        state = ToolState.Completed(input = mapOf("command" to JsonPrimitive(command))),
    )

    private fun divider(id: String) = RenderItem.TurnDivider(id)

    private fun single(part: Part) = RenderItem.GroupedParts(PartGroup.Single(part))

    @Test
    fun `three same-command cards collapse to one repeating tool`() {
        val items = listOf(
            single(bashTool("t1", "sleep 30")),
            divider("d1"),
            single(bashTool("t2", "sleep 30")),
            divider("d2"),
            single(bashTool("t3", "sleep 30")),
        )
        val out = collapseConsecutiveToolCards(items)
        assertEquals(1, out.size)
        val rep = out[0] as RenderItem.RepeatingTool
        assertEquals(3, rep.count)
        assertEquals("t1", rep.part.id)
    }

    @Test
    fun `different commands do not collapse`() {
        val items = listOf(
            single(bashTool("t1", "echo a")),
            single(bashTool("t2", "echo b")),
        )
        val out = collapseConsecutiveToolCards(items)
        assertEquals(2, out.size)
        assertTrue(out.none { it is RenderItem.RepeatingTool })
    }

    @Test
    fun `context tools are excluded from dedup`() {
        val read = Part.Tool(
            id = "r1",
            tool = "read",
            state = ToolState.Completed(input = mapOf("filePath" to JsonPrimitive("/a"))),
        )
        val out = collapseConsecutiveToolCards(listOf(single(read), single(read)))
        assertTrue(out.none { it is RenderItem.RepeatingTool })
    }

    @Test
    fun `run ends at different command keeping later card`() {
        val items = listOf(
            single(bashTool("t1", "sleep 30")),
            single(bashTool("t2", "sleep 30")),
            single(bashTool("t3", "echo done")),
        )
        val out = collapseConsecutiveToolCards(items)
        assertEquals(2, out.size)
        assertTrue(out[0] is RenderItem.RepeatingTool)
        assertEquals(2, (out[0] as RenderItem.RepeatingTool).count)
        assertTrue(out[1] !is RenderItem.RepeatingTool)
    }

    @Test
    fun `single occurrence stays unwrapped`() {
        val items = listOf(single(bashTool("t1", "pwd")))
        val out = collapseConsecutiveToolCards(items)
        assertTrue(out[0] !is RenderItem.RepeatingTool)
    }

    @Test
    fun `divider between non-tool items is preserved`() {
        val items = listOf(
            single(bashTool("t1", "pwd")),
            divider("d1"),
            single(bashTool("t2", "ls")),
        )
        val out = collapseConsecutiveToolCards(items)
        assertEquals(3, out.size)
        assertFalse(out.any { it is RenderItem.RepeatingTool })
    }

    @Test
    fun `dedup key ignores volatile fields`() {
        val a = bashTool("t1", "sleep 30")
        val b = bashTool("totally-different-callid", "sleep 30").copy(
            state = ToolState.Running(input = mapOf("command" to JsonPrimitive("sleep 30"))),
        )
        assertEquals(toolDedupKey(a), toolDedupKey(b))
    }
}