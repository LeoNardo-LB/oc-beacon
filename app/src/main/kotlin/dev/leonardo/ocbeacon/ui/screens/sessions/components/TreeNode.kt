package dev.leonardo.ocbeacon.ui.screens.sessions.components

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import dev.leonardo.ocbeacon.ui.screens.sessions.util.projectForSession

/**
 * Sealed interface for flat session list nodes.
 * Two levels: Directory (expandable group) and Session (leaf item).
 */
sealed interface TreeNode {
    val id: String

    data class Directory(
        override val id: String,
        val path: String,
        val displayName: String,
        val sessionCount: Int,
        val activeSessionCount: Int,
        val isExpanded: Boolean,
    ) : TreeNode

    data class Session(
        override val id: String,
        val session: SessionItem,
    ) : TreeNode
}

/**
 * Build a flat 2-level node list from sessions.
 *
 * When baseDirectory is set:
 *   - Sessions are grouped by their first path segment relative to baseDirectory
 *   - Sessions directly in baseDirectory appear at the top (ungrouped)
 *   - Each group is an expandable Directory node
 *   - Groups are ordered alphabetically by segment (stable browsing order)
 *
 * When baseDirectory is null:
 *   - Sessions are grouped project-aware: sessions whose [Session.projectId]
 *     (or worktree prefix) maps to the same [Project] aggregate into one group.
 *     Unmatched sessions form per-directory groups.
 *   - Groups are ordered by latest activity (descending), then by display name.
 *
 * @param sessions Filtered sessions (already scoped to server, not archived, etc.)
 * @param expandedDirs Set of directory paths currently expanded
 * @param baseDirectory The selected base directory path (normalized, e.g. "D:/Develop"), or null
 * @param statuses Session status map
 * @param projects Known projects used for project-aware grouping when baseDirectory is null
 * @param sessionCategories Resolved session id → category map for display
 */
fun buildTreeNodes(
    sessions: List<Session>,
    expandedDirs: Set<String>,
    baseDirectory: String?,
    statuses: Map<String, SessionStatus> = emptyMap(),
    draftSessionIds: Set<String> = emptySet(),
    projects: List<Project> = emptyList(),
    sessionCategories: Map<String, SessionCategory> = emptyMap(),
): List<TreeNode> {
    val result = mutableListOf<TreeNode>()
    val rootSessions = mutableListOf<Session>()
    val normalizedBase = baseDirectory?.replace('\\', '/')?.trimEnd('/')

    data class GroupBucket(
        val id: String,
        val path: String,
        val displayName: String,
        val sessions: MutableList<Session> = mutableListOf(),
    )
    val groupsByKey = linkedMapOf<String, GroupBucket>()
    val groupOrder = mutableListOf<GroupBucket>()

    fun bucketFor(key: String, id: String, path: String, displayName: String): GroupBucket =
        groupsByKey.getOrPut(key) {
            GroupBucket(id = id, path = path, displayName = displayName).also(groupOrder::add)
        }

    for (session in sessions) {
        val dir = session.directory.replace('\\', '/').trimEnd('/')
        if (dir.isEmpty()) {
            rootSessions.add(session)
            continue
        }

        if (normalizedBase != null) {
            // baseDirectory mode: group by first path segment relative to base.
            if (!dir.startsWith(normalizedBase)) continue
            val relative = dir.removePrefix(normalizedBase).removePrefix("/")
            if (relative.isEmpty()) {
                rootSessions.add(session)
            } else {
                val firstSegment = relative.substringBefore('/')
                val fullPath = "$normalizedBase/$firstSegment"
                bucketFor(
                    key = firstSegment,
                    id = firstSegment,
                    path = fullPath,
                    displayName = fullPath,
                ).sessions.add(session)
            }
        } else {
            // Project-aware grouping: aggregate sessions by owning project.
            val project = projectForSession(session, projects)
            val groupPath = (project?.worktree?.takeIf { it.isNotBlank() }
                ?: project?.path?.takeIf { it.isNotBlank() }
                ?: dir).replace('\\', '/').trimEnd('/').ifEmpty { dir }
            val displayName = project?.displayName ?: dir
            val key = project?.id?.takeIf { it.isNotBlank() } ?: "dir:$dir"
            bucketFor(
                key = key,
                id = groupPath,
                path = groupPath,
                displayName = displayName,
            ).sessions.add(session)
        }
    }

    // Order groups: baseDirectory -> alphabetical by id (stable); otherwise -> latest activity then name.
    val orderedGroups = if (normalizedBase != null) {
        groupOrder.sortedBy { it.id }
    } else {
        groupOrder.sortedWith(
            compareByDescending<GroupBucket> { it.sessions.maxOfOrNull { s -> s.time.updated } ?: 0 }
                .thenBy { it.displayName.lowercase() }
        )
    }

    for (bucket in orderedGroups) {
        val isExpanded = bucket.path in expandedDirs
        val activeCount = bucket.sessions.count { statuses[it.id] is SessionStatus.Busy }
        result.add(TreeNode.Directory(
            id = bucket.id,
            path = bucket.path,
            displayName = bucket.displayName,
            sessionCount = bucket.sessions.size,
            activeSessionCount = activeCount,
            isExpanded = isExpanded,
        ))
        if (isExpanded) {
            for (session in bucket.sessions.sortedByDescending { it.time.updated }) {
                result.add(TreeNode.Session(
                    id = session.id,
                    session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle, hasDraft = session.id in draftSessionIds, category = sessionCategories[session.id]),
                ))
            }
        }
    }

    // Root sessions last (empty directory or directly in base directory, ungrouped)
    for (session in rootSessions.sortedByDescending { it.time.updated }) {
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle, hasDraft = session.id in draftSessionIds, category = sessionCategories[session.id]),
        ))
    }

    return result
}
