package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode

internal const val TAG_SESSION_LIST_VM = "SessionListViewModel"

enum class SessionViewMode { FOLDER, RECENT }

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val hasDraft: Boolean = false,
    val tags: List<Tag> = emptyList(),
    /** 会话有比最后已读时间更新的消息（未读提示红点）。 */
    val hasUnread: Boolean = false,
)

// 低频数据输入（DataStore/服务派生，变化少）
data class SessionListDataInputs(
    val sessions: List<Session>,
    val statuses: Map<String, SessionStatus>,
    val serverSessionMap: Map<String, Set<String>>,
    val lastUserMessageTime: Map<String, Long>,
    val categoryAssignments: Map<String, List<String>>,
    val sessionTags: List<Tag>,
    val favoritesOnly: Boolean,
    val lastReplyTime: Map<String, Long>,
    val readTimes: Map<String, Long>,
    val unreadBaseline: Long,
    val justRead: Map<String, Long>,
    val allReadAt: Long,
)

// 高频 UI 输入（用户交互）
data class SessionListUiInputs(
    val expandedPaths: Set<String>,
    val selectedIds: Set<String>,
    val baseDirectory: String?,
    val lastToggledDirectory: String?,
    val searchQuery: String?,
    val viewMode: SessionViewMode,
    val categoryFilterIds: Set<String>,
)

// 内容册：列表渲染相关
data class SessionListContentState(
    val treeNodes: List<TreeNode> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val baseDirectory: String? = null,
    val searchQuery: String? = null,
    val prefillDirectory: String? = null,
)

// 外壳册：顶栏/框架相关（本任务定义，Task 2 使用）
data class SessionListShellState(
    val serverName: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)
