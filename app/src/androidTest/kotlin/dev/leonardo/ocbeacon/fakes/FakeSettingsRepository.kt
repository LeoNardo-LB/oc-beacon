package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

@Singleton
class FakeSettingsRepository @Inject constructor() : SettingsRepository {

    val settingsState = MutableStateFlow(AppSettings())
    val hiddenModelsState = MutableStateFlow<Set<String>>(emptySet())
    var updateSettingsResult: Result<Unit> = Result.success(Unit)

    val sessionCategoriesState = MutableStateFlow<List<SessionCategory>>(emptyList())
    val categoryAssignmentsState = MutableStateFlow<Map<String, String>>(emptyMap())
    val favoriteSessionIdsState = MutableStateFlow<Set<String>>(emptySet())
    val crossServerFavoriteOrderState = MutableStateFlow<List<String>>(emptyList())
    val favoriteSnapshotsState = MutableStateFlow<Map<String, FavoriteSessionSnapshot>>(emptyMap())

    override fun getSettingsFlow(): Flow<AppSettings> = settingsState

    override suspend fun updateSettings(settings: AppSettings): Result<Unit> {
        settingsState.value = settings
        return updateSettingsResult
    }

    override fun hiddenModels(serverId: String): Flow<Set<String>> = hiddenModelsState

    override fun sessionCategories(): Flow<List<SessionCategory>> = sessionCategoriesState

    override fun sessionCategoryAssignments(serverId: String): Flow<Map<String, String>> = categoryAssignmentsState

    override suspend fun addSessionCategory(category: SessionCategory) {
        sessionCategoriesState.value = sessionCategoriesState.value.filterNot { it.id == category.id } + category
    }

    override suspend fun removeSessionCategory(categoryId: String) {
        sessionCategoriesState.value = sessionCategoriesState.value.filterNot { it.id == categoryId }
    }

    override suspend fun assignSessionCategory(serverId: String, sessionId: String, categoryId: String) {
        categoryAssignmentsState.value = categoryAssignmentsState.value + (sessionId to categoryId)
    }

    override suspend fun unassignSessionCategory(serverId: String, sessionId: String) {
        categoryAssignmentsState.value = categoryAssignmentsState.value - sessionId
    }

    override fun favoriteSessionIds(serverId: String): Flow<Set<String>> = favoriteSessionIdsState

    override val crossServerFavoriteOrder: Flow<List<String>> = crossServerFavoriteOrderState

    override val favoriteSessionSnapshots: Flow<Map<String, FavoriteSessionSnapshot>> = favoriteSnapshotsState

    override suspend fun addFavoriteSession(serverId: String, sessionId: String, snapshot: FavoriteSessionSnapshot) {
        val key = "$serverId:$sessionId"
        favoriteSessionIdsState.value = favoriteSessionIdsState.value + sessionId
        favoriteSnapshotsState.value = favoriteSnapshotsState.value + (key to snapshot)
        crossServerFavoriteOrderState.value = crossServerFavoriteOrderState.value + key
    }

    override suspend fun removeFavoriteSession(serverId: String, sessionId: String) {
        val key = "$serverId:$sessionId"
        favoriteSessionIdsState.value = favoriteSessionIdsState.value - sessionId
        favoriteSnapshotsState.value = favoriteSnapshotsState.value - key
        crossServerFavoriteOrderState.value = crossServerFavoriteOrderState.value - key
    }

    override suspend fun setCrossServerFavoriteOrder(order: List<String>) {
        crossServerFavoriteOrderState.value = order
    }

    override suspend fun setCrossServerFavoriteOrderItem(key: String, favorite: Boolean) {
        crossServerFavoriteOrderState.value = if (favorite) {
            if (key in crossServerFavoriteOrderState.value) crossServerFavoriteOrderState.value
            else crossServerFavoriteOrderState.value + key
        } else {
            crossServerFavoriteOrderState.value - key
        }
    }

    override suspend fun saveFavoriteSessionSnapshot(serverId: String, sessionId: String, snapshot: FavoriteSessionSnapshot) {
        favoriteSnapshotsState.value = favoriteSnapshotsState.value + ("$serverId:$sessionId" to snapshot)
    }

    override suspend fun clearFavoriteSessionSnapshot(serverId: String, sessionId: String) {
        favoriteSnapshotsState.value = favoriteSnapshotsState.value - "$serverId:$sessionId"
    }
}
