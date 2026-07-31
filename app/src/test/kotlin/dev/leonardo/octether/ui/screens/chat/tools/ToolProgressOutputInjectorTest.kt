package dev.leonardo.octether.ui.screens.chat.tools

import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolProgressOutputInjectorTest {

    private fun tool(callId: String, state: ToolState) = Part.Tool(
        id = "p1", sessionId = "s1", messageId = "m1",
        callId = callId, tool = "bash", state = state
    )

    @Test
    fun `empty progress map returns parts unchanged`() {
        val parts = listOf(tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, emptyMap())
        assertSame(parts, result)
    }

    @Test
    fun `injects output into Running tool by callId`() {
        val parts = listOf(tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to "stdout line"))
        val running = (result[0] as Part.Tool).state as ToolState.Running
        assertEquals("stdout line", running.output)
    }

    @Test
    fun `does not touch Completed tools`() {
        val parts = listOf(tool("c1", ToolState.Completed(output = "done")))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to "stdout"))
        val completed = (result[0] as Part.Tool).state as ToolState.Completed
        assertEquals("done", completed.output)
    }

    @Test
    fun `skips Running tools with no matching callId`() {
        val parts = listOf(tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c2" to "stdout"))
        val running = (result[0] as Part.Tool).state as ToolState.Running
        assertEquals("", running.output)
    }

    @Test
    fun `empty output string does not replace existing`() {
        val parts = listOf(tool("c1", ToolState.Running(output = "existing")))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to ""))
        val running = (result[0] as Part.Tool).state as ToolState.Running
        assertEquals("existing", running.output)
    }

    @Test
    fun `preserves non-Tool parts`() {
        val textPart = Part.Text(id = "p0", sessionId = "s1", messageId = "m1", text = "hello")
        val parts = listOf(textPart, tool("c1", ToolState.Running()))
        val result = ToolProgressOutputInjector.inject(parts, mapOf("c1" to "out"))
        assertEquals(textPart, result[0])
    }
}
