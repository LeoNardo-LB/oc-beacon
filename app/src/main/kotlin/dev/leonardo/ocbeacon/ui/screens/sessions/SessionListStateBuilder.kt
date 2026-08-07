package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
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
 * 未读判定：会话存在完成回复时间且晚于 max(最后已读时间, 一键已读时间, 未读基线)。
 * 纯函数，供 RECENT 视图与 FOLDER 树共用。
 */
internal fun isUnread(
    sessionId: String,
    lastReplyTime: Map<String, Long>,
    readTimes: Map<String, Long>,
    unreadBaseline: Long = 0L,
    allReadAt: Long = 0L,
): Boolean {
    val last = lastReplyTime[sessionId] ?: return false
    return last > maxOf(readTimes[sessionId] ?: 0L, unreadBaseline, allReadAt)
}

/**
 * 构建 [SessionListUiState] 的纯函数——从 [SessionListViewModel.uiState] combine 管线提取。
 *
 * 保持与原内联逻辑完全一致：会话过滤、搜索、分类过滤、树节点构建。
 *
 * ⚠️ values 索引约定（与 SessionListViewModel.combine 参数顺序一一对应）：
 *   0 sessions · 1 statuses · 2 serverSessions · 3 lastUserMessageTime · 4 isLoading ·
 *   5 error · 6 projects(未用) · 7 expandedPaths · 8 selectedIds · 9 baseDirectory ·
 *   10 isRefreshing · 11 lastToggledDirectory · 12 searchQuery · 13 viewMode ·
 *   14 categoryAssignments · 15 categoryFilterIds · 16 sessionTags · 17 favoritesOnly ·
 *   18 lastReplyTime · 19 readTimes(persisted) · 20 unreadBaseline · 21 justRead(内存) · 22 allReadAt
 */
