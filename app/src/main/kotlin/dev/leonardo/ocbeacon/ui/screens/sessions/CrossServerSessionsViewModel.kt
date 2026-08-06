package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.repository.CrossServerSessionsAggregator
import dev.leonardo.ocbeacon.data.repository.ServerDataStore
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.model.favoriteKey
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.service.SseConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 跨服务器收藏列表中的单行。
 *
 * 当服务器已连接且会话存在于其会话列表中时，[session] 为活跃的 [Session]；
 * 否则为 `null`（服务器离线或会话已不再列出），此时由 [snapshot]
 * 提供离线显示数据。
 */
data class CrossServerSessionItem(
    val serverId: String,
    val serverName: String,
    val sessionId: String,
    val session: Session?,
    val snapshot: FavoriteSessionSnapshot?,
    val isConnected: Boolean,
    val category: SessionCategory?,
    val isFavorite: Boolean,
    val favoriteIndex: Int,
)

data class CrossServerSessionsUiState(
    val items: List<CrossServerSessionItem> = emptyList(),
    val categories: List<SessionCategory> = emptyList(),
    /** 在可见收藏中实际出现的分类（用于过滤行）。 */
    val filterCategories: List<SessionCategory> = emptyList(),
    val connectedServerCount: Int = 0,
)

internal data class ServerSessionPreferences(
    val favoriteIds: Set<String>,
    val categoryAssignments: Map<String, String>,
)

private data class FavoritesMeta(
    val order: List<String>,
    val snapshots: Map<String, FavoriteSessionSnapshot>,
    val categories: List<SessionCategory>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CrossServerSessionsViewModel @Inject constructor(
    private val aggregator: CrossServerSessionsAggregator,
    private val sseConnectionManager: SseConnectionManager,
    private val serverDataStore: ServerDataStore,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * 每个服务器的收藏 + 分类分配，聚合所有已知服务器。
     *
     * 注：分类分配已在 Task 2 移除；本 Flow 暂以空 Map 占位，
     * Task 7 整体重做此 ViewModel 时改为标签语义。
     */
    private val preferencesByServer: Flow<Map<String, ServerSessionPreferences>> =
        serverDataStore.servers.flatMapLatest { servers ->
            if (servers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    servers.map { server ->
                        combine(
                            settingsRepository.favoriteSessionIds(server.id),
                            flowOf<Map<String, String>>(emptyMap()),
                        ) { favoriteIds, assignments ->
                            server.id to ServerSessionPreferences(favoriteIds, assignments)
                        }
                    },
                ) { values -> values.toMap() }
            }
        }

    /**
     * Task 2 移除了 crossServerFavoriteOrder / favoriteSessionSnapshots / sessionCategories；
     * 本 Flow 暂以空 meta 占位，Task 7 整体重做。
     */
    private val favoritesMeta: Flow<FavoritesMeta> =
        flowOf(FavoritesMeta(order = emptyList(), snapshots = emptyMap(), categories = emptyList()))

    val uiState = combine(
        aggregator.crossServerSessions,
        sseConnectionManager.connectedServerIds,
        serverDataStore.servers,
        preferencesByServer,
        favoritesMeta,
    ) { sessionsByServer, connectedIds, servers, preferences, meta ->
        buildCrossServerSessionsState(
            sessionsByServer = sessionsByServer,
            connectedIds = connectedIds,
            servers = servers,
            preferences = preferences,
            order = meta.order,
            snapshots = meta.snapshots,
            categories = meta.categories,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CrossServerSessionsUiState(),
    )

    /** 切换 (serverId, sessionId) 的收藏状态（基于内置收藏标签）。 */
    fun toggleFavorite(item: CrossServerSessionItem) {
        viewModelScope.launch {
            settingsRepository.toggleFavorite(item.serverId, item.sessionId)
        }
    }

    /**
     * 跨服务器手动重排——Task 7 整体重做（原依赖已移除的 crossServerFavoriteOrder）。
     */
    fun moveFavorite(item: CrossServerSessionItem, visibleItems: List<CrossServerSessionItem>, offset: Int) {
        // 暂为 no-op：旧排序存储已移除，新顺序由 Task 7 引入。
    }

    /** 设置某会话的标签集（Task 7 重做 UI；过渡期把 categoryId 视为单标签 id）。 */
    fun setSessionCategory(item: CrossServerSessionItem, categoryId: String?) {
        viewModelScope.launch {
            if (categoryId == null) {
                settingsRepository.setSessionTags(item.serverId, item.sessionId, emptySet())
            } else {
                settingsRepository.setSessionTags(item.serverId, item.sessionId, setOf(categoryId))
            }
        }
    }

    /** 新建一个用户标签（按服务器划分；Task 7 重做 UI 时改名）。 */
    fun saveSessionCategory(id: String?, name: String, color: String, icon: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // serverId 在此 ViewModel 无单值上下文，用空串——Task 7 重做时按服务器划分。
            settingsRepository.addSessionTag(
                serverId = "",
                tag = Tag(
                    id = id ?: UUID.randomUUID().toString(),
                    name = trimmed,
                    color = color,
                    icon = icon,
                ),
            )
        }
    }

    /** 删除一个用户标签（Task 7 重做 UI 时改名）。 */
    fun deleteSessionCategory(categoryId: String) {
        viewModelScope.launch {
            // serverId 占位，Task 7 重做时按服务器划分。
            settingsRepository.removeSessionTag(serverId = "", tagId = categoryId)
        }
    }
}

