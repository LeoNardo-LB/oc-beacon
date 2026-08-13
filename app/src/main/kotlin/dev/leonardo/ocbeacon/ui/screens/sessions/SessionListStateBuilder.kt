package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import dev.leonardo.ocbeacon.ui.screens.sessions.components.buildTreeNodes

/**
 * 合并持久化已读时间与内存即时已读信号（取每会话较大值）。
 * 退出会话瞬间 DataStore 写入未完成时，内存信号先生效——消除红点闪烁。
 */
internal fun mergeReadTimes(
    persisted: Map<String, Long>,
    inMemory: Map<String, Long>,
): Map<String, Long> = (persisted.keys + inMemory.keys).associateWith {
    maxOf(persisted[it] ?: 0L, inMemory[it] ?: 0L)
}

/**
 * 未读判定：会话状态为 Idle（turn 完全结束）且有完成回复时间，
 * 且晚于 max(最后已读位置, 一键已读位置)。全部服务器时刻，纯函数。
 * 会话状态未知/进行中（非 Idle）→ 不红点。
 */
internal fun isUnread(
    sessionId: String,
    maxCompleted: Map<String, Long>,
    readTimes: Map<String, Long>,
    allReadAt: Long = 0L,
    status: SessionStatus,
): Boolean {
    if (status != SessionStatus.Idle) return false
    val last = maxCompleted[sessionId] ?: return false
    return last > maxOf(readTimes[sessionId] ?: 0L, allReadAt)
}

/**
 * 内容册构建纯函数——从 [SessionListDataInputs] + [SessionListUiInputs] 构建列表渲染状态。
 * 逻辑：过滤/搜索/分类/收藏/树构建/未读。
 * 外壳字段（isLoading/isRefreshing/error/serverName）不再进入此函数。
 *
 * `suspend` 因为读取 [DraftRepository.getDraftSessionIds] 需要异步 IO（backlog #38）。
 */
internal suspend fun buildContentState(
    data: SessionListDataInputs,
    ui: SessionListUiInputs,
    serverId: String,
    draftRepository: DraftRepository,
): SessionListContentState {
    val readTimes = mergeReadTimes(data.readTimes, data.justRead)

    val serverSessionIds = data.serverSessionMap[serverId].orEmpty()

    val filteredSessions = data.sessions
        .filter { it.id in serverSessionIds && it.parentId == null }
        .sortedByDescending { session ->
            data.lastUserMessageTime[session.id] ?: session.time.updated
        }

    val baseFilteredSessions = if (ui.baseDirectory != null) {
        filteredSessions.filter { session ->
            val dir = session.directory.replace('\\', '/').trimEnd('/')
            dir.startsWith(ui.baseDirectory)
        }
    } else {
        filteredSessions
    }

    val searchedSessions = if (!ui.searchQuery.isNullOrBlank()) {
        val query = ui.searchQuery.lowercase()
        baseFilteredSessions.filter { session ->
            session.directory.lowercase().contains(query) ||
                session.title?.lowercase()?.contains(query) == true
        }
    } else {
        baseFilteredSessions
    }

    val categoryFilteredSessions = if (ui.categoryFilterIds.isEmpty()) {
        searchedSessions
    } else {
        searchedSessions.filter { session ->
            val sessionTags = data.categoryAssignments[session.id].orEmpty()
            ui.categoryFilterIds.all { it in sessionTags }
        }
    }

    val favoritesFilteredSessions = if (data.favoritesOnly) {
        val favoriteIds = data.categoryAssignments
            .filterValues { dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID in it }
            .keys
        categoryFilteredSessions.filter { it.id in favoriteIds }
    } else {
        categoryFilteredSessions
    }

    // 草稿会话 id 集合——读取一次复用（suspend IO，避免重复调用）
    val draftSessionIds = draftRepository.getDraftSessionIds()

    val tagsById = data.sessionTags.associateBy { it.id }
    val resolvedTags: Map<String, List<Tag>> = buildMap {
        data.categoryAssignments.forEach { (sessionId, tagIds) ->
            put(sessionId, tagIds.mapNotNull { tagsById[it] })
        }
    }

    // 2026-08-14：提问中并入状态枚举——有 pending question 的会话状态为
    // SessionStatus.Asking（替代独立的 hasPendingQuestion 布尔标记）
    val mergedStatuses: Map<String, SessionStatus> =
        data.statuses + data.pendingQuestionIds.associateWith { SessionStatus.Asking }

    val treeNodes = if (ui.viewMode == SessionViewMode.RECENT) {
        favoritesFilteredSessions.map { session ->
            TreeNode.Session(
                id = session.id,
                session = SessionItem(
                    session = session,
                    status = mergedStatuses[session.id] ?: SessionStatus.Idle,
                    hasDraft = session.id in draftSessionIds,
                    tags = resolvedTags[session.id].orEmpty(),
                    hasUnread = isUnread(session.id, data.lastReplyTime, readTimes, data.allReadAt, mergedStatuses[session.id] ?: SessionStatus.Idle),
                )
            )
        }
    } else {
        buildTreeNodes(favoritesFilteredSessions, ui.expandedPaths, ui.baseDirectory, mergedStatuses, draftSessionIds, resolvedTags, data.lastReplyTime, readTimes, data.allReadAt)
    }

    val prefillDirectory = if (ui.lastToggledDirectory != null && ui.lastToggledDirectory in ui.expandedPaths)
        ui.lastToggledDirectory
    else
        ui.baseDirectory

    return SessionListContentState(
        treeNodes = treeNodes,
        sessions = filteredSessions,
        selectedIds = ui.selectedIds,
        isSelectionMode = ui.selectedIds.isNotEmpty(),
        baseDirectory = ui.baseDirectory,
        searchQuery = ui.searchQuery,
        prefillDirectory = prefillDirectory,
    )
}
