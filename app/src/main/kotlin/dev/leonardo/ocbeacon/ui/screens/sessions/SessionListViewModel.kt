package dev.leonardo.ocbeacon.ui.screens.sessions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
import dev.leonardo.ocbeacon.ui.WhileSubscribed5s
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.McpServerStatus
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.PendingMessageRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SessionTagRepository
import dev.leonardo.ocbeacon.domain.usecase.CreateDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetServerPathsUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListProjectsUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListSessionsUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ProbeDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.SearchDirectoriesUseCase
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.util.copyToClipboard
import javax.inject.Inject

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val listSessionsUseCase: ListSessionsUseCase,
    private val listProjectsUseCase: ListProjectsUseCase,
    private val getServerPathsUseCase: GetServerPathsUseCase,
    private val probeDirectoryUseCase: ProbeDirectoryUseCase,
    private val searchDirectoriesUseCase: SearchDirectoriesUseCase,
    private val createDirectoryUseCase: CreateDirectoryUseCase,
    private val fileRepository: FileRepository,
    private val manageSessionUseCase: ManageSessionUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val draftRepository: DraftRepository,
    private val mcpRepository: McpRepository,
    private val scrollSignal: SessionScrollSignal,
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase,
    // C5 拆分：标签/收藏方法自 SettingsRepository 独立成 SessionTagRepository
    private val sessionTagRepository: SessionTagRepository,
    private val serverRepository: ServerRepository,
    private val unreadBadgeService: dev.leonardo.ocbeacon.data.repository.UnreadBadgeService,
    private val chatRepository: ChatRepository,
    // #176/#177：堆积队列手动「继续」入口（详情对话框）+ 计数可见性
    private val pendingMessageRepository: PendingMessageRepository,
    // 走查修复（UI→Data 分层）：经 domain 接口触发，不直依赖具体管线
    private val pendingMessageDrainController: dev.leonardo.ocbeacon.domain.usecase.PendingMessageDrainController,
    // #272：BM25 内容检索（FTS5 索引，纯本地）
    private val messageFtsIndex: dev.leonardo.ocbeacon.data.local.MessageFtsIndex,
) : ViewModel() {

    companion object {
        /** #100（M-11）：搜索输入防抖间隔。 */
        const val SEARCH_DEBOUNCE_MS = 300L
        /** ChatScreen 在用户发送消息时写入的 SavedStateHandle key；
         * 此 ViewModel 在返回时消费它以将列表滚动回顶部。 */
        const val KEY_SCROLL_TO_TOP = "session_list_scroll_to_top"
    }

    val serverId: String = safeDecodeParam(savedStateHandle.get<String>("serverId") ?: "")

    /** #177：会话 → 堆积队列计数（详情对话框「继续发送堆积消息」可见性）。 */
    val pendingCounts: StateFlow<Map<String, Int>> =
        pendingMessageRepository.observeCounts()
            .stateIn(viewModelScope, WhileSubscribed5s, emptyMap())

    /** #177：详情对话框手动放行队首（堆积状态补偿的显式逃生口）。 */
    fun continuePendingQueue(sessionId: String) {
        pendingMessageDrainController.continueFromList(sessionId)
    }

    // ============ 服务器配置异步加载（backlog #38：消除构造期主线程 runBlocking） ============
    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    private val directoryManager = DirectoryManager(
        serverId = serverId,
        getServerPathsUseCase = getServerPathsUseCase,
        probeDirectoryUseCase = probeDirectoryUseCase,
        searchDirectoriesUseCase = searchDirectoriesUseCase,
        createDirectoryUseCase = createDirectoryUseCase,
        fileRepository = fileRepository,
    )

    init {
        // #137（D2-L59）：旧收藏数据一次性迁移（原藏在 favoriteSessionIds flow map 内的
        // 隐蔽写库副作用——flow 发射时写 DataStore；迁移显式化后此处触发）
        viewModelScope.launch {
            sessionTagRepository.migrateLegacyFavoritesIfNeeded(serverId)
        }
        // backlog #38: 异步加载服务器配置，加载完成后设置 MCP 连接
        viewModelScope.launch {
            val config = kotlinx.coroutines.withContext(Dispatchers.IO) {
                serverRepository.getServer(serverId)
            }
            _serverName.value = config?.displayName ?: ""
            val conn = config?.let {
                ServerConnection.from(it.url, it.username, it.password, it.apiVersion)
            } ?: ServerConnection.from("", "", null)
            // #110（D2-24）：本 VM 缓存 conn 供 MCP 调用（显式传入，
            // 避免共享单例可变 connection 被其他服务器 VM 覆盖）。
            _mcpConn = conn
            mcpRepository.setConnection(conn)
        }
    }

    // ============ 内部状态 ============

    /** #110（D2-24）：本 VM 的服务器连接（loadConfig 时解析，MCP 调用显式使用）。 */
    private var _mcpConn: ServerConnection? = null

    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _expandedPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _baseDirectory = MutableStateFlow<String?>(null)
    private val _isRefreshing = MutableStateFlow(false)
    private val _lastToggledDirectory = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow<String?>(null)
    private val _currentCursor = MutableStateFlow<String?>(null)
    private val _hasMorePages = MutableStateFlow(true)
    private val _isLoadingMore = MutableStateFlow(false)

    private val _viewMode = MutableStateFlow(
        savedStateHandle.get<String>("viewMode")?.let {
            runCatching { SessionViewMode.valueOf(it) }.getOrNull()
        } ?: SessionViewMode.RECENT
    )
    val viewMode: StateFlow<SessionViewMode> = _viewMode.asStateFlow()

    /** 待标记已读的会话（点击进入时记录；返回列表时组合阶段消费，渲染前同步生效）。 */
    private val _pendingReadSessionId = MutableStateFlow<String?>(null)

    /** sessionId → 最后完成消息 completed 的内存快照。
     * Eagerly：consumePendingReadSessionId 是事件驱动、可能在无 UI 订阅时调用，
     * 快照必须随时可用（WhileSubscribed5s 会在无订阅时停止上游）。 */
    private val lastCompletedReplyTime = sessionRepository.getLastCompletedReplyTimeFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** #184：本服务器会话 id 快照（Eagerly：一键已读为事件驱动调用，
     * 可能在无 UI 订阅时触发，快照必须随时可用——同上者理由）。 */
    private val serverSessionIds = sessionRepository.getServerSessionsFlow()
        .map { it[serverId].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 点击会话进入时记录——返回列表时立即标记已读（消除 popBackStack 1 帧红点）。 */
    fun onSessionOpened(sessionId: String) {
        _pendingReadSessionId.value = sessionId
    }

    /**
     * 消费待标记会话并同步标记已读（列表组合阶段调用）。
     * 同步读内存快照 lastCompletedReplyTime.value[sid]（无 DataStore 文件 I/O）→
     * 同步 sessionReadSignal.markRead（内存信号即时生效）→ combine（Main.immediate）
     * 在渲染前完成重算，帧 1 即无红点；DataStore 持久化在 viewModelScope.launch 中异步执行。
     * 快照无值（该会话无 completed 记录）时跳过，不写客户端 now。
     */
    fun consumePendingReadSessionId(): String? {
        val sid = _pendingReadSessionId.value ?: return null
        _pendingReadSessionId.value = null
        // #171：已读写路径收进红点模块（水位线快照 + 内存信号 + app-scope 持久化）
        unreadBadgeService.markSessionRead(serverId, sid)
        return sid
    }

    /** 选中的分类过滤 id 集合，空 = "全部"。多选后按 AND 过滤。 */
    private val _tagFilters = MutableStateFlow<Set<String>>(emptySet())
    val tagFilters: StateFlow<Set<String>> = _tagFilters.asStateFlow()

    /** 仅显示收藏会话（本服务器内置标签筛选）。 */
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    /** 切换"仅收藏"筛选。 */
    fun toggleFavoritesOnly() {
        _favoritesOnly.value = !_favoritesOnly.value
    }

    /** 全局标签列表（按服务器划分），用于选择器 / 过滤 chip。 */
    val sessionTags: StateFlow<List<Tag>> = sessionTagRepository.sessionTags(serverId)
        .stateIn(viewModelScope, WhileSubscribed5s, emptyList())

    /** 按服务器划分的 sessionId → tagIds 分配（含内置收藏标签），供设置页标签管理逐会话解除使用。 */
    val sessionTagAssignments: StateFlow<Map<String, List<String>>> =
        sessionTagRepository.sessionTagAssignments(serverId)
            .stateIn(viewModelScope, WhileSubscribed5s, emptyMap())

    /** 当前服务器的已收藏会话 id（驱动 SessionRow 中的星标切换）。 */
    val favoriteSessionIds: StateFlow<Set<String>> = sessionTagRepository.favoriteSessionIds(serverId)
        .stateIn(viewModelScope, WhileSubscribed5s, emptySet())

    // ============ MCP 状态 ============

    private val _mcpServers = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val mcpServers: StateFlow<List<McpServerStatus>> = _mcpServers.asStateFlow()

    private val _mcpLoading = MutableStateFlow<String?>(null)
    val mcpLoading: StateFlow<String?> = _mcpLoading.asStateFlow()

    private val _mcpInitialLoading = MutableStateFlow(false)
    val mcpInitialLoading: StateFlow<Boolean> = _mcpInitialLoading.asStateFlow()

    private val _mcpError = MutableSharedFlow<String>()
    val mcpError: SharedFlow<String> = _mcpError.asSharedFlow()

    // ============ 聚合 UI 状态（#23 状态切片：嵌套分组 combine） ============
    // 分组设计：每组只携带自己拥有的字段（部分数据类），最终 dataFlow 合并 3 组。
    // 禁止"占位填充"（会重置其他组的字段）。

    // 分组1：会话数据（6 源）→ 部分字段
    private data class SessionDataPart(
        val sessions: List<Session>,
        val statuses: Map<String, SessionStatus>,
        val serverSessionMap: Map<String, Set<String>>,
        val lastUserMessageTime: Map<String, Long>,
        val lastReplyTime: Map<String, Long>,
        val questions: Map<String, List<SseEvent.QuestionAsked>>,
    )

    // kotlinx.coroutines combine 仅有 2-5 源的类型化重载；第 6 源用嵌套 combine 接入。
    // #100（M-11）：上游 6 源 distinctUntilChanged——重复发射（内容未变）不触发
    // 下游 combine 全量重建（过滤+排序+分类+树构建+未读判定）
    private val sessionDataFlow = combine(
        combine(
            sessionRepository.getSessionsFlow(serverId).distinctUntilChanged(),
            sessionStateRepository.statusFlow,
            sessionRepository.getServerSessionsFlow().distinctUntilChanged(),
            sessionRepository.getLastUserMessageTimeFlow().distinctUntilChanged(),
            sessionRepository.getLastCompletedReplyTimeFlow().distinctUntilChanged(),
        ) { sessions, statuses, serverSessionMap, lastUserMessageTime, lastReplyTime ->
            SessionCorePart(sessions, statuses, serverSessionMap, lastUserMessageTime, lastReplyTime)
        },
        chatRepository.getAllQuestionsFlow().distinctUntilChanged(),
    ) { core, questions ->
        SessionDataPart(core.sessions, core.statuses, core.serverSessionMap, core.lastUserMessageTime, core.lastReplyTime, questions)
    }

    /** 嵌套 combine 的中间载体（前 5 源）。 */
    private data class SessionCorePart(
        val sessions: List<Session>,
        val statuses: Map<String, SessionStatus>,
        val serverSessionMap: Map<String, Set<String>>,
        val lastUserMessageTime: Map<String, Long>,
        val lastReplyTime: Map<String, Long>,
    )

    // 分组2：设置数据（3 源——已读合并读收进红点模块单源，#171）
    private data class SettingDataPart(
        val tagAssignments: Map<String, List<String>>,
        val sessionTags: List<Tag>,
        val readTimes: Map<String, Long>,
    )

    private val settingDataFlow = combine(
        sessionTagRepository.sessionTagAssignments(serverId).distinctUntilChanged(),
        sessionTags,
        unreadBadgeService.mergedReadTimes(serverId),
    ) { assignments, tags, readTimes ->
        SettingDataPart(assignments, tags, readTimes)
    }

    // 分组3：杂项（2 源）
    private data class MiscDataPart(
        val favoritesOnly: Boolean,
        val allReadAt: Long,
    )

    private val miscDataFlow = combine(
        _favoritesOnly,
        unreadBadgeService.allReadAt(serverId),
    ) { favoritesOnly, allReadAt ->
        MiscDataPart(favoritesOnly, allReadAt)
    }

    // 数据流：3 组合并（3 源具名）
    private val dataFlow = combine(
        sessionDataFlow, settingDataFlow, miscDataFlow,
    ) { sessionData, settingData, miscData ->
        SessionListDataInputs(
            sessions = sessionData.sessions,
            statuses = sessionData.statuses,
            serverSessionMap = sessionData.serverSessionMap,
            lastUserMessageTime = sessionData.lastUserMessageTime,
            tagAssignments = settingData.tagAssignments,
            sessionTags = settingData.sessionTags,
            favoritesOnly = miscData.favoritesOnly,
            lastReplyTime = sessionData.lastReplyTime,
            readTimes = settingData.readTimes,
            allReadAt = miscData.allReadAt,
            pendingQuestionIds = sessionData.questions
                .filterKeys { it in sessionData.serverSessionMap[serverId].orEmpty() }
                .filterValues { it.isNotEmpty() }
                .keys
                .toSet(),
        )
    }

    // UI 流：2 组合并
    private data class UiGroup1Part(
        val expandedPaths: Set<String>,
        val selectedIds: Set<String>,
        val baseDirectory: String?,
        val lastToggledDirectory: String?,
    )

    private data class UiGroup2Part(
        val searchQuery: String?,
        val viewMode: SessionViewMode,
        val categoryFilterIds: Set<String>,
    )

    private val uiFlow = combine(
        combine(
            _expandedPaths, _selectedIds, _baseDirectory, _lastToggledDirectory,
        ) { expandedPaths, selectedIds, baseDirectory, lastToggledDirectory ->
            UiGroup1Part(expandedPaths, selectedIds, baseDirectory, lastToggledDirectory)
        },
        combine(
            // #100（M-11）：搜索输入防抖 300ms——逐键过滤改为停顿后过滤（纯客户端过滤）
            _searchQuery.debounce(SEARCH_DEBOUNCE_MS), _viewMode, _tagFilters,
        ) { searchQuery, viewMode, categoryFilterIds ->
            UiGroup2Part(searchQuery, viewMode, categoryFilterIds)
        },
    ) { g1, g2 ->
        SessionListUiInputs(
            expandedPaths = g1.expandedPaths,
            selectedIds = g1.selectedIds,
            baseDirectory = g1.baseDirectory,
            lastToggledDirectory = g1.lastToggledDirectory,
            searchQuery = g2.searchQuery,
            viewMode = g2.viewMode,
            categoryFilterIds = g2.categoryFilterIds,
        )
    }

    // 内容簇（状态簇·列表渲染）（最终）
    // #100（M-11）：上游 distinctUntilChanged + 搜索防抖已大幅降低全量重建频率；
    // buildContentState 保持收集线程（移 flowOn(Default) 会在测试/组合期引入
    // Dispatchers.Main 访问竞态——2026-08-15 实测 SessionListShellStateTest 失败）
    val contentState: StateFlow<SessionListContentState> = combine(
        dataFlow, uiFlow,
    ) { data, ui ->
        buildContentState(data, ui, serverId, draftRepository)
    }.stateIn(viewModelScope, WhileSubscribed5s, SessionListContentState())

    // 外壳簇（状态簇·框架）（独立）
    val shellState: StateFlow<SessionListShellState> = combine(
        _isLoading, _isRefreshing, _error, serverName,
    ) { isLoading, isRefreshing, error, sname ->
        SessionListShellState(
            serverName = sname,
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            error = error,
        )
    }.stateIn(viewModelScope, WhileSubscribed5s, SessionListShellState())

    /** 快速新建会话对话框中显示的最近目录最大数量。 */
    val recentDirectoryCount: StateFlow<Int> = getSettingsFlowUseCase()
        .map { it.recentDirectoryCount }
        .stateIn(viewModelScope, WhileSubscribed5s, 20)

    init {
        loadSessions()
    }

    // ============ 滚动 / 分类 / 收藏 ============

    /** 若上次进入 ChatScreen 时发送了消息，列表应滚动回顶部。 */
    fun consumeScrollToTopOnReturn(): Boolean = scrollSignal.consumeScrollToTop()

    /** 进入 ChatScreen 前标记：返回列表时滚动回顶部（无论是否发过消息）。 */
    fun requestScrollToTopOnReturn() = scrollSignal.requestScrollToTop()

    /** 切换分类过滤选中态（多选，AND 语义；全部取消后回到"全部"状态）。 */
    fun toggleCategoryFilter(categoryId: String) {
        _tagFilters.update { current ->
            if (categoryId in current) current - categoryId else current + categoryId
        }
    }

    /** 清空分类过滤（回到"全部"）。 */
    fun clearCategoryFilters() {
        _tagFilters.value = emptySet()
    }

    /**
     * 一键已读：记录本服务器已读位置（本服务器会话集内最后完成消息的最大 completed，
     * 服务器时刻），消除所有红点；此后新完成的回复才重新红点。
     * #184：作用域化到本服务器会话集——别服务器条目（可能时钟不同域）不再混入。
     */
    fun markAllSessionsRead() {
        // #171：水位线 max + 内存广播 + 持久化收进红点模块单点；#184：作用域本服务器会话集
        unreadBadgeService.markAllSessionsRead(serverId, serverSessionIds.value)
    }

    /** 替换指定会话上的用户标签集（保留内置收藏标签）。 */
    fun assignTags(sessionId: String, tagIds: Set<String>) {
        viewModelScope.launch { sessionTagRepository.setSessionTags(serverId, sessionId, tagIds) }
    }

    /** 切换当前服务器上某会话的收藏状态（基于内置收藏标签）。 */
    fun toggleFavorite(session: Session) {
        viewModelScope.launch {
            sessionTagRepository.toggleFavorite(serverId, session.id)
        }
    }

    /** 移除某会话在当前服务器上的某个标签分配（不删除标签本身）。 */
    fun removeSessionTagAssignment(sessionId: String, tagId: String) {
        viewModelScope.launch { sessionTagRepository.removeSessionTagAssignment(serverId, sessionId, tagId) }
    }

    /**
     * 新建一个用户标签，使用调用方预生成的 [id]（用于 TagPickerDialog 创建后立即勾选）。
     *
     * 返回 [id] 以便调用方把它加入本地选择集合。
     */
    fun addSessionTag(name: String, color: String, icon: String, id: String): String {
        viewModelScope.launch {
            sessionTagRepository.addSessionTag(
                serverId,
                Tag(
                    id = id,
                    name = name,
                    color = color,
                    icon = icon,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        return id
    }

    /** 更新一个已存在的用户标签（按 id 替换）。 */
    fun updateSessionTag(tag: Tag) {
        viewModelScope.launch { sessionTagRepository.updateSessionTag(serverId, tag) }
    }

    /** 按 id 删除一个用户标签（并原子清理所有分配）。 */
    fun removeSessionTag(tagId: String) {
        viewModelScope.launch { sessionTagRepository.removeSessionTag(serverId, tagId) }
    }

    // ============ 树形展开/收起 ============

    fun toggleDirectory(path: String) {
        val normalized = path.replace('\\', '/')
        _lastToggledDirectory.value = normalized
        _expandedPaths.update { paths ->
            if (normalized in paths) paths - normalized
            else paths + normalized
        }
    }

    fun setBaseDirectory(directory: String?) {
        _baseDirectory.value = directory?.replace('\\', '/')?.trimEnd('/')
        // 重置展开路径，让自动展开基于新 base 重新计算
        _expandedPaths.value = emptySet()
    }

    val currentBaseDirectory: String? get() = _baseDirectory.value

    // ============ 搜索 ============

    val searchQuery: String? get() = _searchQuery.value

    // #272：内容命中（FTS5 BM25，纯本地）——与标题命中并行检索，聚合展示
    private val _contentHits = MutableStateFlow<List<dev.leonardo.ocbeacon.data.local.ContentSearchHit>>(emptyList())
    val contentHits: StateFlow<List<dev.leonardo.ocbeacon.data.local.ContentSearchHit>> = _contentHits.asStateFlow()
    private var contentSearchJob: kotlinx.coroutines.Job? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query.ifBlank { null }
        contentSearchJob?.cancel()
        if (query.isBlank()) {
            _contentHits.value = emptyList()
            return
        }
        contentSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _contentHits.value = runCatching {
                messageFtsIndex.search(query, dev.leonardo.ocbeacon.data.local.ContentSearchFilter(limit = 100))
            }.getOrDefault(emptyList())
        }
    }

    fun clearSearchQuery() {
        _searchQuery.value = null
        contentSearchJob?.cancel()
        _contentHits.value = emptyList()
    }

    // ============ 分页属性（只读） ============

    val currentCursor: String? get() = _currentCursor.value
    val hasMorePages: Boolean get() = _hasMorePages.value
    val isLoadingMore: Boolean get() = _isLoadingMore.value

    // ============ 视图模式 ============

    fun toggleViewMode() {
        _viewMode.value = if (_viewMode.value == SessionViewMode.FOLDER) SessionViewMode.RECENT else SessionViewMode.FOLDER
        savedStateHandle["viewMode"] = _viewMode.value.name
    }

    // ============ 会话加载 / 刷新 / 分页 ============

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
            _error.value = null
            resetPagination()
            val failure = fetchAllSessions()
            if (failure != null) {
                _error.value = failure
            }
            if (_expandedPaths.value.isEmpty()) {
                    // 首次加载时默认展开所有目录
                    // firstOrNull：Flow 未发射值时不抛 NoSuchElementException（曾导致协程异常泄漏到全局线程池，污染其他 TestScope）
                    val currentSessions = sessionRepository.getSessionsFlow(serverId).firstOrNull() ?: emptyList()
                    val base = _baseDirectory.value?.replace('\\', '/')?.trimEnd('/')
                    val dirs = mutableSetOf<String>()
                    for (s in currentSessions) {
                        val dir = s.directory.replace('\\', '/').trimEnd('/')
                        if (base != null && dir.startsWith(base)) {
                            val relative = dir.removePrefix(base).removePrefix("/")
                            if (relative.isNotEmpty()) {
                                dirs.add("$base/${relative.substringBefore('/')}")
                            }
                        }
                    }
                    _expandedPaths.value = dirs
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshSessions() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            _error.value = null
            val failure = fetchAllSessions()
            if (failure != null) {
                _error.value = failure
            }
            _isRefreshing.value = false
        }
    }

    /**
     * 拉取所有项目的会话并同步 FSM 状态（D2-L21：loadSessions/refreshSessions 共享）。
     * @return 失败时的错误信息（成功为 null）。
     */
    private suspend fun fetchAllSessions(): String? = try {
        val projects = listProjectsUseCase(serverId).getOrThrow()
        _projects.value = projects
        if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Loaded ${projects.size} projects for multi-project session fetch")

        if (projects.isEmpty()) {
            val sessions = listSessionsUseCase(serverId, search = _searchQuery.value)
            sessionRepository.setSessions(serverId, sessions)
            if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Loaded ${sessions.size} sessions (no projects)")
        } else {
            var totalSessions = 0
            for (project in projects) {
                try {
                    val sessions = listSessionsUseCase(serverId, directory = project.worktree, search = _searchQuery.value)
                    sessionRepository.setSessions(serverId, sessions)
                    totalSessions += sessions.size
                    if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                } catch (e: Exception) {
                    AppLogger.w(TAG_SESSION_LIST_VM, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                }
            }
            if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
        }
        // 通过统一的 FSM 管线从服务器同步会话状态
        //（跨项目 worktree 聚合 + 缺失即 idle + 不完整保护）。
        sessionStateRepository.setServerId(serverId)
        sessionStateRepository.syncFromRest(_projects.value)
        null
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLogger.e(TAG_SESSION_LIST_VM, "Failed to load sessions", e)
        e.message ?: "Failed to load sessions"
    }

    fun resetPagination() {
        _currentCursor.value = null
        _hasMorePages.value = true
        _isLoadingMore.value = false
    }

    /**
     * 使用基于游标的分页加载下一页会话。
     * 由 UI 在用户滚动到会话列表底部附近时调用。
     */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMorePages.value) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val cursor = _currentCursor.value
                val sessions = listSessionsUseCase(
                    serverId,
                    directory = _baseDirectory.value,
                    search = _searchQuery.value,
                    cursor = cursor,
                    limit = 50
                )
                if (sessions.isNotEmpty()) {
                    sessionRepository.setSessions(serverId, sessions)
                    _currentCursor.value = sessions.last().id
                }
                if (sessions.size < 50) {
                    _hasMorePages.value = false
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG_SESSION_LIST_VM, "Failed to load more sessions", e)
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    // ============ 会话操作（删除 / 重命名 / 导入） ============

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val result = deleteSessionUseCase(serverId, sessionId)
                if (result.isSuccess) {
                    if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Deleted session $sessionId")
                    // 清理红点条目与内存已读信号残留（已删除会话的 key）
                    unreadBadgeService.removeSession(sessionId)
                    loadSessions()
                } else {
                    _error.value = "Failed to delete session"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG_SESSION_LIST_VM, "Failed to delete session", e)
                _error.value = e.message ?: "Failed to delete session"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                manageSessionUseCase.renameSession(serverId, sessionId, newTitle)
                if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Renamed session $sessionId to '$newTitle'")
                loadSessions()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG_SESSION_LIST_VM, "Failed to rename session", e)
                _error.value = e.message ?: "Failed to rename session"
            }
        }
    }

    /**
     * 从分享 URL 导入会话。
     * 成功后重新加载会话列表。
     */
    fun importSession(shareUrl: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val session = manageSessionUseCase.importSession(serverId, shareUrl)
                if (BuildConfig.DEBUG) AppLogger.d(TAG_SESSION_LIST_VM, "Imported session ${session.id}")
                sessionRepository.setSessions(serverId, listOf(session))
                onResult(true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG_SESSION_LIST_VM, "Failed to import session", e)
                _error.value = e.message ?: "Failed to import session"
                onResult(false)
            }
        }
    }

    fun copyToClipboard(text: String, context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.copyToClipboard("label", text)
    }

    // ============ 批量选择 ============

    fun toggleSelection(sessionId: String) {
        _selectedIds.update { selected ->
            if (sessionId in selected) selected - sessionId else selected + sessionId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        val currentState = contentState.value
        val sessionIds = currentState.treeNodes
            .filterIsInstance<dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode.Session>()
            .map { it.id }
            .toSet()
        _selectedIds.value = sessionIds
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return@launch
            try {
                val results = coroutineScope {
                    ids.map { id ->
                        async { id to deleteSessionUseCase(serverId, id).isSuccess }
                    }.awaitAll()
                }
                val failed = results.filterNot { it.second }
                if (failed.isNotEmpty()) {
                    _error.value = "Failed to delete ${failed.size} session(s)"
                }
                clearSelection()
                loadSessions()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLogger.e(TAG_SESSION_LIST_VM, "Failed to delete selected sessions", e)
                _error.value = e.message ?: "Failed to delete selected sessions"
            }
        }
    }

    // ============ MCP ============

    fun loadMcpServers() {
        viewModelScope.launch {
            _mcpInitialLoading.value = true
            val conn = _mcpConn ?: run { _mcpInitialLoading.value = false; return@launch }
            mcpRepository.getMcpServers(conn)
                .onSuccess { _mcpServers.value = it }
                .onFailure {
                    _mcpError.emit(it.message ?: "Failed to load MCP servers")
                }
            _mcpInitialLoading.value = false
        }
    }

    fun toggleMcpServer(name: String) {
        if (_mcpLoading.value == name) return
        val server = _mcpServers.value.find { it.name == name } ?: return
        val connect = server.status != "connected"
        _mcpLoading.value = name

        viewModelScope.launch {
            val conn = _mcpConn ?: run { _mcpLoading.value = null; return@launch }
            mcpRepository.toggleMcpServer(conn, name, connect)
                .onSuccess {
                    mcpRepository.getMcpServers(conn)
                        .onSuccess { _mcpServers.value = it }
                }
                .onFailure {
                    _mcpError.emit("Failed to ${if (connect) "connect" else "disconnect"} $name")
                }
            _mcpLoading.value = null
        }
    }

    // ============ 用于"打开项目"的目录浏览（委托给 DirectoryManager） ============

    suspend fun getServerPaths(): ServerPaths = directoryManager.getServerPaths()

    val isWindowsServer: Boolean
        get() = directoryManager.isWindowsServer

    suspend fun getHomeDirectory(): String = directoryManager.getHomeDirectory()

    suspend fun listWindowsDrives(): Flow<FileNode> = directoryManager.listWindowsDrives()

    suspend fun listDirectories(directory: String): List<FileNode> =
        directoryManager.listDirectories(directory)

    suspend fun searchDirectories(query: String, directory: String): List<String> =
        directoryManager.searchDirectories(query, directory)

    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> =
        directoryManager.createDirectory(parentDirectory, folderName)
}
