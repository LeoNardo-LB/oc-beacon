package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #180（2026-08-21）：Running 期子智能体会话 id 注入契约——
 * tool.progress metadata.sessionID → Part.Tool(Running).metadata.sessionId/sessionID，
 * TaskToolCard 据此在 Running 期显示导航并跳转。
 */
class ToolProgressChildSessionInjectionTest {

    private fun runningTool(callId: String, output: String = "") = Part.Tool(
        id = callId, sessionId = "parent", messageId = "m1", callId = callId,
        tool = "subagent", state = ToolState.Running(input = emptyMap(), output = output),
    )

    @Test
    fun injectsChildSessionIdIntoRunningToolMetadata() {
        val part = runningTool("call_1")
        val out = ToolProgressOutputInjector.inject(
            listOf(part),
            progressOutputs = mapOf("call_1" to "partial output"),
            childSessionIds = mapOf("call_1" to "ses_child_9"),
        )
        val st = (out[0] as Part.Tool).state as ToolState.Running
        assertEquals("ses_child_9", st.metadata?.get("sessionID")?.let { (it as JsonPrimitive).content })
        assertEquals("ses_child_9", st.metadata?.get("sessionId")?.let { (it as JsonPrimitive).content })
        assertEquals("partial output", st.output)
    }

    @Test
    fun childIdOnlyStillInjectsMetadataWithoutTouchingOutput() {
        val part = runningTool("call_1", output = "keep me")
        val out = ToolProgressOutputInjector.inject(
            listOf(part),
            progressOutputs = emptyMap(),
            childSessionIds = mapOf("call_1" to "ses_child_2"),
        )
        val st = (out[0] as Part.Tool).state as ToolState.Running
        assertEquals("ses_child_2", st.metadata?.get("sessionID")?.let { (it as JsonPrimitive).content })
        assertEquals("keep me", st.output)
    }

    @Test
    fun noMatchReturnsSameReference() {
        val parts = listOf(runningTool("call_other"))
        val out = ToolProgressOutputInjector.inject(parts, emptyMap(), mapOf("call_1" to "ses_x"))
        assertSame(parts, out)
    }

    @Test
    fun completedToolsAreNotTouched() {
        val done = Part.Tool(
            id = "c", sessionId = "s", messageId = "m", callId = "c", tool = "subagent",
            state = ToolState.Completed(output = "done", metadata = null),
        )
        val out = ToolProgressOutputInjector.inject(listOf(done), emptyMap(), mapOf("c" to "ses_x"))
        assertSame(done, out[0])
    }

    @Test
    fun blankChildIdNotInjected() {
        val part = runningTool("call_1")
        val out = ToolProgressOutputInjector.inject(listOf(part), mapOf("call_1" to "out"), emptyMap())
        val st = (out[0] as Part.Tool).state as ToolState.Running
        assertNull(st.metadata)
        assertEquals("out", st.output)
    }
}