@Suppress("UNCHECKED_CAST")
internal fun buildSessionListUiState(
    values: Array<Any?>,
    serverId: String,
    serverName: String,
    draftRepository: DraftRepository,
): SessionListUiState {
    val allSessions = values[0] as List<Session>
    val statuses = values[1] as Map<String, SessionStatus>
    val serverSessionMap = values[2] as Map<String, Set<String>>
    val lastUserMessageTime = values[3] as Map<String, Long>
    val isLoading = values[4] as Boolean
    val error = values[5] as String?
    val expandedPaths = values[7] as Set<String>
    val selectedIds = values[8] as Set<String>
    val baseDirectory = values[9] as String?
    val isRefreshing = values[10] as Boolean
    val lastToggledDirectory = values[11] as String?
    val searchQuery = values[12] as String?
    val viewMode = values[13] as SessionViewMode
    val categoryAssignments = values[14] as Map<String, List<String>>
    val categoryFilterIds = values[15] as Set<String>
    val categoriesList = values[16] as List<Tag>
    val favoritesOnly = values[17] as Boolean
    val lastReplyTime = values[18] as Map<String, Long>
    val readTimes = mergeReadTimes(
        values[19] as Map<String, Long>,
        values[21] as Map<String, Long>,
    )
    val unreadBaseline = values[20] as Long
    val allReadAt = values[22] as Long

    val serverSessionIds = serverSessionMap[serverId].orEmpty()

    val filteredSessions = allSessions
        .filter { it.id in serverSessionIds && it.parentId == null }
        .sortedByDescending { session ->
            lastUserMessageTime[session.id] ?: session.time.updated
        }

    val baseFilteredSessions = if (baseDirectory != null) {
        filteredSessions.filter { session ->
            val dir = session.directory.replace('\\', '/').trimEnd('/')
            dir.startsWith(baseDirectory)
        }
    } else {
        filteredSessions
    }

    val searchedSessions = if (!searchQuery.isNullOrBlank()) {
        val query = searchQuery.lowercase()
        baseFilteredSessions.filter { session ->
            session.directory.lowercase().contains(query) ||
                session.title?.lowercase()?.contains(query) == true
        }
    } else {
        baseFilteredSessions
    }

    // 多选分类过滤（AND 语义）：会话必须包含所有选中的 tag；未选任何 tag 时不过滤
    val categoryFilteredSessions = if (categoryFilterIds.isEmpty()) {
        searchedSessions
    } else {
        searchedSessions.filter { session ->
            val sessionTags = categoryAssignments[session.id].orEmpty()
            categoryFilterIds.all { it in sessionTags }
        }
    }

    // 仅收藏筛选：从统一分配 map 派生内置收藏标签（builtin:favorite）的会话
    val favoritesFilteredSessions = if (favoritesOnly) {
        val favoriteIds = categoryAssignments
            .filterValues { dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID in it }
            .keys
        categoryFilteredSessions.filter { it.id in favoriteIds }
    } else {
        categoryFilteredSessions
    }

    val tagsById = categoriesList.associateBy { it.id }
    val resolvedTags: Map<String, List<Tag>> = buildMap {
        categoryAssignments.forEach { (sessionId, tagIds) ->
            put(sessionId, tagIds.mapNotNull { tagsById[it] })
        }
    }

    val treeNodes = if (viewMode == SessionViewMode.RECENT) {
        favoritesFilteredSessions.map { session ->
            TreeNode.Session(
                id = session.id,
                session = SessionItem(
                    session = session,
                    status = statuses[session.id] ?: SessionStatus.Idle,
                    hasDraft = session.id in draftRepository.getDraftSessionIds(),
                    tags = resolvedTags[session.id].orEmpty(),
                    hasUnread = isUnread(session.id, lastReplyTime, readTimes, unreadBaseline, allReadAt)
                )
            )
        }
    } else {
        buildTreeNodes(favoritesFilteredSessions, expandedPaths, baseDirectory, statuses, draftRepository.getDraftSessionIds(), resolvedTags, lastReplyTime, readTimes, unreadBaseline, allReadAt)
    }

    val prefillDirectory = if (lastToggledDirectory != null && lastToggledDirectory in expandedPaths)
        lastToggledDirectory
    else
        baseDirectory

    return SessionListUiState(
        treeNodes = treeNodes,
        sessions = filteredSessions,
        serverName = serverName,
        isLoading = isLoading,
        error = error,
        selectedIds = selectedIds,
        isSelectionMode = selectedIds.isNotEmpty(),
        baseDirectory = baseDirectory,
        baseDirectories = emptySet(),
        isRefreshing = isRefreshing,
        prefillDirectory = prefillDirectory,
        searchQuery = searchQuery,
    )
}

/**
 * 内容册构建纯函数——从 [SessionListDataInputs] + [SessionListUiInputs] 构建列表渲染状态。
 * 逻辑与旧 buildSessionListUiState 完全一致（过滤/搜索/分类/收藏/树构建/未读）。
 * 外壳字段（isLoading/isRefreshing/error/serverName）不再进入此函数。
 */
internal fun buildContentState(
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

    val tagsById = data.sessionTags.associateBy { it.id }
    val resolvedTags: Map<String, List<Tag>> = buildMap {
        data.categoryAssignments.forEach { (sessionId, tagIds) ->
            put(sessionId, tagIds.mapNotNull { tagsById[it] })
        }
    }

    val treeNodes = if (ui.viewMode == SessionViewMode.RECENT) {
        favoritesFilteredSessions.map { session ->
            TreeNode.Session(
                id = session.id,
                session = SessionItem(
                    session = session,
                    status = data.statuses[session.id] ?: SessionStatus.Idle,
                    hasDraft = session.id in draftRepository.getDraftSessionIds(),
                    tags = resolvedTags[session.id].orEmpty(),
                    hasUnread = isUnread(session.id, data.lastReplyTime, readTimes, data.unreadBaseline, data.allReadAt)
                )
            )
        }
    } else {
        buildTreeNodes(favoritesFilteredSessions, ui.expandedPaths, ui.baseDirectory, data.statuses, draftRepository.getDraftSessionIds(), resolvedTags, data.lastReplyTime, readTimes, data.unreadBaseline, data.allReadAt)
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
