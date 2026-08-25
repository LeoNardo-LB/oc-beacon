package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

/**
 * C5 存储归属拆分（2026-08-26）：标签/收藏方法移至 [FakeSessionTagRepository]；
 * 未读已读方法随 UnreadStateStore 落在 data 层（测试图用真实实例）。
 */
@Singleton
class FakeSettingsRepository @Inject constructor() : SettingsRepository {

    val settingsState = MutableStateFlow(AppSettings())
    val hiddenModelsState = MutableStateFlow<Set<String>>(emptySet())
    var updateSettingsResult: Result<Unit> = Result.success(Unit)

    // service 层单键读取（C5 接口新增成员的 Fake 实现）
    val notificationsEnabledState = MutableStateFlow(true)
    val silentNotificationsState = MutableStateFlow(false)
    val autoAllowPermissionsState = MutableStateFlow(false)
    val reconnectModeState = MutableStateFlow("normal")

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

    override fun notificationsEnabled(): Flow<Boolean> = notificationsEnabledState

    override fun silentNotifications(): Flow<Boolean> = silentNotificationsState

    override fun autoAllowPermissions(): Flow<Boolean> = autoAllowPermissionsState

    override fun reconnectMode(): Flow<String> = reconnectModeState
}
