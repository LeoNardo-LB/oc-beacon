package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode

internal const val TAG_SESSION_LIST_VM = "SessionListViewModel"

enum class SessionViewMode { FOLDER, RECENT }

data class SessionListUiState(
    val treeNodes: List<TreeNode> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val baseDirectory: String? = null,
    val baseDirectories: Set<String> = emptySet(),
    val isRefreshing: Boolean = false,
    val prefillDirectory: String? = null,
    val searchQuery: String? = null,
)

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val hasDraft: Boolean = false,
    val category: SessionCategory? = null,
)
