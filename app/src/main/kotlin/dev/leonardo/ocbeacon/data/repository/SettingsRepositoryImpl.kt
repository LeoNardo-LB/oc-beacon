package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
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
 * C5 存储归属拆分（2026-08-26）：会话标签委托移至 SessionTagRepositoryImpl；
 * 未读已读方法随 UnreadStateStore 落在 data 层内部（消费方直注 store）。
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

    // ============ service 层单键读取（C5：service 直注具体类改经本接口） ============

    override fun notificationsEnabled(): Flow<Boolean> = dataRepo.notificationsEnabled

    override fun silentNotifications(): Flow<Boolean> = dataRepo.silentNotifications

    override fun autoAllowPermissions(): Flow<Boolean> = dataRepo.autoAllowPermissions

    override fun reconnectMode(): Flow<String> = dataRepo.reconnectMode

    override suspend fun updateSettings(settings: AppSettings): Result<Unit> = runCatchingCancellable {
        // #134（D2-L57）：单次 DataStore edit 原子落盘（原 21 次独立 edit——
        // 中途崩溃留下半套设置，且每次 edit 全文件重写）
        dataRepo.updateAll(settings)
    }
}
