package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DshWorkspaceStore 状态机测试（#311 Task1——workspace/follow baseline 整替换 +
 * archived 增量集合替换；对齐 DshQueueStoreTest 形状/纪律）。
 */
class DshWorkspaceStoreTest {

    private fun ws(id: String, vararg sessionIds: String) =
        Workspace(workspaceId = id, path = "/w/" + id, title = id, sessionIds = sessionIds.toList())

    @Test
    fun `applyBaseline is whole replacement per server`() {
        val store = DshWorkspaceStore()
        store.applyBaseline("srv-1", listOf(ws("ws-1", "s-1")), archivedSessionIds = listOf("s-9"))
        store.applyBaseline("srv-1", listOf(ws("ws-2")), archivedSessionIds = emptyList())
        val snapshot = store.snapshotFor("srv-1")
        assertEquals(listOf("ws-2"), snapshot.workspaces.map { it.workspaceId })
        assertTrue(snapshot.archivedSessionIds.isEmpty())
    }

    /** 集合替换语义（契约 ①-a）：archived 增量帧即新集合，非合并。 */
    @Test
    fun `applyArchived replaces archived set keeping workspaces`() {
        val store = DshWorkspaceStore()
        store.applyBaseline("srv-1", listOf(ws("ws-1", "s-1", "s-2")), archivedSessionIds = listOf("s-9"))
        store.applyArchived("srv-1", listOf("s-2", "s-9"))
        val snapshot = store.snapshotFor("srv-1")
        assertEquals(listOf("s-2", "s-9"), snapshot.archivedSessionIds)
        // workspaces 保持——增量帧不携带注册表
        assertEquals(listOf("ws-1"), snapshot.workspaces.map { it.workspaceId })
    }

    @Test
    fun `snapshotFor absent server is empty and servers are isolated`() {
        val store = DshWorkspaceStore()
        assertTrue(store.snapshotFor("srv-none").workspaces.isEmpty())
        assertTrue(store.snapshotFor("srv-none").archivedSessionIds.isEmpty())
        store.applyBaseline("srv-1", listOf(ws("ws-1")), archivedSessionIds = listOf("s-9"))
        assertTrue(store.snapshotFor("srv-2").workspaces.isEmpty())
        assertEquals(1, store.snapshotFor("srv-1").workspaces.size)
    }

    @Test
    fun `clearForServer releases state and clear empties all`() {
        val store = DshWorkspaceStore()
        store.applyBaseline("srv-1", listOf(ws("ws-1")), archivedSessionIds = listOf("s-9"))
        store.applyArchived("srv-1", listOf("s-1"))
        store.clearForServer("srv-1")
        assertTrue(store.snapshotFor("srv-1").archivedSessionIds.isEmpty())
        store.applyBaseline("srv-2", listOf(ws("ws-2")), archivedSessionIds = emptyList())
        store.clear()
        assertTrue(store.snapshots.value.isEmpty())
    }
}
