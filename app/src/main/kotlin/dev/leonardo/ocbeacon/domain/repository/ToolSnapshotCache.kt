package dev.leonardo.ocbeacon.domain.repository

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-level in-memory cache for tool snapshots (spec §5.6).
 *
 * Navigation arguments cannot carry large Part content (URL length limit +
 * Binder 1MB transaction limit). ChatViewModel caches snapshots keyed by tool
 * part ID before navigating; FileViewerViewModel reads them by toolPartIds.
 *
 * Lifecycle: write-on-navigate, clear-on-FileViewer-onCleared.
 * Process death → loss is acceptable (chat state may have changed).
 */
@Singleton
class ToolSnapshotCache @Inject constructor() {

    private val snapshots = mutableMapOf<String, Snapshot>()

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
        /** True if this snapshot has diff data (Edit-style). */
        val isDiff: Boolean get() = before != null && after != null
        /** True if this snapshot is a content view (Read/Write-style). */
        val isContent: Boolean get() = content != null
    }
}
