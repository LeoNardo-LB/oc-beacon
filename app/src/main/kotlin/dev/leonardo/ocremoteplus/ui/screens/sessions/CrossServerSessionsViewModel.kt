package dev.leonardo.ocremoteplus.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocremoteplus.data.repository.CrossServerSessionsAggregator
import dev.leonardo.ocremoteplus.data.repository.ServerDataStore
import dev.leonardo.ocremoteplus.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocremoteplus.domain.model.Session
import dev.leonardo.ocremoteplus.domain.model.SessionCategory
import dev.leonardo.ocremoteplus.domain.model.ServerConfig
import dev.leonardo.ocremoteplus.domain.model.favoriteKey
import dev.leonardo.ocremoteplus.domain.repository.SettingsRepository
import dev.leonardo.ocremoteplus.service.SseConnectionManager
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
 * A single row in the cross-server favorites list.
 *
 * [session] is the live [Session] when its server is connected and the session is present in the
 * server's session list; `null` otherwise (server offline or session no longer listed), in which
 * case [snapshot] provides the offline display data.
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
    /** Categories that actually appear among the visible favorites (for the filter row). */
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

    /** Per-server favorites + category assignments, aggregated across all known servers. */
    private val preferencesByServer: Flow<Map<String, ServerSessionPreferences>> =
        serverDataStore.servers.flatMapLatest { servers ->
            if (servers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    servers.map { server ->
                        combine(
                            settingsRepository.favoriteSessionIds(server.id),
                            settingsRepository.sessionCategoryAssignments(server.id),
                        ) { favoriteIds, assignments ->
                            server.id to ServerSessionPreferences(favoriteIds, assignments)
                        }
                    },
                ) { values -> values.toMap() }
            }
        }

    private val favoritesMeta: Flow<FavoritesMeta> = combine(
        settingsRepository.crossServerFavoriteOrder,
        settingsRepository.favoriteSessionSnapshots,
        settingsRepository.sessionCategories(),
    ) { order, snapshots, categories -> FavoritesMeta(order, snapshots, categories) }

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

    fun toggleFavorite(item: CrossServerSessionItem) {
        viewModelScope.launch {
            if (item.isFavorite) {
                settingsRepository.removeFavoriteSession(item.serverId, item.sessionId)
                settingsRepository.setCrossServerFavoriteOrderItem(
                    favoriteKey(item.serverId, item.sessionId),
                    favorite = false,
                )
            } else {
                val snapshot = item.session?.let(FavoriteSessionSnapshot::from)
                    ?: item.snapshot
                    ?: FavoriteSessionSnapshot(item.sessionId, item.sessionId, 0, 0)
                settingsRepository.addFavoriteSession(item.serverId, item.sessionId, snapshot)
                settingsRepository.setCrossServerFavoriteOrderItem(
                    favoriteKey(item.serverId, item.sessionId),
                    favorite = true,
                )
            }
        }
    }

    fun moveFavorite(item: CrossServerSessionItem, visibleItems: List<CrossServerSessionItem>, offset: Int) {
        if (offset == 0) return
        viewModelScope.launch {
            val currentOrder = settingsRepository.crossServerFavoriteOrder.first()
            val mergedOrder = moveCrossServerFavoriteOrder(
                currentOrder = currentOrder,
                visibleOrder = visibleItems.map { favoriteKey(it.serverId, it.sessionId) },
                itemKey = favoriteKey(item.serverId, item.sessionId),
                offset = offset,
            )
            if (mergedOrder != currentOrder) {
                settingsRepository.setCrossServerFavoriteOrder(mergedOrder)
            }
        }
    }

    fun setSessionCategory(item: CrossServerSessionItem, categoryId: String?) {
        viewModelScope.launch {
            if (categoryId == null) {
                settingsRepository.unassignSessionCategory(item.serverId, item.sessionId)
            } else {
                settingsRepository.assignSessionCategory(item.serverId, item.sessionId, categoryId)
            }
        }
    }

    fun saveSessionCategory(id: String?, name: String, color: String, icon: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.addSessionCategory(
                SessionCategory(
                    id = id ?: UUID.randomUUID().toString(),
                    name = trimmed,
                    color = color,
                    icon = icon,
                ),
            )
        }
    }

    fun deleteSessionCategory(categoryId: String) {
        viewModelScope.launch { settingsRepository.removeSessionCategory(categoryId) }
    }
}

/**
 * Pure, testable builder for [CrossServerSessionsUiState].
 *
 * Iterates every known server's favorites, resolves each favorite to a live [Session] when its
 * server is connected (falling back to [FavoriteSessionSnapshot] when offline), then sorts by the
 * stored cross-server order with stable tie-breakers.
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

/** Sort favorites only, respecting the stored favorite index then most-recently-updated. */
internal fun sortCrossServerFavorites(items: List<CrossServerSessionItem>): List<CrossServerSessionItem> =
    items.filter { it.isFavorite }.sortedWith(
        compareBy<CrossServerSessionItem> { it.favoriteIndex }
            .thenByDescending { it.displayUpdated() },
    )

/** Filter favorites by category (null = all). */
internal fun filterCrossServerFavorites(
    items: List<CrossServerSessionItem>,
    categoryId: String?,
): List<CrossServerSessionItem> = sortCrossServerFavorites(items).filter { item ->
    categoryId == null || item.category?.id == categoryId
}

/**
 * Reorder a favorite within the visible list while preserving the positions of disconnected-server
 * favorites that are not currently visible.
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
