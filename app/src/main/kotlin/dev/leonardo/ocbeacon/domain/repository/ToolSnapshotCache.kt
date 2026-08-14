package dev.leonardo.ocbeacon.domain.repository

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
    // #98（H-7）：LRU 有界——快照含整文件内容（可达 MB 级），导航取消/失败
    // 时 onCleared 不触发 → 无界版本永驻。插入序即导航序，超限淘汰最旧。
    private val snapshots = object : LinkedHashMap<String, Snapshot>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>): Boolean {
            return size > MAX_SNAPSHOTS
        }
    }

    fun put(partId: String, snapshot: Snapshot) {
        synchronized(snapshots) { snapshots[partId] = snapshot }
    }

    fun putAll(snapshots: Map<String, Snapshot>) {
        synchronized(this.snapshots) { this.snapshots.putAll(snapshots) }
    }

    fun get(partId: String): Snapshot? = synchronized(snapshots) { snapshots[partId] }

    fun getAll(partIds: List<String>): List<Snapshot> =
        synchronized(snapshots) { partIds.mapNotNull { snapshots[it] } }

    fun clear(partIds: List<String>) {
        synchronized(snapshots) { partIds.forEach { snapshots.remove(it) } }
    }

    fun clear() {
        synchronized(snapshots) { snapshots.clear() }
    }

    fun size(): Int = synchronized(snapshots) { snapshots.size }

    private companion object {
        /** #98（H-7）：条目上限（参照 DirectoryManager.dirCache 200 LRU 标杆）。 */
        const val MAX_SNAPSHOTS = 200
    }

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
