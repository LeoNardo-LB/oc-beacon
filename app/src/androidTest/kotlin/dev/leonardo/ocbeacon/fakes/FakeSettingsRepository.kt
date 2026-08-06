package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

@Singleton
class FakeSettingsRepository @Inject constructor() : SettingsRepository {

    val settingsState = MutableStateFlow(AppSettings())
    val hiddenModelsState = MutableStateFlow<Set<String>>(emptySet())
    var updateSettingsResult: Result<Unit> = Result.success(Unit)

    val sessionTagsState = MutableStateFlow<List<Tag>>(emptyList())
    val tagAssignmentsState = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val favoriteSessionIdsState = MutableStateFlow<Set<String>>(emptySet())

    override fun getSettingsFlow(): Flow<AppSettings> = settingsState

    override suspend fun updateSettings(settings: AppSettings): Result<Unit> {
        settingsState.value = settings
        return updateSettingsResult
    }

    override fun hiddenModels(serverId: String): Flow<Set<String>> = hiddenModelsState

    override suspend fun setModelVisibility(serverId: String, providerId: String, modelId: String, visible: Boolean) {
        val key = "$providerId:$modelId"
        hiddenModelsState.value = if (visible) hiddenModelsState.value - key else hiddenModelsState.value + key
    }

    override fun sessionTags(serverId: String): Flow<List<Tag>> = sessionTagsState

    override fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> = tagAssignmentsState

    override suspend fun addSessionTag(serverId: String, tag: Tag) {
        sessionTagsState.value = sessionTagsState.value.filterNot { it.id == tag.id } + tag
    }

    override suspend fun updateSessionTag(serverId: String, tag: Tag) {
        sessionTagsState.value = sessionTagsState.value.map { if (it.id == tag.id) tag else it }
    }

    override suspend fun removeSessionTag(serverId: String, tagId: String) {
        sessionTagsState.value = sessionTagsState.value.filterNot { it.id == tagId }
    }

    override suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) {
        tagAssignmentsState.value = tagAssignmentsState.value + (sessionId to tagIds.toList())
    }

    override suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) {
        val current = tagAssignmentsState.value
        val updated = current[sessionId]?.filterNot { it == tagId } ?: return
        tagAssignmentsState.value = if (updated.isEmpty()) current - sessionId else current + (sessionId to updated)
    }

    override fun favoriteSessionIds(serverId: String): Flow<Set<String>> = favoriteSessionIdsState

    override suspend fun toggleFavorite(serverId: String, sessionId: String) {
        favoriteSessionIdsState.value = if (sessionId in favoriteSessionIdsState.value) {
            favoriteSessionIdsState.value - sessionId
        } else {
            favoriteSessionIdsState.value + sessionId
        }
    }
}
