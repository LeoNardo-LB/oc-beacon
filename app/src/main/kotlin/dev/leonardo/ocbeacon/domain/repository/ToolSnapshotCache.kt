package dev.leonardo.ocbeacon.domain.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级内存工具快照缓存（spec §5.6）。
 *
 * 导航参数无法携带大段 Part 内容（URL 长度限制 +
 * Binder 1MB 事务限制）。ChatViewModel 在导航前以 tool part ID 为键
 * 缓存快照；FileViewerViewModel 通过 toolPartIds 读取。
 *
 * 生命周期：导航时写入，FileViewer onCleared 时清除。
 * 进程死亡 → 丢失是可接受的（聊天状态可能已变化）。
 */
@Singleton
class ToolSnapshotCache @Inject constructor() {

    // ConcurrentHashMap：ChatViewModel（主线程写入）与 FileViewerViewModel
    //（主线程清除）可能在不同生命周期交错访问，且未来可能引入后台线程，
    // 非线程安全 map 存在并发损坏风险。
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    fun put(partId: String, snapshot: Snapshot) {
        snapshots[partId] = snapshot
    }

    fun putAll(snapshots: Map<String, Snapshot>) {
        this.snapshots.putAll(snapshots)
    }

    fun get(partId: String): Snapshot? = snapshots[partId]

    fun getAll(partIds: List<String>): List<Snapshot> =
        partIds.mapNotNull { snapshots[it] }

    fun clear(partIds: List<String>) {
        partIds.forEach { snapshots.remove(it) }
    }

    fun clear() {
        snapshots.clear()
    }

    fun size(): Int = snapshots.size

    data class Snapshot(
        val filePath: String,
        val content: String?,
        val before: String?,
        val after: String?,
        val toolName: String  // "read" | "write" | "edit"
    ) {
        /** 当此快照包含 diff 数据（Edit 类型）时为 true。 */
        val isDiff: Boolean get() = before != null && after != null
        /** 当此快照为内容视图（Read/Write 类型）时为 true。 */
        val isContent: Boolean get() = content != null
    }
}
