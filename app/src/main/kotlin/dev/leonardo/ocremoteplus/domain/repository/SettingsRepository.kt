package dev.leonardo.ocremoteplus.domain.repository

import dev.leonardo.ocremoteplus.domain.model.AppSettings
import dev.leonardo.ocremoteplus.domain.model.SessionCategory
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
}
