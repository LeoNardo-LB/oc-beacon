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
    val sessionReadTimesState = MutableStateFlow<Map<String, Long>>(emptyMap())
    val allReadAtState = MutableStateFlow(0L)

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

    // 2026-08-16（方案 A·默认模型）：接口新增成员的 Fake 实现
    private val defaultModelFlow = MutableStateFlow<String?>(null)
    override fun defaultModel(serverId: String): Flow<String?> = defaultModelFlow
    override suspend fun setDefaultModel(serverId: String, value: String?) {
        defaultModelFlow.value = value
    }

    // 2026-08-16（androidTest 源集修复）：接口既有成员的缺失实现
    override suspend fun migrateLegacyFavoritesIfNeeded(serverId: String) = Unit

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

    // ============ 会话已读（未读提示） ============

    override fun sessionReadTimes(serverId: String): Flow<Map<String, Long>> = sessionReadTimesState

    override fun allReadAt(serverId: String): Flow<Long> = allReadAtState

    override suspend fun markAllSessionsRead(serverId: String, globalMax: Long) {
        allReadAtState.value = globalMax
    }

    override suspend fun markSessionRead(serverId: String, sessionId: String, completedTs: Long) {
        sessionReadTimesState.value = sessionReadTimesState.value + (sessionId to completedTs)
    }

    override suspend fun runUnreadStateV2Migration() {
        // noop — Fake 不持久化，无需迁移
    }
}
