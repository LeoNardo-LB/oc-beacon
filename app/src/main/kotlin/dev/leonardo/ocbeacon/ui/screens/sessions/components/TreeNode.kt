package dev.leonardo.ocbeacon.ui.screens.sessions.components

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem
import dev.leonardo.ocbeacon.ui.screens.sessions.isUnread
import dev.leonardo.ocbeacon.util.PathUtils

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
 *   - 会话按完整目录路径分组：每个目录一个可展开的分组
 *   - 分组按最近活动时间（降序）排序，再按目录名
 *
 * @param sessions 已过滤的会话（已限定到服务器、未归档等）
 * @param expandedDirs 当前展开的目录路径集合
 * @param baseDirectory 选定的基础目录路径（已规范化，如 "D:/Develop"），或 null
 * @param statuses 会话状态映射
 * @param sessionTags 已解析的 会话 id → 标签列表 映射，用于显示
 * @param lastMessageTime 会话 id → 最后消息时间（未读判定）
 * @param readTimes 会话 id → 最后已读时间（未读判定）
 */
fun buildTreeNodes(
    sessions: List<Session>,
    expandedDirs: Set<String>,
    baseDirectory: String?,
    statuses: Map<String, SessionStatus> = emptyMap(),
    draftSessionIds: Set<String> = emptySet(),
    sessionTags: Map<String, List<Tag>> = emptyMap(),
    lastMessageTime: Map<String, Long> = emptyMap(),
    readTimes: Map<String, Long> = emptyMap(),
    allReadAt: Long = 0L,
    pendingQuestionIds: Set<String> = emptySet(),
): List<TreeNode> {
    // 性能监控（2026-08-13 用户反馈目录点击卡顿）：树重建 >50ms 打 warn
    val buildStart = System.currentTimeMillis()
    val result = buildTreeNodesInternal(
        sessions, expandedDirs, baseDirectory, statuses, draftSessionIds,
        sessionTags, lastMessageTime, readTimes, allReadAt, pendingQuestionIds
    )
    val elapsed = System.currentTimeMillis() - buildStart
    if (elapsed > 50) {
        dev.leonardo.ocbeacon.logging.AppLogger.w(
            "SessionTree",
            "buildTreeNodes SLOW: sessions=${sessions.size} expanded=${expandedDirs.size} took=${elapsed}ms"
        )
    }
    return result
}

private fun buildTreeNodesInternal(
    sessions: List<Session>,
    expandedDirs: Set<String>,
    baseDirectory: String?,
    statuses: Map<String, SessionStatus>,
    draftSessionIds: Set<String>,
    sessionTags: Map<String, List<Tag>>,
    lastMessageTime: Map<String, Long>,
    readTimes: Map<String, Long>,
    allReadAt: Long,
    pendingQuestionIds: Set<String>,
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
            // 按完整目录路径分组：每个会话按自己的目录独立成组。
            // 此前为"项目感知分组"——把目录匹配到服务器 /project 返回的
            // 项目聚合（如服务器全局/根项目名为 global 时，会把不同目录的
            // 会话聚合到一个 global 文件夹），不符合按文件夹浏览的预期，
            // 故改回纯目录分组。
            val groupPath = dir.ifEmpty { "/" }
            // displayName 用目录名（最后一段）而非完整路径，移动端 UI 更友好；
            // path/id/key 保留完整路径用于展开匹配。
            val displayName = PathUtils.fileName(groupPath).ifBlank { groupPath }
            bucketFor(
                key = "dir:$groupPath",
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
                val status = statuses[session.id] ?: SessionStatus.Idle
                result.add(TreeNode.Session(
                    id = session.id,
                    session = SessionItem(session = session, status = status, hasDraft = session.id in draftSessionIds, tags = sessionTags[session.id].orEmpty(), hasUnread = isUnread(session.id, lastMessageTime, readTimes, allReadAt, status), hasPendingQuestion = session.id in pendingQuestionIds),
                ))
            }
        }
    }

    // 根会话放最后（空目录或直接位于 base 目录，未分组）
    for (session in rootSessions.sortedByDescending { it.time.updated }) {
        val status = statuses[session.id] ?: SessionStatus.Idle
        result.add(TreeNode.Session(
            id = session.id,
            session = SessionItem(session = session, status = status, hasDraft = session.id in draftSessionIds, tags = sessionTags[session.id].orEmpty(), hasUnread = isUnread(session.id, lastMessageTime, readTimes, allReadAt, status), hasPendingQuestion = session.id in pendingQuestionIds),
        ))
    }

    return result
}
