package dev.leonardo.ocbeacon.ui.screens.sessions.util

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionItem

/**
 * 属于同一项目（或未匹配目录）的一组会话。
 *
 * 项目感知分组优于原始目录分组，它将 [Session.projectId] 映射到
 * 同一 [Project] 的会话聚合在一起，即使它们位于不同的 worktree 路径。
 * 没有匹配项目的会话各自形成按目录的分组。
 */
data class ProjectSessionGroup(
    val projectId: String,
    val projectName: String,
    val directory: String,
    val sessions: List<SessionItem>,
    /** 每个会话的波浪号路径标签（sessionId -> tildePath），用于扁平显示。 */
    val sessionDirLabels: Map<String, String> = emptyMap(),
)

/**
 * 解析 [session] 所属的 [Project]。
 *
 * 匹配优先级：
 *  1. 精确匹配 [Session.projectId] 与 [Project.id]。
 *  2. 最长匹配的 worktree/路径前缀回退 — 处理位于
 *     服务器未填充 `projectId` 的 project worktree 内的会话。
 *
 * 无项目匹配时返回 `null`。
 */
internal fun projectForSession(session: Session, projects: List<Project>): Project? {
    // 1. 精确匹配 projectId。
    projects.firstOrNull { it.id.isNotBlank() && it.id == session.projectId }?.let { return it }
    // 2. 最长 worktree/路径前缀回退。
    val directory = normalizedPath(session.directory)
    return projects
        .filter { project ->
            val root = projectRoot(project)
            root.isNotEmpty() && (directory == root || directory.startsWith("$root/"))
        }
        .maxByOrNull { projectRoot(it).length }
}

/** 对组内会话排序：最新活动优先。 */
internal fun sortSessionItems(items: List<SessionItem>): List<SessionItem> =
    items.sortedByDescending { it.session.time.updated }

/**
 * 从扁平的 [SessionItem] 列表构建项目感知的会话分组。
 *
 * 会话按所属项目分组（见 [projectForSession]）；无匹配项目的会话
 * 各自形成以目录为键的独立分组。分组按最近活动时间（降序）排序，再按名称。
 *
 * @param homeDir 可选的服务器主目录，用于在 [ProjectSessionGroup.sessionDirLabels]
 *  中渲染波浪号相对标签。传 `null` 保留绝对路径。
 */
internal fun buildProjectSessionGroups(
    sessions: List<SessionItem>,
    projects: List<Project>,
    homeDir: String?,
): List<ProjectSessionGroup> {
    fun displayPath(path: String): String {
        val dir = normalizedPath(path)
        val home = homeDir?.let(::normalizedPath)
        return if (home != null && home.isNotEmpty() && (dir == home || dir.startsWith("$home/"))) {
            "~" + dir.removePrefix(home)
        } else {
            dir
        }
    }

    return sessions
        .groupBy { item ->
            val project = projectForSession(item.session, projects)
            project?.id?.takeIf { it.isNotBlank() } ?: "directory:${normalizedPath(item.session.directory)}"
        }
        .map { (key, items) ->
            val sorted = sortSessionItems(items)
            val project = projectForSession(sorted.first().session, projects)
            val directory = normalizedPath(
                project?.worktree?.takeIf { it.isNotBlank() }
                    ?: project?.path?.takeIf { it.isNotBlank() }
                    ?: sorted.first().session.directory,
            )
            ProjectSessionGroup(
                projectId = project?.id ?: key,
                projectName = project?.displayName
                    ?: directory.substringAfterLast('/').ifEmpty { "/" },
                directory = directory,
                sessions = sorted,
                sessionDirLabels = sorted.associate { it.session.id to displayPath(it.session.directory) },
            )
        }
        .sortedWith(
            compareByDescending<ProjectSessionGroup> { group ->
                group.sessions.maxOfOrNull { it.session.time.updated } ?: 0
            }.thenBy { it.projectName.lowercase() }
        )
}

/** 规范化远程路径：统一分隔符、去除尾部斜杠、空则默认根。 */
private fun normalizedPath(path: String): String =
    path.replace('\\', '/').trimEnd('/').ifEmpty { "/" }

/** 规范的项目根：优先 worktree，否则回退到旧版 path。 */
private fun projectRoot(project: Project): String =
    normalizedPath(project.worktree.ifBlank { project.path })
