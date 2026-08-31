package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DefaultToolCardResolverTest {

    private lateinit var resolver: DefaultToolCardResolver

    @Before
    fun setup() {
        resolver = DefaultToolCardResolver()
    }

    private fun testTool(name: String) = Part.Tool(
        id = "test-part",
        sessionId = "s1",
        messageId = "m1",
        callId = "c1",
        tool = name,
        state = ToolState.Pending(input = emptyMap())
    )

    @Test
    fun `resolves bash tool`() {
        val result = resolver.resolve(testTool("bash"), false, {}, null, null)
        assertNotNull("bash should resolve", result)
    }

    @Test
    fun `resolves edit tool`() {
        val result = resolver.resolve(testTool("edit"), false, {}, null, null)
        assertNotNull("edit should resolve", result)
    }

    @Test
    fun `resolves glob tool`() {
        val result = resolver.resolve(testTool("glob"), false, {}, null, null)
        assertNotNull("glob should resolve", result)
    }

    @Test
    fun `resolves task tool`() {
        val result = resolver.resolve(testTool("task"), false, {}, null, null)
        assertNotNull("task should resolve", result)
    }

    @Test
    fun `resolves webfetch tool`() {
        val result = resolver.resolve(testTool("webfetch"), false, {}, null, null)
        assertNotNull("webfetch should resolve", result)
    }

    @Test
    fun `resolves web_fetch tool`() {
        val result = resolver.resolve(testTool("web_fetch"), false, {}, null, null)
        assertNotNull("web_fetch should resolve", result)
    }

    @Test
    fun `resolves apply_patch tool`() {
        val result = resolver.resolve(testTool("apply_patch"), false, {}, null, null)
        assertNotNull("apply_patch should resolve", result)
    }

    @Test
    fun `resolves subagent_fork tool (Task 3a)`() {
        val result = resolver.resolve(testTool("subagent_fork"), false, {}, null, null)
        assertNotNull("subagent_fork should resolve to TaskToolCard", result)
    }

    @Test
    fun `resolves camelCase subagentFork tool (Task 3a)`() {
        val result = resolver.resolve(testTool("subagentFork"), false, {}, null, null)
        assertNotNull("subagentFork (camelCase) should resolve", result)
    }

    @Test
    fun `resolves fork tool (Task 3a)`() {
        val result = resolver.resolve(testTool("fork"), false, {}, null, null)
        assertNotNull("fork should resolve", result)
    }

    @Test
    fun `returns null for unknown tool`() {
        val result = resolver.resolve(testTool("unknown_tool_xyz"), false, {}, null, null)
        assertNull("unknown tool should not resolve", result)
    }

    @Test
    fun `case insensitive matching`() {
        val result = resolver.resolve(testTool("Bash"), false, {}, null, null)
        assertNotNull("Bash (capitalized) should resolve", result)
    }
}
