package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

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

    override fun sessionCategories(): Flow<List<SessionCategory>> = dataRepo.sessionCategories

    override fun sessionCategoryAssignments(serverId: String): Flow<Map<String, String>> =
        dataRepo.sessionCategoryAssignments(serverId)

    override suspend fun addSessionCategory(category: SessionCategory) =
        dataRepo.addSessionCategory(category)

    override suspend fun removeSessionCategory(categoryId: String) =
        dataRepo.removeSessionCategory(categoryId)

    override suspend fun assignSessionCategory(serverId: String, sessionId: String, categoryId: String) =
        dataRepo.assignSessionCategory(serverId, sessionId, categoryId)

    override suspend fun unassignSessionCategory(serverId: String, sessionId: String) =
        dataRepo.unassignSessionCategory(serverId, sessionId)

    // ============ 跨服务器会话收藏 ============

    override fun favoriteSessionIds(serverId: String): Flow<Set<String>> =
        dataRepo.favoriteSessionIds(serverId)

    override val crossServerFavoriteOrder: Flow<List<String>>
        get() = dataRepo.crossServerFavoriteOrder

    override val favoriteSessionSnapshots: Flow<Map<String, FavoriteSessionSnapshot>>
        get() = dataRepo.favoriteSessionSnapshots

    override suspend fun addFavoriteSession(
        serverId: String,
        sessionId: String,
        snapshot: FavoriteSessionSnapshot,
    ) = dataRepo.addFavoriteSession(serverId, sessionId, snapshot)

    override suspend fun removeFavoriteSession(serverId: String, sessionId: String) =
        dataRepo.removeFavoriteSession(serverId, sessionId)

    override suspend fun setCrossServerFavoriteOrder(order: List<String>) =
        dataRepo.setCrossServerFavoriteOrder(order)

    override suspend fun setCrossServerFavoriteOrderItem(key: String, favorite: Boolean) =
        dataRepo.setCrossServerFavoriteOrderItem(key, favorite)

    override suspend fun saveFavoriteSessionSnapshot(
        serverId: String,
        sessionId: String,
        snapshot: FavoriteSessionSnapshot,
    ) = dataRepo.saveFavoriteSessionSnapshot(serverId, sessionId, snapshot)

    override suspend fun clearFavoriteSessionSnapshot(serverId: String, sessionId: String) =
        dataRepo.clearFavoriteSessionSnapshot(serverId, sessionId)

    override suspend fun updateSettings(settings: AppSettings): Result<Unit> = runCatching {
        dataRepo.setAppLanguage(settings.appLanguage)
        dataRepo.setAppTheme(settings.appTheme)
        dataRepo.setDynamicColor(settings.dynamicColor)
        dataRepo.setAmoledDark(settings.amoledDark)
        dataRepo.setChatFontSize(settings.chatFontSize)
        dataRepo.setChatDensity(settings.chatDensity)
        dataRepo.setInitialMessageCount(settings.initialMessageCount)
        dataRepo.setConfirmBeforeSend(settings.confirmBeforeSend)
        dataRepo.setCompactMessages(settings.compactMessages)
        dataRepo.setCollapseTools(settings.collapseTools)
        dataRepo.setExpandReasoning(settings.expandReasoning)
        dataRepo.setShowTurnDividers(settings.showTurnDividers)
        dataRepo.setNotificationsEnabled(settings.notificationsEnabled)
        dataRepo.setSilentNotifications(settings.silentNotifications)
        dataRepo.setHapticFeedback(settings.hapticFeedback)
        dataRepo.setReconnectMode(settings.reconnectMode)
        dataRepo.setKeepScreenOn(settings.keepScreenOn)
        dataRepo.setCompressImageAttachments(settings.compressImageAttachments)
        dataRepo.setImageAttachmentMaxLongSide(settings.imageAttachmentMaxLongSide)
        dataRepo.setImageAttachmentWebpQuality(settings.imageAttachmentWebpQuality)
        dataRepo.setTerminalFontSize(settings.terminalFontSize)
    }
}
