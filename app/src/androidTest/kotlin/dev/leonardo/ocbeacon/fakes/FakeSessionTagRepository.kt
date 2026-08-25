package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.SessionTagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

/** C5 拆分：标签/收藏 Fake 自 FakeSettingsRepository 迁移（实现体不变）。 */
@Singleton
class FakeSessionTagRepository @Inject constructor() : SessionTagRepository {

    val sessionTagsState = MutableStateFlow<List<Tag>>(emptyList())
    val tagAssignmentsState = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val favoriteSessionIdsState = MutableStateFlow<Set<String>>(emptySet())

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

    override suspend fun migrateLegacyFavoritesIfNeeded(serverId: String) = Unit
}
