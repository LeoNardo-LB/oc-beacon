package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

/**
 * [SettingsRepository] 的实现。
 * 包装现有的基于 DataStore 的设置 repository，
 * 委托给其原子的 [dev.leonardo.ocbeacon.data.repository.SettingsDataStore.appSettingsFlow]。
 *
 * 阶段 3：已编译但尚未接入 UseCase。阶段 4 将把
 * SettingsViewModel 的直接调用迁移为通过此 repository。
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataRepo: dev.leonardo.ocbeacon.data.repository.SettingsDataStore
) : SettingsRepository {

    override fun getSettingsFlow(): Flow<AppSettings> = dataRepo.appSettingsFlow

    override fun hiddenModels(serverId: String): Flow<Set<String>> = dataRepo.hiddenModels(serverId)

    override suspend fun setModelVisibility(serverId: String, providerId: String, modelId: String, visible: Boolean) =
        dataRepo.setModelVisibility(serverId, providerId, modelId, visible)

    // 2026-08-16（方案 A·默认模型）
    override fun defaultModel(serverId: String): Flow<String?> = dataRepo.defaultModel(serverId)

    override suspend fun setDefaultModel(serverId: String, value: String?) =
        dataRepo.setDefaultModel(serverId, value)

    // ============ 会话标签 ============

    override fun sessionTags(serverId: String): Flow<List<Tag>> = dataRepo.sessionTags(serverId)

    override fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
        dataRepo.sessionTagAssignments(serverId)

    override suspend fun addSessionTag(serverId: String, tag: Tag) = dataRepo.addSessionTag(serverId, tag)

    override suspend fun updateSessionTag(serverId: String, tag: Tag) = dataRepo.updateSessionTag(serverId, tag)

    override suspend fun removeSessionTag(serverId: String, tagId: String) = dataRepo.removeSessionTag(serverId, tagId)

    override suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) =
        dataRepo.setSessionTags(serverId, sessionId, tagIds)

    override suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) =
        dataRepo.removeSessionTagAssignment(serverId, sessionId, tagId)

    // ============ 会话收藏（基于内置收藏标签派生） ============

    override fun favoriteSessionIds(serverId: String): Flow<Set<String>> =
        dataRepo.favoriteSessionIds(serverId)

    override suspend fun toggleFavorite(serverId: String, sessionId: String) =
        dataRepo.toggleFavorite(serverId, sessionId)

    // #137（D2-L59）：收藏迁移显式化（原藏在 favoriteSessionIds flow map 内的隐蔽副作用）
    override suspend fun migrateLegacyFavoritesIfNeeded(serverId: String) =
        dataRepo.migrateLegacyFavoritesIfNeeded(serverId)

    // ============ 会话已读（未读提示） ============

    override fun sessionReadTimes(serverId: String): Flow<Map<String, Long>> =
        dataRepo.sessionReadTimes(serverId)

    override fun allReadAt(serverId: String): Flow<Long> =
        dataRepo.allReadAt(serverId)

    override suspend fun markAllSessionsRead(serverId: String, globalMax: Long) =
        dataRepo.markAllSessionsRead(serverId, globalMax)

    override suspend fun markSessionRead(serverId: String, sessionId: String, completedTs: Long) =
        dataRepo.markSessionRead(serverId, sessionId, completedTs)

    override suspend fun runUnreadStateV2Migration() =
        dataRepo.runUnreadStateV2Migration()

    override suspend fun updateSettings(settings: AppSettings): Result<Unit> = runCatchingCancellable {
        // #134（D2-L57）：单次 DataStore edit 原子落盘（原 21 次独立 edit——
        // 中途崩溃留下半套设置，且每次 edit 全文件重写）
        dataRepo.updateAll(settings)
    }
}
