package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * extractToolSubagentSessionId（synthetic 完成通知「定位发起卡片」的匹配键）测试。
 * 与 TaskToolCard 的子智能体会话跳转解析一致：Completed/Running 的 metadata.sessionId。
 */
class ExtractToolSubagentSessionIdTest {

    private fun toolWithMetadata(metadata: Map<String, kotlinx.serialization.json.JsonElement>?): Part.Tool =
        Part.Tool(
            id = "call_1",
            sessionId = "s",
            messageId = "m",
            tool = "subagent",
            state = ToolState.Completed(
                input = emptyMap(),
                output = "",
                metadata = metadata
            )
        )

    @Test
    fun `completed with sessionId extracted`() {
        val meta = buildJsonObject { put("sessionId", "ses_child_1") }
        assertEquals("ses_child_1", extractToolSubagentSessionId(toolWithMetadata(meta)))
    }

    @Test
    fun `completed with sessionID uppercase extracted`() {
        val meta = buildJsonObject { put("sessionID", "ses_child_2") }
        assertEquals("ses_child_2", extractToolSubagentSessionId(toolWithMetadata(meta)))
    }

    @Test
    fun `completed with jobId extracted (V2 server key)`() {
        // V2 服务器 metadata 用 jobId 存子智能体会话 ID（task.ts: jobId = nextSession.id）
        val meta = buildJsonObject { put("jobId", "ses_child_job") }
        assertEquals("ses_child_job", extractToolSubagentSessionId(toolWithMetadata(meta)))
    }

    @Test
    fun `sessionId 优先于 jobId`() {
        val meta = buildJsonObject {
            put("sessionId", "ses_child_primary")
            put("jobId", "ses_child_job")
        }
        assertEquals("ses_child_primary", extractToolSubagentSessionId(toolWithMetadata(meta)))
    }

    @Test
    fun `no metadata returns null`() {
        assertNull(extractToolSubagentSessionId(toolWithMetadata(null)))
    }

    @Test
    fun `running state also extracted`() {
        val tool = Part.Tool(
            id = "call_2",
            sessionId = "s",
            messageId = "m",
            tool = "task",
            state = ToolState.Running(
                input = emptyMap(),
                output = "",
                metadata = buildJsonObject { put("sessionID", "ses_running_1") }
            )
        )
        assertEquals("ses_running_1", extractToolSubagentSessionId(tool))
    }

    @Test
    fun `blank sessionId returns null`() {
        val meta = buildJsonObject { put("sessionId", "  ") }
        assertNull(extractToolSubagentSessionId(toolWithMetadata(meta)))
    }

    // ===== 2026-09-01（DSH 定位卡/前向箭头）：DSH 工具卡无 metadata 槽——
    //     子会话 id 经调用参数（state.input）携带时须可解析 =====

    @Test
    fun `completed input sessionId fallback (DSH no metadata)`() {
        // DSH mapToolCall：metadata 缺失，sessionId 在工具参数里（input 键）
        val tool = Part.Tool(
            id = "call_10",
            sessionId = "s",
            messageId = "m",
            tool = "subagent",
            state = ToolState.Completed(
                input = buildJsonObject { put("sessionId", "session-child-uuid") },
                output = "",
            )
        )
        assertEquals("session-child-uuid", extractToolSubagentSessionId(tool))
    }

    @Test
    fun `pending input sessionID fallback (DSH tool in flight)`() {
        val tool = Part.Tool(
            id = "call_11",
            sessionId = "s",
            messageId = "m",
            tool = "task",
            state = ToolState.Pending(
                input = buildJsonObject { put("sessionID", "session-abc-123") },
                raw = "{\"sessionID\":\"session-abc-123\"}",
            )
        )
        assertEquals("session-abc-123", extractToolSubagentSessionId(tool))
    }

    @Test
    fun `metadata takes priority over input (V1 V2 behavior unchanged)`() {
        val tool = Part.Tool(
            id = "call_12",
            sessionId = "s",
            messageId = "m",
            tool = "subagent",
            state = ToolState.Completed(
                input = buildJsonObject { put("sessionId", "input-id") },
                output = "",
                metadata = buildJsonObject { put("jobId", "meta-id") },
            )
        )
        // metadata.jobId 命中（元数据优先），不回退 input
        assertEquals("meta-id", extractToolSubagentSessionId(tool))
    }

    @Test
    fun `input without session id keys returns null (DSH)`() {
        val tool = Part.Tool(
            id = "call_13",
            sessionId = "s",
            messageId = "m",
            tool = "task",
            state = ToolState.Pending(input = buildJsonObject { put("description", "no id here") }),
        )
        assertNull(extractToolSubagentSessionId(tool))
    }
}
