package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.Workspace
import dev.leonardo.ocbeacon.domain.model.WorkspaceSnapshot
import dev.leonardo.ocbeacon.ui.screens.sessions.components.WorkspaceDialogEntry
import dev.leonardo.ocbeacon.ui.screens.sessions.components.findReusableBlankSession
import dev.leonardo.ocbeacon.ui.screens.sessions.components.projectDialogEntries
import dev.leonardo.ocbeacon.ui.screens.sessions.components.workspaceDialogEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #311 Task3 多 workspace 真建模——新建会话对话框纯函数（红→绿）：
 * - 分组归属：sessionIds 显式数组（契约 ①-d）——在组未归档计数 + stray 兜底（cwd/recency）；
 * - 快照→对话框状态映射：V012 快照模式 + V011 listProjects 回退；
 * - 连接语义：web mod29:46-58 复用判定四条件（blank ∧ cwd===path ∧ 在组 ∧ 未归档）。
 */
class WorkspaceDialogModelsTest {

    private fun session(
        id: String,
        directory: String,
        updated: Long = 1000L,
        blank: Boolean = false,
    ): Session = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 0L, updated = updated),
        blank = blank,
    )

    private fun ws(id: String, path: String, title: String, vararg sessionIds: String) =
        Workspace(workspaceId = id, path = path, title = title, sessionIds = sessionIds.toList())

    // ============ workspaceDialogEntries：分组归属 + stray 兜底 ============

    @Test
    fun `workspace entry carries title and in-group unarchived live-session count`() {
        val snapshot = WorkspaceSnapshot(
            workspaces = listOf(
                ws("ws-1", "/w/beacon", "Beacon", "s-1", "s-2", "s-9", "s-ghost"),
                ws("ws-2", "/w/ops", "Ops"),
            ),
            archivedSessionIds = listOf("s-9"),
        )
        val sessions = listOf(
            session("s-1", "/w/beacon", updated = 2000L),
            session("s-2", "/w/beacon", updated = 1000L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)

        val beacon = entries.first { it.workspaceId == "ws-1" }
        // 名字键=title（契约 ①-d：不取 path 尾段）
        assertEquals("Beacon", beacon.title)
        assertEquals("/w/beacon", beacon.path)
        // s-ghost 不在会话列表（blank 滤除面）不计；s-9 已归档不计
        assertEquals(2, beacon.sessionCount)
        assertEquals(2000L, beacon.lastUsed)

        // 空注册 workspace 保留（连接起点），计数 0
        val ops = entries.first { it.workspaceId == "ws-2" }
        assertEquals(0, ops.sessionCount)
    }

    @Test
    fun `stray sessions fall back to directory entries grouped by cwd with recency`() {
        val snapshot = WorkspaceSnapshot(
            workspaces = listOf(ws("ws-1", "/a", "A", "s-1")),
        )
        val sessions = listOf(
            session("s-1", "/a", updated = 100L),
            session("s-2", "/b", updated = 5000L),
            session("s-3", "/b", updated = 3000L),
            session("s-4", "/c", updated = 400L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)

        val strayB = entries.first { it.path == "/b" }
        assertNull(strayB.workspaceId)
        assertEquals("b", strayB.title)
        assertEquals(2, strayB.sessionCount)
        assertEquals(5000L, strayB.lastUsed)

        val strayC = entries.first { it.path == "/c" }
        assertEquals(1, strayC.sessionCount)
    }

    @Test
    fun `stray directory shadowed by a workspace path is deduped in favor of the workspace`() {
        val snapshot = WorkspaceSnapshot(
            workspaces = listOf(ws("ws-1", "/shared", "Shared", "s-1")),
        )
        // s-2 cwd 与 workspace.path 相同但不在 sessionIds（stray）——目录条目让位
        val sessions = listOf(
            session("s-1", "/shared", updated = 100L),
            session("s-2", "/shared", updated = 9000L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)

        assertEquals(1, entries.size)
        assertEquals("ws-1", entries[0].workspaceId)
    }

    @Test
    fun `archived strays are excluded from directory fallback`() {
        val snapshot = WorkspaceSnapshot(
            workspaces = listOf(ws("ws-1", "/a", "A")),
            archivedSessionIds = listOf("s-2"),
        )
        val sessions = listOf(
            session("s-2", "/gone", updated = 9000L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)
        // 只剩空 workspace 条目；/gone 不出现
        assertEquals(listOf("ws-1"), entries.map { it.workspaceId })
    }

    @Test
    fun `blank and root directories are filtered from stray entries`() {
        val snapshot = WorkspaceSnapshot(workspaces = listOf(ws("ws-1", "/a", "A")))
        val sessions = listOf(
            session("s-1", "", updated = 9000L),
            session("s-2", "/", updated = 8000L),
            session("s-3", "/b", updated = 100L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)
        // recency 序：stray /b(100) > 空 workspace /a(0)
        assertEquals(listOf("/b", "/a"), entries.map { it.path })
    }

    @Test
    fun `entries sort by recency desc with title then path tie-break`() {
        val snapshot = WorkspaceSnapshot(
            workspaces = listOf(
                ws("ws-1", "/a", "Zeta"),
                ws("ws-2", "/b", "Alpha"),
            ),
        )
        val sessions = listOf(
            session("s-1", "/x", updated = 100L), // stray /x lastUsed=100
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)
        // 两个 workspace lastUsed 同为 0 → title 字典序 Alpha 前 Zeta 后；stray 100 > 0 最前
        assertEquals(listOf("/x", "/b", "/a"), entries.map { it.path })
    }

    @Test
    fun `limit applies across merged workspace and stray entries`() {
        val snapshot = WorkspaceSnapshot(
            workspaces = listOf(
                ws("ws-1", "/a", "A", "s-1"),
                ws("ws-2", "/b", "B"),
            ),
        )
        val sessions = listOf(
            session("s-1", "/a", updated = 100L),
            session("s-9", "/stray", updated = 9000L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 2)
        // recency 序：/stray(9000) > /a(100) > /b(0)；limit=2 截断
        assertEquals(listOf("/stray", "/a"), entries.map { it.path })
    }

    @Test
    fun `empty snapshot yields empty entries as fallback signal`() {
        val entries = workspaceDialogEntries(
            WorkspaceSnapshot(),
            listOf(session("s-1", "/a", updated = 100L)),
            limit = 20,
        )
        assertEquals(emptyList<WorkspaceDialogEntry>(), entries)
    }

    @Test
    fun `backslash cwd normalizes to posix workspace path for stray grouping`() {
        val snapshot = WorkspaceSnapshot(workspaces = listOf(ws("ws-1", "/a", "A")))
        val sessions = listOf(
            session("s-1", "/win\\sub", updated = 100L),
            session("s-2", "/win/sub/", updated = 300L),
        )
        val entries = workspaceDialogEntries(snapshot, sessions, limit = 20)
        // \\ 与 / 归一为同一目录键，合并计数
        val stray = entries.first { it.workspaceId == null }
        assertEquals("/win/sub", stray.path)
        assertEquals(2, stray.sessionCount)
    }

    // ============ projectDialogEntries：V011 listProjects 回退 ============

    @Test
    fun `project fallback entries count live sessions by worktree cwd`() {
        val projects = listOf(
            Project(id = "p-1", worktree = "/a", name = "Alpha"),
            Project(id = "p-2", worktree = "/b"),
        )
        val sessions = listOf(
            session("s-1", "/a", updated = 9000L),
            session("s-2", "/a/", updated = 100L),
            session("s-3", "/b", updated = 500L),
        )
        val entries = projectDialogEntries(projects, sessions, limit = 20)

        val alpha = entries.first { it.path == "/a" }
        assertEquals("Alpha", alpha.title)
        assertNull(alpha.workspaceId)
        assertEquals(2, alpha.sessionCount)
        assertEquals(9000L, alpha.lastUsed)

        // name 缺席回退 worktree 尾段（Project.displayName 语义）
        val b = entries.first { it.path == "/b" }
        assertEquals("b", b.title)
        assertEquals(1, b.sessionCount)
    }

    @Test
    fun `project fallback sorts by recency desc and respects limit`() {
        val projects = listOf(
            Project(id = "p-1", worktree = "/cold", name = "Cold"),
            Project(id = "p-2", worktree = "/hot", name = "Hot"),
        )
        val sessions = listOf(session("s-1", "/hot", updated = 8000L))
        val entries = projectDialogEntries(projects, sessions, limit = 1)
        assertEquals(listOf("/hot"), entries.map { it.path })
    }

    // ============ findReusableBlankSession：web 连接语义四条件 ============

    private val workspace = ws("ws-1", "/w/beacon", "Beacon", "s-1", "s-2")

    @Test
    fun `reuse requires all four web conditions`() {
        val candidates = listOf(
            session("s-1", "/w/beacon", blank = false),  // 破①非 blank
            session("s-2", "/other", blank = true),      // 破②cwd 不符
            session("s-x", "/w/beacon", blank = true),   // 破③不在组
            session("s-2", "/w/beacon", blank = true),   // 破④已归档
            session("s-1", "/w/beacon", blank = true),   // 四条件全过 → 命中
        )
        val reuse = findReusableBlankSession(
            workspace,
            candidates,
            archivedIds = setOf("s-2"),
        )
        assertEquals("s-1", reuse?.id)
    }

    @Test
    fun `reuse returns first match in candidate order and null when none`() {
        val a = session("s-1", "/w/beacon", updated = 100L, blank = true)
        val b = session("s-2", "/w/beacon", updated = 9000L, blank = true)
        // 命中序 = 候选序（web 按 sessions.ids 序首个命中——不按 recency 挑选）
        assertEquals(
            "s-2",
            findReusableBlankSession(workspace, listOf(b, a), archivedIds = emptySet())?.id,
        )
        assertNull(
            findReusableBlankSession(workspace, listOf(session("s-1", "/w/beacon")), emptySet()),
        )
    }

    @Test
    fun `reuse matches cwd with separator and trailing-slash tolerance`() {
        val candidate = session("s-2", "/w/beacon/", blank = true)
        assertEquals(
            "s-2",
            findReusableBlankSession(workspace, listOf(candidate), emptySet())?.id,
        )
        val windows = session("s-2", "\\w\\beacon\\", blank = true)
        assertEquals(
            "s-2",
            findReusableBlankSession(workspace, listOf(windows), emptySet())?.id,
        )
    }
}
