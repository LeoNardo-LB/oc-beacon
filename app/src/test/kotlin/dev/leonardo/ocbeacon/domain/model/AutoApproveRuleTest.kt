package dev.leonardo.ocbeacon.domain.model

import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import org.junit.Assert.*
import org.junit.Test

class AutoApproveRuleTest {

    private fun testEvent(
        permission: String = "bash",
        sessionId: String = "s1",
        callId: String? = "call-1"
    ) = SseEvent.PermissionAsked(
        id = "perm-1",
        sessionId = sessionId,
        permission = permission,
        tool = callId?.let { ToolRef(messageId = "msg-1", callId = it) }
    )

    @Test
    fun `wildcard rule matches everything`() {
        val rule = AutoApproveRule(toolName = "*")
        assertTrue(rule.matches(testEvent(), "/home/user/project"))
    }

    @Test
    fun `specific tool name matches`() {
        val rule = AutoApproveRule(toolName = "bash")
        assertTrue(rule.matches(testEvent(permission = "bash"), "/project"))
    }

    @Test
    fun `different tool name does not match`() {
        val rule = AutoApproveRule(toolName = "bash")
        assertFalse(rule.matches(testEvent(permission = "edit"), "/project"))
    }

    @Test
    fun `session-scoped rule only matches same session`() {
        val rule = AutoApproveRule(toolName = "*", sessionId = "s1")
        assertTrue(rule.matches(testEvent(sessionId = "s1"), "/project"))
        assertFalse(rule.matches(testEvent(sessionId = "s2"), "/project"))
    }

    @Test
    fun `directory pattern matches specific directory`() {
        val rule = AutoApproveRule(toolName = "*", directoryPattern = "/home/user/project")
        assertTrue(rule.matches(testEvent(), "/home/user/project"))
        assertFalse(rule.matches(testEvent(), "/other/path"))
    }

    @Test
    fun `permission name used when tool is null`() {
        val rule = AutoApproveRule(toolName = "bash")
        val event = testEvent(permission = "bash", callId = null)
        assertTrue(rule.matches(event, "/project"))
    }

    @Test
    fun `blank rule tool name never matches even blank event - P2 empty-name artifact`() {
        // 新增P2（2026-08-19）：历史遗留 toolName="" 规则与空名事件互相匹配是伪命中
        val rule = AutoApproveRule(toolName = "", directoryPattern = "/home/leo-tkp")
        assertFalse(rule.matches(testEvent(permission = ""), "/home/leo-tkp"))
        assertFalse(rule.matches(testEvent(permission = "bash"), "/home/leo-tkp"))
    }

    @Test
    fun `blank event permission never matches even wildcard rule`() {
        // 新增P2（2026-08-19）：空名事件（如评估端点产生的 ask）不应命中任何规则
        val rule = AutoApproveRule(toolName = "*")
        assertFalse(rule.matches(testEvent(permission = ""), "/project"))
    }
}
