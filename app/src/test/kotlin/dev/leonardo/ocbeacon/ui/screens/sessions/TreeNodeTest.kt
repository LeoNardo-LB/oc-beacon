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

        // Should have directory nodes for /home, /home/user, /home/user/project-a
        // None expanded, so only root-level
        assertTrue(result.isNotEmpty())
        val dirs = result.filterIsInstance<TreeNode.Directory>()
        assertTrue(dirs.isNotEmpty())

        // No session nodes because nothing is expanded
        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertTrue(sessNodes.isEmpty())
    }

    @Test
    fun `single directory with sessions - expanded`() {
        val sessions = listOf(
            makeSession("s1", "/home/user/project-a"),
            makeSession("s2", "/home/user/project-a"),
        )

        // Expand all paths
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

        // Only expand /a, not /a/b
        val expanded = setOf("/a")
        val result = buildTreeNodes(sessions, expanded, null)

        val sessNodes = result.filterIsInstance<TreeNode.Session>()
        assertEquals(1, sessNodes.size) // only s1 (in /a), s2 not visible because /a/b not expanded
        assertEquals("s1", sessNodes[0].id)
    }
}