/**
 * 纯净、可测试的 [CrossServerSessionsUiState] 构建器。
 *
 * 遍历每个已知服务器的收藏，在其服务器已连接时把每个收藏解析为活跃的 [Session]
 *（离线时回退到 [FavoriteSessionSnapshot]），然后按存储的跨服务器顺序排序，
 * 并使用稳定的兜底比较器。
 */
internal fun buildCrossServerSessionsState(
    sessionsByServer: Map<String, List<Session>>,
    connectedIds: Set<String>,
    servers: List<ServerConfig>,
    preferences: Map<String, ServerSessionPreferences>,
    order: List<String>,
    snapshots: Map<String, FavoriteSessionSnapshot>,
    categories: List<SessionCategory>,
): CrossServerSessionsUiState {
    val categoriesById = categories.associateBy(SessionCategory::id)
    val serverIndices = servers.withIndex().associate { it.value.id to it.index }
    val storedIndices = order.withIndex().associate { it.value to it.index }

    val rawItems = servers.flatMap { server ->
        val serverPrefs = preferences[server.id]
            ?: ServerSessionPreferences(emptySet(), emptyMap())
        val isConnected = server.id in connectedIds
        val liveById = if (isConnected) {
            sessionsByServer[server.id]?.associateBy(Session::id)
        } else {
            null
        }
        serverPrefs.favoriteIds.map { sessionId ->
            val key = favoriteKey(server.id, sessionId)
            val live = liveById?.get(sessionId)
            CrossServerSessionItem(
                serverId = server.id,
                serverName = server.displayName,
                sessionId = sessionId,
                session = live,
                snapshot = snapshots[key],
                isConnected = isConnected,
                category = serverPrefs.categoryAssignments[sessionId]?.let { categoriesById[it] },
                isFavorite = true,
                favoriteIndex = 0,
            )
        }
    }

    val ordered = rawItems.sortedWith(
        compareBy<CrossServerSessionItem> { storedIndices[favoriteKey(it.serverId, it.sessionId)] ?: Int.MAX_VALUE }
            .thenBy { serverIndices[it.serverId] ?: Int.MAX_VALUE }
            .thenByDescending { it.displayUpdated() },
    )
    val items = ordered.mapIndexed { index, item -> item.copy(favoriteIndex = index) }
    val visibleCategoryIds = items.mapNotNullTo(mutableSetOf()) { it.category?.id }

    return CrossServerSessionsUiState(
        items = items,
        categories = categories,
        filterCategories = categories.filter { it.id in visibleCategoryIds },
        connectedServerCount = connectedIds.size,
    )
}

/** 仅对收藏排序：先按存储的收藏索引，再按最近更新时间。 */
internal fun sortCrossServerFavorites(items: List<CrossServerSessionItem>): List<CrossServerSessionItem> =
    items.filter { it.isFavorite }.sortedWith(
        compareBy<CrossServerSessionItem> { it.favoriteIndex }
            .thenByDescending { it.displayUpdated() },
    )

/** 按分类过滤收藏（null = 全部）。 */
internal fun filterCrossServerFavorites(
    items: List<CrossServerSessionItem>,
    categoryId: String?,
): List<CrossServerSessionItem> = sortCrossServerFavorites(items).filter { item ->
    categoryId == null || item.category?.id == categoryId
}

/**
 * 在可见列表中重排某个收藏，同时保持当前不可见的离线服务器收藏的位置不变。
 */
internal fun moveCrossServerFavoriteOrder(
    currentOrder: List<String>,
    visibleOrder: List<String>,
    itemKey: String,
    offset: Int,
): List<String> {
    val from = visibleOrder.indexOf(itemKey)
    if (from < 0 || offset == 0) return currentOrder
    val to = (from + offset).coerceIn(0, visibleOrder.lastIndex)
    if (from == to) return currentOrder
    val reordered = visibleOrder.toMutableList()
    reordered[from] = reordered[to]
    reordered[to] = itemKey
    val visibleKeys = reordered.toSet()
    val existingVisibleCount = currentOrder.count(visibleKeys::contains)
    val visibleIterator = reordered.iterator()
    return currentOrder.map { key ->
        if (key in visibleKeys) visibleIterator.next() else key
    } + reordered.drop(existingVisibleCount)
}

private fun CrossServerSessionItem.displayUpdated(): Long =
    session?.time?.updated ?: snapshot?.updated ?: 0L
