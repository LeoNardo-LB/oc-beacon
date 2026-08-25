package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * 应用设置的领域层接口。
 * 实现归属：由 data 层实现（domain 层仅声明契约）。
 *
 * C5 存储归属拆分（2026-08-26）：会话标签方法移至 [SessionTagRepository]；
 * 未读已读方法随 UnreadStateStore 落在 data 层内部（消费方 UnreadBadgeService/
 * EventDispatcher 均 data/service 内部，无 UI 直连，不设透传接口）。
 */
interface SettingsRepository {

    /**
     * 观察聚合后的应用设置。
     */
    fun getSettingsFlow(): Flow<AppSettings>

    /**
     * 更新应用设置。
     */
    suspend fun updateSettings(settings: AppSettings): Result<Unit>

    /**
     * 观察某台服务器的隐藏 model 键集合。
     * 键格式："providerId:modelId"。
     */
    fun hiddenModels(serverId: String): Flow<Set<String>>

    /**
     * 设置某个 model 的可见性。
     * @param visible false 时将该 model 加入隐藏集合。
     */
    suspend fun setModelVisibility(serverId: String, providerId: String, modelId: String, visible: Boolean)

    /** 2026-08-16（方案 A·默认模型）：某服务器的本地默认模型（null=未设）。
     *  格式 "providerId|modelId|variant"，由调用方编解码。 */
    fun defaultModel(serverId: String): Flow<String?>

    /** 设置/清除默认模型（value=null 清除）。 */
    suspend fun setDefaultModel(serverId: String, value: String?)

    // ============ service 层单键读取（C5：service 直注具体类改经本接口，缺的方法补齐） ============

    /** 是否启用轮次完成通知。默认：true。（OpenCodeConnectionService / AppNotificationManager） */
    fun notificationsEnabled(): Flow<Boolean>

    /** 通知是否静默（无声音/振动）。默认：false。（同上 + 提示音策略镜像） */
    fun silentNotifications(): Flow<Boolean>

    /** 是否自动批准权限请求。默认：false。（OpenCodeConnectionService） */
    fun autoAllowPermissions(): Flow<Boolean>

    /** 重连模式："aggressive"/"normal"/"conservative"。默认："normal"。（SseConnectionManager） */
    fun reconnectMode(): Flow<String>
}
