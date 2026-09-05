package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.data.repository.DshWorkspaceStore
import dev.leonardo.ocbeacon.domain.model.SseEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH workspace 事件处理器（#311 Task1/Task3、#330）——workspace/follow 合成帧
 * 映射的 [SseEvent.WorkspaceSnapshotChanged] / [SseEvent.WorkspaceArchivedChanged] /
 * [SseEvent.WorkspaceUpserted] / [SseEvent.WorkspaceRemoved] /
 * [SseEvent.WorkspaceOrderChanged] 写入 [DshWorkspaceStore]（状态机语义由 store
 * 承担；对齐 DshQueueHandler 形状）。
 */
@Singleton
class DshWorkspaceHandler @Inject constructor(
    private val store: DshWorkspaceStore,
) : SseEventHandler {

    override fun handle(event: SseEvent, serverId: String): Boolean = when (event) {
        is SseEvent.WorkspaceSnapshotChanged -> {
            store.applyBaseline(serverId, event.workspaces, event.archivedSessionIds)
            true
        }
        is SseEvent.WorkspaceArchivedChanged -> {
            store.applyArchived(serverId, event.archivedSessionIds)
            true
        }
        is SseEvent.WorkspaceUpserted -> {
            store.applyUpsert(serverId, event.workspace)
            true
        }
        is SseEvent.WorkspaceRemoved -> {
            store.applyRemove(serverId, event.workspaceId)
            true
        }
        is SseEvent.WorkspaceOrderChanged -> {
            store.applyOrder(serverId, event.workspaceIds)
            true
        }
        else -> false
    }
}
