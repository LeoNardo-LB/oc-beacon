package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseSyntheticTaskTest {

    @Test
    fun `parses new task format`() {
        val text = """
            <task id="ses_abc" state="completed">
            <summary>Background task completed: 总结文档</summary>
            <task_result>文档列表：a.md b.md</task_result>
            </task>
        """.trimIndent()
        val info = parseSyntheticTask(text)
        assertEquals("ses_abc", info?.sessionId)
        assertEquals("completed", info?.state)
        assertEquals("Background task completed: 总结文档", info?.summary)
        assertEquals("文档列表：a.md b.md", info?.output)
    }

    @Test
    fun `parses new task format with error`() {
        val text = """
            <task id="ses_err" state="error">
            <summary>Background task failed: 失败任务</summary>
            <task_error>错误信息</task_error>
            </task>
        """.trimIndent()
        val info = parseSyntheticTask(text)
        assertEquals("error", info?.state)
        assertEquals("错误信息", info?.output)
    }

    @Test
    fun `parses subagent format from running server`() {
        // 运行中的旧版服务器实际格式（2026-08-12 实测）：
        // 修复前 parseSyntheticTask 只认 <task> → 此格式返回 null → 降级原始 XML 文本
        val text = """<subagent id="ses_00c6a275fffeU4010j6IMLiqUF" state="completed" description="简单算术验证后台任务">
4
</subagent>"""
        val info = parseSyntheticTask(text)
        assertEquals("ses_00c6a275fffeU4010j6IMLiqUF", info?.sessionId)
        assertEquals("completed", info?.state)
        assertEquals("简单算术验证后台任务", info?.summary)
        assertEquals("4", info?.output)
    }

    @Test
    fun `parses subagent format with multiline output`() {
        val text = """<subagent id="ses_x" state="completed" description="多行输出任务">
第一行
第二行
</subagent>"""
        val info = parseSyntheticTask(text)
        assertEquals("第一行\n第二行", info?.output)
    }

    @Test
    fun `parses subagent error state`() {
        val text = """<subagent id="ses_y" state="error" description="失败任务">
任务执行失败：网络超时
</subagent>"""
        val info = parseSyntheticTask(text)
        assertEquals("error", info?.state)
        assertEquals("失败任务", info?.summary)
        assertEquals("任务执行失败：网络超时", info?.output)
    }

    @Test
    fun `returns null for non task text`() {
        assertNull(parseSyntheticTask("普通文本没有结构化标记"))
        assertNull(parseSyntheticTask(""))
    }

    @Test
    fun `returns null when state missing`() {
        val text = """<subagent id="ses_z" description="无状态">正文</subagent>"""
        assertNull(parseSyntheticTask(text))
    }
}
