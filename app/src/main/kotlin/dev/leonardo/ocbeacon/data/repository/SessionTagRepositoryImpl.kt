package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.SessionTagRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SessionTagRepository] 的实现——一行委托 [SessionTagStore]
 * （C5 拆分前这些方法在 SettingsRepositoryImpl/SettingsDataStore 上）。
 */
@Singleton
class SessionTagRepositoryImpl @Inject constructor(
    private val tagStore: SessionTagStore,
) : SessionTagRepository {

    override fun sessionTags(serverId: String): Flow<List<Tag>> = tagStore.sessionTags(serverId)

    override fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
        tagStore.sessionTagAssignments(serverId)

    override suspend fun addSessionTag(serverId: String, tag: Tag) = tagStore.addSessionTag(serverId, tag)

    override suspend fun updateSessionTag(serverId: String, tag: Tag) = tagStore.updateSessionTag(serverId, tag)

    override suspend fun removeSessionTag(serverId: String, tagId: String) = tagStore.removeSessionTag(serverId, tagId)

    override suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) =
        tagStore.setSessionTags(serverId, sessionId, tagIds)

    override suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) =
        tagStore.removeSessionTagAssignment(serverId, sessionId, tagId)

    override fun favoriteSessionIds(serverId: String): Flow<Set<String>> =
        tagStore.favoriteSessionIds(serverId)

    override suspend fun toggleFavorite(serverId: String, sessionId: String) =
        tagStore.toggleFavorite(serverId, sessionId)

    // #137（D2-L59）：收藏迁移显式化（原藏在 favoriteSessionIds flow map 内的隐蔽副作用）
    override suspend fun migrateLegacyFavoritesIfNeeded(serverId: String) =
        tagStore.migrateLegacyFavoritesIfNeeded(serverId)
}
