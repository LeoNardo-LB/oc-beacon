package dev.leonardo.octether.data.repository

import dev.leonardo.octether.domain.model.AppSettings
import dev.leonardo.octether.domain.model.FavoriteSessionSnapshot
import dev.leonardo.octether.domain.model.SessionCategory
import dev.leonardo.octether.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SettingsRepository].
 * Wraps the existing DataStore-based settings repository,
 * delegating to its atomic [dev.leonardo.octether.data.repository.SettingsDataStore.appSettingsFlow].
 *
 * Phase 3: compiled but not yet wired to UseCases. Phase 4 will migrate
 * SettingsViewModel direct calls to go through this repository.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataRepo: dev.leonardo.octether.data.repository.SettingsDataStore
) : SettingsRepository {

    override fun getSettingsFlow(): Flow<AppSettings> = dataRepo.appSettingsFlow

    override fun hiddenModels(serverId: String): Flow<Set<String>> = dataRepo.hiddenModels(serverId)

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

    // ============ Cross-server session favorites ============

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
        dataRepo.setCodeWordWrap(settings.codeWordWrap)
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
        dataRepo.setShowLocalRuntime(settings.showLocalRuntime)
        dataRepo.setLocalSetupCompleted(settings.localSetupCompleted)
        dataRepo.setLocalProxyEnabled(settings.localProxyEnabled)
        dataRepo.setLocalProxyUrl(settings.localProxyUrl)
        dataRepo.setLocalProxyNoProxy(settings.localProxyNoProxy)
        dataRepo.setLocalServerAllowLan(settings.localServerAllowLan)
        dataRepo.setLocalServerUsername(settings.localServerUsername)
        dataRepo.setLocalServerPassword(settings.localServerPassword)
        dataRepo.setLocalServerRunInBackground(settings.localServerRunInBackground)
        dataRepo.setLocalServerAutoStart(settings.localServerAutoStart)
        dataRepo.setLocalServerStartupTimeoutSec(settings.localServerStartupTimeoutSec)
    }
}
