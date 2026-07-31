package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import kotlinx.coroutines.flow.Flow

/**
 * Domain-layer interface for application settings.
 * Aligned with spec §4.1.1.
 * Implemented by the Data layer in Phase 3.
 */
interface SettingsRepository {

    /**
     * Observe the aggregated application settings.
     */
    fun getSettingsFlow(): Flow<AppSettings>

    /**
     * Update the application settings.
     */
    suspend fun updateSettings(settings: AppSettings): Result<Unit>

    /**
     * Observe the set of hidden model keys for a server.
     * Key format: "providerId:modelId".
     */
    fun hiddenModels(serverId: String): Flow<Set<String>>

    /**
     * Global list of user-defined session categories.
     */
    fun sessionCategories(): Flow<List<SessionCategory>>

    /**
     * Per-server session id → category id assignments.
     */
    fun sessionCategoryAssignments(serverId: String): Flow<Map<String, String>>

    /** Add or replace a category. */
    suspend fun addSessionCategory(category: SessionCategory)

    /** Remove a category by id. */
    suspend fun removeSessionCategory(categoryId: String)

    /** Assign a session to a category for the given server. */
    suspend fun assignSessionCategory(serverId: String, sessionId: String, categoryId: String)

    /** Remove a session's category assignment for the given server. */
    suspend fun unassignSessionCategory(serverId: String, sessionId: String)

    // ============ Cross-server session favorites ============

    /** Favorite session ids for a specific server. */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>>

    /** Global cross-server favorite order — list of "serverId:sessionId" keys. */
    val crossServerFavoriteOrder: Flow<List<String>>

    /** Offline snapshots keyed by "serverId:sessionId". */
    val favoriteSessionSnapshots: Flow<Map<String, FavoriteSessionSnapshot>>

    /** Add a session to favorites for a server, persisting its offline snapshot. */
    suspend fun addFavoriteSession(serverId: String, sessionId: String, snapshot: FavoriteSessionSnapshot)

    /** Remove a session from favorites for a server, clearing its snapshot. */
    suspend fun removeFavoriteSession(serverId: String, sessionId: String)

    /** Replace the entire cross-server favorite order. */
    suspend fun setCrossServerFavoriteOrder(order: List<String>)

    /** Upsert or remove a single favorite key in the cross-server order list. */
    suspend fun setCrossServerFavoriteOrderItem(key: String, favorite: Boolean)

    /** Save or replace a snapshot for a (server, session) pair. */
    suspend fun saveFavoriteSessionSnapshot(serverId: String, sessionId: String, snapshot: FavoriteSessionSnapshot)

    /** Clear a snapshot for a (server, session) pair. */
    suspend fun clearFavoriteSessionSnapshot(serverId: String, sessionId: String)
}
