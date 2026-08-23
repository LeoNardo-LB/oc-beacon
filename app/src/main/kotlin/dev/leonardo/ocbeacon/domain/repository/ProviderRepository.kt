package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.GlobalConfig
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch
import dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod
import dev.leonardo.ocbeacon.domain.model.ProviderConnectionStatus
import dev.leonardo.ocbeacon.domain.model.ProviderInfo
import dev.leonardo.ocbeacon.domain.model.ProviderOauthAuthorization
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse

/**
 * 已连接服务器的 Provider/model 管理。
 */
interface ProviderRepository {
    suspend fun loadProviders(serverId: String): Result<List<ProviderInfo>>
    suspend fun loadProviderCatalog(serverId: String): Result<ProvidersResponse>
    suspend fun connectProviderApi(serverId: String, providerId: String, apiKey: String): Result<Unit>
    suspend fun disconnectProvider(serverId: String, providerId: String): Result<Unit>

    /**
     * 获取带连接状态的 provider 目录（GET /provider）。
     */
    suspend fun loadProviderConnectionStatus(serverId: String): Result<ProviderConnectionStatus>

    /**
     * 获取全局服务器配置（GET /global/config）。
     */
    suspend fun getGlobalConfig(serverId: String): Result<GlobalConfig>

    /**
     * 修补全局服务器配置（PATCH /global/config）。
     * 返回更新后的配置。
     */
    suspend fun updateGlobalConfig(serverId: String, patch: GlobalConfigPatch): Result<GlobalConfig>

    /**
     * 获取各 provider 支持的认证方法（GET /provider/auth）。
     */
    suspend fun getProviderAuthMethods(serverId: String): Result<Map<String, List<ProviderAuthMethod>>>

    /**
     * 启动 provider 的 OAuth 授权流程。
     * 返回 null 表示该 provider 不支持 OAuth。
     */
    suspend fun authorizeProviderOauth(
        serverId: String,
        providerId: String,
        methodIndex: Int
    ): Result<ProviderOauthAuthorization?>

    /**
     * 完成 provider 的 OAuth 授权回调。
     * 返回是否成功。
     */
    suspend fun completeProviderOauth(
        serverId: String,
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Result<Boolean>

    /**
     * 移除 provider 已存储的认证（DELETE /auth/{providerId}）。
     * 返回是否成功。
     */
    suspend fun removeProviderCredential(serverId: String, providerId: String): Result<Boolean>

    /**
     * 销毁全局实例并强制刷新 provider/认证状态（POST /global/dispose）。
     * 返回是否成功。
     */
    suspend fun disposeGlobal(serverId: String): Result<Boolean>
}
