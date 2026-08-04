package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import dev.leonardo.ocbeacon.ui.screens.sessions.components.buildTreeNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeNodeTest {

    private fun makeSession(id: String, directory: String) = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 1000L, updated = 1000L),
    )

    @Test
    fun `empty sessions returns empty list`() {
        val result = buildTreeNodes(emptyList(), emptySet(), null)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single directory with sessions - collapsed`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        val result = buildTreeNodes(sessions, emptySet(), "/home/user")

        // 应有 /home、/home/user、/home/user/project-a 的目录节点
        // 未展开任何节点，因此只有根层级
        assertTrue(result.isNotEmpty())
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertTrue(dirs.isNotEmpty())

        // 没有会话节点，因为未展开任何节点
        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertTrue(sessNodes.isEmpty())
    }

    @Test
    fun `single directory with sessions - expanded`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        // 展开所有路径
        val expanded = setOf("/home", "/home/user", "/home/user/project-a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(2, sessNodes.size)
        assertEquals("s1", sessNodes[0].id)
        assertEquals("s2", sessNodes[1].id)
    }

    @Test
    fun `partial expand shows only expanded children`() {
        val sessions = listOf(
            makeSession("s1", "/a"),
            makeSession("s2", "/a/b"),
        )

        // 只展开 /a，不展开 /a/b
        val expanded = setOf("/a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(1, sessNodes.size) // 只有 s1（在 /a 中），s2 因 /a/b 未展开而不可见
        assertEquals("s1", sessNodes[0].id)
    }

    // ============ baseDirectory==null 按完整目录路径分组（不再项目感知聚合） ============

    @Test
    fun `distinct directories produce one directory node each`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/proj-a"),
            makeSession("s2", "/home/user/proj-b"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertEquals(2, dirs.size)
        // 目录节点 path 为完整目录路径
        assertTrue(dirs.map { it.path }.containsAll(listOf("/home/user/proj-a", "/home/user/proj-b")))
    }

    @Test
    fun `directory displayName uses basename not full path`() {
        val sessions = listOf(makeSession("s1", "/home/user/proj-a"))

        val result = buildTreeNodes(sessions, emptySet(), null)

        val dir = result.filterIsInstance<TreeNode.Directory>().single()
        assertEquals("proj-a", dir.displayName)
        assertEquals("/home/user/proj-a", dir.path)
    }

    @Test
    fun `sessions with empty directory stay at root`() {
        val sessions = listOf(
            makeSession("s1", ""),
            makeSession("s2", ""),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        assertTrue(result.filterIsInstance<TreeNode.Directory>().isEmpty())
        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(2, sessNodes.size)
    }

    @Test
    fun `windows backslash paths are normalized into one group`() {
        val sessions = listOf(
            makeSession("s1", "D:\\Develop\\proj-a"),
            makeSession("s2", "D:/Develop/proj-a"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        // 反斜杠与正斜杠归一化后应归入同一目录组
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertEquals(1, dirs.size)
        assertEquals("D:/Develop/proj-a", dirs[0].path)
        assertEquals("proj-a", dirs[0].displayName)
    }

    @Test
    fun `same basename different parents produce distinct nodes`() {
        val sessions = listOf(
            makeSession("s1", "/root-a/app"),
            makeSession("s2", "/root-b/app"),
        )

        val result = buildTreeNodes(sessions, emptySet(), null)

        // 即使 basename 相同（app），不同父目录也应是独立分组
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertEquals(2, dirs.size)
        assertTrue(dirs.map { it.path }.containsAll(listOf("/root-a/app", "/root-b/app")))
    }

    @Test
    fun `root path session stays at root not grouped`() {
        val sessions = listOf(makeSession("s1", "/"))

        // "/" 规范化后为空 → 根会话（不产生 Directory 节点）
        val result = buildTreeNodes(sessions, emptySet(), null)

        assertTrue(result.filterIsInstance<TreeNode.Directory>().isEmpty())
        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(1, sessNodes.size)
        assertEquals("s1", sessNodes[0].id)
    }
}
