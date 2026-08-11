package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SyntheticNotificationCard 的 <task> 结构化文本解析测试。
 *
 * 服务器（opencode task.ts renderOutput）在后台 subagent 完成时向主会话
 * 注入 synthetic 消息，text 为：
 *   <task id="ses_xxx" state="completed|error">
 *   <summary>Background task completed: <描述></summary>
 *   <task_result|task_error>…输出…</task_result|task_error>
 *   </task>
 * 客户端解析出 sessionId（子会话跳转引用）、state（完成/失败色彩）、
 * summary（标题描述）、output（展开内容）。
 */
class SyntheticTaskParserTest {

    @Test
    fun `解析完整的 completed 任务文本`() {
        val text = """
            <task id="ses_abc123" state="completed">
            <summary>Background task completed: 扫描项目结构</summary>
            <task_result>
            Done! Found 3 modules.
            </task_result>
            </task>
        """.trimIndent()

        val info = parseSyntheticTask(text)

        assertEquals("ses_abc123", info?.sessionId)
        assertEquals("completed", info?.state)
        assertEquals("Background task completed: 扫描项目结构", info?.summary)
        assertEquals("Done! Found 3 modules.", info?.output)
    }

    @Test
    fun `解析 error 任务使用 task_error 标签`() {
        val text = """
            <task id="ses_xyz" state="error">
            <summary>Background task failed: 执行脚本</summary>
            <task_error>
            Error: permission denied
            </task_error>
            </task>
        """.trimIndent()

        val info = parseSyntheticTask(text)

        assertEquals("ses_xyz", info?.sessionId)
        assertEquals("error", info?.state)
        assertEquals("Background task failed: 执行脚本", info?.summary)
        assertEquals("Error: permission denied", info?.output)
    }

    @Test
    fun `无 id 时 sessionId 为 null（不崩溃）`() {
        val text = """
            <task state="completed">
            <summary>Background task completed: test</summary>
            <task_result>ok</task_result>
            </task>
        """.trimIndent()

        val info = parseSyntheticTask(text)

        assertEquals("completed", info?.state)
        assertNull(info?.sessionId)
        assertEquals("ok", info?.output)
    }

    @Test
    fun `非 task 格式返回 null（fallback 到纯文本）`() {
        assertNull(parseSyntheticTask("普通文本消息，不是 task 格式"))
        assertNull(parseSyntheticTask(""))
        assertNull(parseSyntheticTask("<task>没有属性</task>"))
    }

    @Test
    fun `输出为空时 output 为 null（无展开内容）`() {
        val text = """
            <task id="ses_1" state="completed">
            <summary>Background task completed: empty output</summary>
            <task_result></task_result>
            </task>
        """.trimIndent()

        val info = parseSyntheticTask(text)

        assertEquals("ses_1", info?.sessionId)
        assertEquals("completed", info?.state)
        assertNull(info?.output)
    }
}
