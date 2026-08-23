package dev.leonardo.ocbeacon.ui.screens.sessions.util

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupingTest {

    @Test
    fun groupsByProjectIdBeforeDirectoryFallback() {
        val projects = listOf(
            Project(id = "project", worktree = "/repo", name = "Repository"),
        )
        val item = item("session", "/elsewhere", projectId = "project")

        val group = buildProjectSessionGroups(listOf(item), projects, null).single()

        assertEquals("project", group.projectId)
        assertEquals("Repository", group.projectName)
        assertEquals("/repo", group.directory)
    }

    @Test
    fun choosesLongestMatchingWorktreePrefix() {
        val projects = listOf(
            Project(id = "root", worktree = "/repo", name = "Root"),
            Project(id = "nested", worktree = "/repo/apps/mobile", name = "Mobile"),
        )
        // 会话位于嵌套 worktree 下但不携带 projectId —— 必须
        // 降级到最长匹配前缀。
        val item = item("session", "/repo/apps/mobile/src")

        val group = buildProjectSessionGroups(listOf(item), projects, null).single()

        assertEquals("nested", group.projectId)
        assertEquals("Mobile", group.projectName)
        assertEquals("/repo/apps/mobile", group.directory)
    }

    @Test
    fun unknownDirectoriesBecomeIndependentGroups() {
        val groups = buildProjectSessionGroups(
            listOf(
                item("older", "/one", updated = 1),
                item("newer", "/two", updated = 2),
            ),
            emptyList(),
            null,
        )

        assertEquals(2, groups.size)
        // 按最近活动排序：/two（updated=2）在 /one（updated=1）之前。
        assertEquals(listOf("/two", "/one"), groups.map { it.directory })
    }

    @Test
    fun groupsOrderedByLatestActivityThenName() {
        val groups = buildProjectSessionGroups(
            listOf(
                item("a", "/alpha", updated = 5),
                item("b", "/beta", updated = 9),
                item("c", "/gamma", updated = 9), // 与 /beta 活动相同 -> 按名称决胜
            ),
            emptyList(),
            null,
        )

        assertEquals(listOf("/beta", "/gamma", "/alpha"), groups.map { it.directory })
    }

    @Test
    fun sessionsWithinGroupSortedByUpdatedDescending() {
        val groups = buildProjectSessionGroups(
            listOf(
                item("old", "/repo", updated = 1),
                item("new", "/repo", updated = 10),
                item("mid", "/repo", updated = 5),
            ),
            emptyList(),
            null,
        )

        assertEquals(listOf("new", "mid", "old"), groups.single().sessions.map { it.session.id })
    }

    @Test
    fun sameProjectSessionsAggregateAcrossWorktrees() {
        val projects = listOf(Project(id = "p", worktree = "/repo", name = "Repo"))
        val groups = buildProjectSessionGroups(
            listOf(
                item("s1", "/repo", projectId = "p", updated = 2),
                item("s2", "/repo/feature", projectId = "p", updated = 1),
            ),
            projects,
            null,
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("s1", "s2"), groups.single().sessions.map { it.session.id })
    }

    @Test
    fun emptySessionsProducesEmptyGroups() {
        val groups = buildProjectSessionGroups(
            emptyList(),
            listOf(Project(id = "p", worktree = "/r")),
            null,
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun homeDirProducesTildeLabels() {
        val projects = listOf(Project(id = "p", worktree = "/home/user/repo", name = "Repo"))
        val item = item("s", "/home/user/repo/sub")

        val group = buildProjectSessionGroups(listOf(item), projects, "/home/user").single()

        assertEquals("~/repo/sub", group.sessionDirLabels["s"])
    }

    private fun item(
        id: String,
        directory: String,
        projectId: String = "",
        updated: Long = 1,
    ) = SessionItem(
        Session(
            id = id,
            projectId = projectId,
            directory = directory,
            time = Session.Time(created = 1, updated = updated),
        )
    )
}
