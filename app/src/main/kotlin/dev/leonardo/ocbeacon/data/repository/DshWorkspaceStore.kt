package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Workspace
import dev.leonardo.ocbeacon.domain.model.WorkspaceSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH workspace 状态容器（单一真相源，对齐官方 workspaces.list store；#311 Task1）。
 *
 * 语义（workspace-controller WorkspaceFollower）：
 * - baseline（每代重连恰一帧）：workspaces + archivedSessionIds 整替换（按 serverId 键）；
 * - 增量 {type:'archived'}：只替换归档集合（**集合替换式**——帧即新集合，非合并），
 *   workspaces 保持；
 * - 增量 {type:'upsert'}（#311 Task3）：注册表行按 workspaceId 原位替换（title/
 *   sessionIds 实时消费面）；remove/order 仍不消费（无消费面，留痕）。
 *
 * 瞬态数据：不入 Room/历史、不重放（防替换/历史折叠语义与 DshQueueStore 同款）。
 */
@Singleton
class DshWorkspaceStore @Inject constructor() {

    private val _snapshots = MutableStateFlow<Map<String, WorkspaceSnapshot>>(emptyMap())

    /** serverId → workspace 快照（baseline last-wins）。 */
    val snapshots: StateFlow<Map<String, WorkspaceSnapshot>> = _snapshots.asStateFlow()

    /** 指定服务器的快照；未连接/未 baseline → 空快照。 */
    fun snapshotFor(serverId: String): WorkspaceSnapshot =
        _snapshots.value[serverId] ?: WorkspaceSnapshot()

    /** baseline 整替换（workspaces + archivedSessionIds 同帧刷新）。 */
    fun applyBaseline(serverId: String, workspaces: List<Workspace>, archivedSessionIds: List<String>) {
        _snapshots.update { all ->
            all + (serverId to WorkspaceSnapshot(workspaces, archivedSessionIds))
        }
    }

    /** 归档集合替换（workspaces 保持——增量帧不携带注册表）。 */
    fun applyArchived(serverId: String, archivedSessionIds: List<String>) {
        _snapshots.update { all ->
            val current = all[serverId] ?: WorkspaceSnapshot()
            all + (serverId to current.copy(archivedSessionIds = archivedSessionIds))
        }
    }

    /**
     * 注册表行替换（#311 Task3；wire {type:'upsert', workspace:WorkspaceView} 整行）：
     * 按 workspaceId 原位替换（序稳定——服务器 order 增量未消费，保持 baseline 序），
     * 未知 id 追加尾部；archived 集合保持（upsert 帧不携带）。
     */
    fun applyUpsert(serverId: String, workspace: Workspace) {
        _snapshots.update { all ->
            val current = all[serverId] ?: WorkspaceSnapshot()
            val existing = current.workspaces.indexOfFirst { it.workspaceId == workspace.workspaceId }
            val merged = if (existing >= 0) {
                current.workspaces.toMutableList().also { it[existing] = workspace }
            } else {
                current.workspaces + workspace
            }
            all + (serverId to current.copy(workspaces = merged))
        }
    }

    /** 释放单服务器状态（断连/移除级联）。 */
    fun clearForServer(serverId: String) {
        _snapshots.update { all -> all - serverId }
    }

    fun clear() {
        _snapshots.value = emptyMap()
    }
}
