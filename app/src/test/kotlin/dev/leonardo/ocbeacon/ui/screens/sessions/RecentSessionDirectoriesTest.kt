package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.screens.sessions.components.recentSessionDirectories
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NewSessionQuickDialog 的 recentSessionDirectories 测试。
 * 回归：V2 服务器存在 location.directory 为空的会话（实测 ses_005890631ffe...）——
 * 空目录会产生"空目录"条目（2026-08-13 用户反馈）。
 */
class RecentSessionDirectoriesTest {

    private fun session(id: String, directory: String, updated: Long = 1000L): Session = Session(
        id = id,
        directory = directory,
        title = "t-$id",
        time = Session.Time(created = updated, updated = updated),
    )

    @Test
    fun `empty directory sessions are filtered out`() {
        val sessions = listOf(
            session("s1", "/home/leo-tkp/proj-a"),
            session("s2", ""),               // 空 directory
            session("s3", "/home/leo-tkp/proj-a"),
            session("s4", "  "),             // 空白字符串
        )
        val result = recentSessionDirectories(sessions, limit = 20)
        // 只有 proj-a 一组（2 个会话），空/空白目录不产生条目
        assertEquals(1, result.size)
        assertEquals("/home/leo-tkp/proj-a", result[0].directory)
        assertEquals("proj-a", result[0].name)
        assertEquals(2, result[0].count)
    }

    @Test
    fun `root directory sessions are filtered out`() {
        // 回归：V2 服务器实测会话 ses_005890631ffe... 的 location.directory 为 "/"（根目录）——
        // trimEnd('/') 后为空 → 旧代码分组 key="" → "空目录"条目（用户反馈）
        val sessions = listOf(
            session("s1", "/home/leo-tkp/proj-a"),
            session("s2", "/"),             // 根目录（V2 服务器数据）
        )
        val result = recentSessionDirectories(sessions, limit = 20)
        assertEquals(1, result.size)
        assertEquals("proj-a", result[0].name)
    }

    @Test
    fun `normal directories grouped by trimmed path`() {
        val sessions = listOf(
            session("s1", "/a/b/"),
            session("s2", "/a/b"),
        )
        val result = recentSessionDirectories(sessions, limit = 20)
        assertEquals(1, result.size)
        // directory 保留首条原始值（点击新建会话用），分组 key 是 trim 后的 /a/b
        assertEquals("/a/b/", result[0].directory)
        assertEquals(2, result[0].count)
    }

    @Test
    fun `respects limit`() {
        val sessions = (1..5).map { session("s$it", "/dir/$it") }
        val result = recentSessionDirectories(sessions, limit = 2)
        assertEquals(2, result.size)
    }

    @Test
    fun `lastUsed ties break deterministically by directory`() {
        // 回归（DSH E2E 发现，人类同样可命中）：lastUsed 并列时旧实现仅靠 stable sort
        // 保留输入序 —— live 流以不同顺序重发同一批会话 → 对话框行序漂移 → 点选落位错行。
        // 要求：lastUsed 并列时按 directory 字典序消解，输入顺序不得影响输出行序。
        val forward = listOf(
            session("s1", "/home/leo-tkp/alpha", updated = 5000L),
            session("s2", "/home/leo-tkp/beta", updated = 5000L),
            session("s3", "/home/leo-tkp/gamma", updated = 5000L),
        )
        val fromForward = recentSessionDirectories(forward, limit = 20).map { it.directory }
        val fromReversed = recentSessionDirectories(forward.asReversed(), limit = 20).map { it.directory }
        assertEquals(
            listOf("/home/leo-tkp/alpha", "/home/leo-tkp/beta", "/home/leo-tkp/gamma"),
            fromReversed,
        )
        assertEquals(fromForward, fromReversed)
    }

    @Test
    fun `lastUsed stays primary key before directory tiebreak`() {
        val sessions = listOf(
            session("s1", "/home/a/aaa", updated = 100L),
            session("s2", "/home/z/zzz", updated = 900L),
        )
        val result = recentSessionDirectories(sessions, limit = 20).map { it.directory }
        assertEquals(listOf("/home/z/zzz", "/home/a/aaa"), result)
    }
}
