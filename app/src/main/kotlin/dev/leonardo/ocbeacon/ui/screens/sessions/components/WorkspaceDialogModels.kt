package dev.leonardo.ocbeacon.ui.screens.sessions.components

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.Workspace
import dev.leonardo.ocbeacon.domain.model.WorkspaceSnapshot

/**
 * #311 Task3 多 workspace 真建模——新建会话对话框纯函数集（TDD 红→绿载体）。
 *
 * 契约（docs/research/2026-09-05-311-wire-contracts.md ①-d）：
 * - 归属 = workspace.sessionIds **显式数组**（host 维护）——非 cwd 推断；
 * - 未入组会话 = stray，按 cwd/recency 兜底（web mod29:381-383 同款语义）；
 * - 名字键 = title（不取 path 尾段）。
 *
 * 移动端形态裁决（#313 原则——不照搬 web 树）：单列 recency 序 + 图标区分
 * （workspace=Workspaces / stray=Folder），stray 目录与 workspace.path 同径时
 * 去重让位 workspace（同目录连接语义下入组严格优于裸 cwd）。
 */

/** 新建会话对话框行条目（workspace 真建模 + stray/回退目录统一形状）。
 * public：SessionListViewModel 公开流/方法签名暴露（构建纯函数族保持 internal）。 */
data class WorkspaceDialogEntry(
    /** 入组 workspace id；null = stray 目录 / listProjects 回退条目（目录导航懒建）。 */
    val workspaceId: String?,
    /** 显示名：workspace.title / Project.displayName / 目录尾段。 */
    val title: String,
    /** 规范化目录路径（posix 分隔 + 去尾斜杠）。 */
    val path: String,
    /** 在组（或同目录）未归档会话数（列表滤除面外的 blank 空壳不计）。 */
    val sessionCount: Int,
    /** 组内会话最近更新时刻（recency 排序键；空组 0）。 */
    val lastUsed: Long,
)

/** 目录规范化（跨平台远程路径）：反斜杠 → 正斜杠 + 去尾斜杠（PathUtils 同语义轻量内联）。 */
internal fun normalizeDirectoryPath(path: String): String =
    path.replace('\\', '/').trimEnd('/')

/**
 * V012 快照模式条目：workspace 注册表（title + 在组未归档计数）+ stray 目录兜底。
 *
 * - 计数/lastUsed 基于 [sessions]（列表滤除面：DSH blank 空壳不在其中；归档态
 *   不在 session.list——archivedIds 再滤一次为防御性双保险，Task2 同款）；
 * - stray = 不在任何 workspace.sessionIds 且未归档的会话，按规范化 cwd 分组、
 *   recency 排序（cwd 兜底，web stray 桶同语义）；
 * - stray 目录与 workspace.path 同径 → 去重让位 workspace；
 * - 空注册表 → 空列表（调用方回退 listProjects/最近目录——快照缺席不是数据态）。
 */
internal fun workspaceDialogEntries(
    snapshot: WorkspaceSnapshot,
    sessions: List<Session>,
    limit: Int = 20,
): List<WorkspaceDialogEntry> {
    if (snapshot.workspaces.isEmpty()) return emptyList()
    val archivedIds = snapshot.archivedSessionIds.toSet()
    val groupedIds = snapshot.workspaces.flatMapTo(mutableSetOf()) { it.sessionIds }
    val sessionsById = sessions.associateBy { it.id }
    val workspacePaths = snapshot.workspaces.mapTo(mutableSetOf()) { normalizeDirectoryPath(it.path) }

    val workspaceEntries = snapshot.workspaces.map { ws ->
        val members = ws.sessionIds.mapNotNull { sessionsById[it] }.filter { it.id !in archivedIds }
        WorkspaceDialogEntry(
            workspaceId = ws.workspaceId,
            title = ws.title,
            path = ws.path,
            sessionCount = members.size,
            lastUsed = members.maxOfOrNull { it.time.updated } ?: 0L,
        )
    }

    val strayEntries = sessions
        .filter { it.id !in groupedIds && it.id !in archivedIds }
        .filter { normalizeDirectoryPath(it.directory).isNotBlank() }
        .groupBy { normalizeDirectoryPath(it.directory) }
        .filterKeys { it !in workspacePaths }
        .map { (directory, items) ->
            WorkspaceDialogEntry(
                workspaceId = null,
                title = directory.substringAfterLast('/').ifEmpty { directory },
                path = directory,
                sessionCount = items.size,
                lastUsed = items.maxOf { it.time.updated },
            )
        }

    return (workspaceEntries + strayEntries)
        .sortedWith(
            compareByDescending<WorkspaceDialogEntry> { it.lastUsed }
                .thenBy { it.title }
                .thenBy { it.path },
        )
        .take(limit)
}

/**
 * 空快照回退（V011 DSH）：listProjects 投影条目（V011 workspace.list → Project
 * {worktree=path, name=title}，Task1 零回归改造链）——计数按 cwd 匹配兜底
 * （V011 线面 sessionIds 缺席）。选择走目录导航懒建（V011 createSession 无
 * workspaceId 键，服务端不分组——连接语义不可达，维持现行为）。
 */
internal fun projectDialogEntries(
    projects: List<Project>,
    sessions: List<Session>,
    limit: Int = 20,
): List<WorkspaceDialogEntry> = projects
    .asSequence()
    .filter { normalizeDirectoryPath(it.worktree).isNotBlank() }
    .map { project ->
        val directory = normalizeDirectoryPath(project.worktree)
        val members = sessions.filter { normalizeDirectoryPath(it.directory) == directory }
        WorkspaceDialogEntry(
            workspaceId = null,
            title = project.displayName,
            path = directory,
            sessionCount = members.size,
            lastUsed = members.maxOfOrNull { it.time.updated } ?: 0L,
        )
    }
    .sortedWith(
        compareByDescending<WorkspaceDialogEntry> { it.lastUsed }
            .thenBy { it.title }
            .thenBy { it.path },
    )
    .take(limit)
    .toList()

/** 非 DSH 回退：既有最近目录（recentSessionDirectories）→ 统一条目形状。 */
internal fun RecentSessionDirectory.toDialogEntry(): WorkspaceDialogEntry = WorkspaceDialogEntry(
    workspaceId = null,
    title = name,
    path = directory,
    sessionCount = count,
    lastUsed = lastUsed,
)

/**
 * 连接复用判定（web uiWorkspace.connectWorkspace mod29:46-58 四条件，逐字对齐）：
 *
 * 1. blank（空壳——无 turn/start 事件流）；
 * 2. cwd === workspace.path（规范化双容忍：分隔符 + 尾斜杠）；
 * 3. 会话 id ∈ workspace.sessionIds（显式在组）；
 * 4. 未归档（archivedSessionIds 快照集合）。
 *
 * 候选序即命中序（web 按会话列表序首个命中）；[candidates] 应来自
 * listSessionsIncludingBlank（列表流滤除 blank——见 DshSessionMapper.filterByDirectory）。
 */
internal fun findReusableBlankSession(
    workspace: Workspace,
    candidates: List<Session>,
    archivedIds: Set<String>,
): Session? = candidates.firstOrNull { session ->
    session.blank &&
        normalizeDirectoryPath(session.directory) == normalizeDirectoryPath(workspace.path) &&
        session.id in workspace.sessionIds &&
        session.id !in archivedIds
}
