package dev.leonardo.ocbeacon.ui.screens.sessions.components

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import dev.leonardo.ocbeacon.ui.screens.sessions.util.projectForSession

/**
 * 扁平会话列表节点的密封接口。
 * 两个层级：Directory（可展开分组）和 Session（叶子项）。
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
 * 从会话列表构建扁平的两级节点列表。
 *
 * 设置 baseDirectory 时：
 *   - 会话按其相对 baseDirectory 的第一段路径分组
 *   - 直接位于 baseDirectory 的会话出现在顶部（不分组）
 *   - 每个分组是一个可展开的 Directory 节点
 *   - 分组按段名字母序排序（稳定的浏览顺序）
 *
 * baseDirectory 为 null 时：
 *   - 会话按项目感知分组：[Session.projectId]（或 worktree 前缀）
 *     映射到同一 [Project] 的会话聚合到一个分组。
 *     未匹配的会话形成按目录的分组。
 *   - 分组按最近活动时间（降序）排序，再按显示名。
 *
 * @param sessions 已过滤的会话（已限定到服务器、未归档等）
 * @param expandedDirs 当前展开的目录路径集合
 * @param baseDirectory 选定的基础目录路径（已规范化，如 "D:/Develop"），或 null
 * @param statuses 会话状态映射
 * @param projects baseDirectory 为 null 时用于项目感知分组的已知项目
 * @param sessionCategories 已解析的 会话 id → 分类 映射，用于显示
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
            // baseDirectory 模式：按相对 base 的第一段路径分组。
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
            // 项目感知分组：按所属项目聚合会话。
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

    // 分组排序：baseDirectory -> 按 id 字母序（稳定）；否则 -> 最近活动时间再按名称。
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

    // 根会话放最后（空目录或直接位于 base 目录，未分组）
    for (session in rootSessions.sortedByDescending { it.time.updated }) {
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = statuses[session.id] ?: SessionStatus.Idle, hasDraft = session.id in draftSessionIds, category = sessionCategories[session.id]),
        ))
    }

    return result
}
