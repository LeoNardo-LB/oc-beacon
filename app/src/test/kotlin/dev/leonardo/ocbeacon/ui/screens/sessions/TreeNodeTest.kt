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
}
