package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode
import dev.leonardo.ocbeacon.ui.screens.sessions.components.buildTreeNodes

/**
 * 构建 [SessionListUiState] 的纯函数——从 [SessionListViewModel.uiState] combine 管线提取。
 *
 * 保持与原内联逻辑完全一致：会话过滤、搜索、分类过滤、树节点构建。
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
    val categoryFilterId = values[15] as String?
    val categoriesList = values[16] as List<Tag>

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

    val categoryFilteredSessions = if (categoryFilterId != null) {
        searchedSessions.filter { categoryFilterId in (categoryAssignments[it.id] ?: emptyList()) }
    } else {
        searchedSessions
    }

    val tagsById = categoriesList.associateBy { it.id }
    val resolvedTags: Map<String, List<Tag>> = buildMap {
        categoryAssignments.forEach { (sessionId, tagIds) ->
            put(sessionId, tagIds.mapNotNull { tagsById[it] })
        }
    }

    val treeNodes = if (viewMode == SessionViewMode.RECENT) {
        categoryFilteredSessions.map { session ->
            TreeNode.Session(
                id = session.id,
                session = SessionItem(
                    session = session,
                    status = statuses[session.id] ?: SessionStatus.Idle,
                    hasDraft = session.id in draftRepository.getDraftSessionIds(),
                    tags = resolvedTags[session.id].orEmpty()
                )
            )
        }
    } else {
        buildTreeNodes(categoryFilteredSessions, expandedPaths, baseDirectory, statuses, draftRepository.getDraftSessionIds(), resolvedTags)
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
