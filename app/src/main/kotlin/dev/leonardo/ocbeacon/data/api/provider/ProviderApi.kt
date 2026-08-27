package dev.leonardo.ocbeacon.data.api.provider

import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import javax.inject.Inject
import javax.inject.Singleton

interface ProviderApi {
    /**
     * 获取可用的提供商和模型。
     * GET /config/providers
     */
    suspend fun getProviders(conn: ServerConnection): ProvidersResponse

    /**
     * 获取带连接状态的提供商目录。
     * GET /provider
     */
    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse

    /**
     * 获取可用的提供商认证方法。
     * GET /provider/auth
     */
    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>>

    /**
     * 启动提供商的 OAuth 授权。
     * POST /provider/{providerID}/oauth/authorize
     */
    suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization?

    /**
     * 完成提供商的 OAuth 授权。
     * POST /provider/{providerID}/oauth/callback
     */
    suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String? = null
    ): Boolean

    /**
     * 为提供商设置 API key 认证。
     * PUT /auth/{providerID}
     */
    suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean

    /**
     * 移除提供商已存储的认证。
     * DELETE /auth/{providerID}
     */
    suspend fun removeProviderCredential(conn: ServerConnection, providerId: String): Boolean

    /**
     * 获取当前服务器配置。
     * GET /config
     */
    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse

    /**
     * 获取全局服务器配置。
     * GET /global/config
     */
    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse

    /**
     * 修补服务器配置。
     * PATCH /config
     */
    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse

    /**
     * 修补全局服务器配置。
     * PATCH /global/config
     */
    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse

    /**
     * 销毁全局实例并强制刷新提供商/认证状态。
     * POST /global/dispose
     */
    suspend fun disposeGlobal(conn: ServerConnection): Boolean

    /**
     * 销毁当前实例。
     * POST /instance/dispose
     */
    suspend fun disposeInstance(conn: ServerConnection): Boolean
}

/**
 * C1-7（2026-08-27，#238 五域收编）：分发层收缩为单点路由 + 逐方法单行委托。
 * [V1ApiClient]/[V2ApiClient] 已直接实现 [ProviderApi]。
 */
@Singleton
class ProviderApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : ProviderApi {

    private fun pick(conn: ServerConnection): ProviderApi =
        if (conn.apiVersion.isV2) v2 else v1

    override suspend fun getProviders(conn: ServerConnection): ProvidersResponse =
        pick(conn).getProviders(conn)

    override suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse =
        pick(conn).listProviderCatalog(conn)

    override suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> =
        pick(conn).getProviderAuthMethods(conn)

    override suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? = pick(conn).authorizeProviderOauth(conn, providerId, methodIndex)

    override suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Boolean = pick(conn).completeProviderOauth(conn, providerId, methodIndex, code)

    override suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean =
        pick(conn).setProviderApiKey(conn, providerId, apiKey)

    override suspend fun removeProviderCredential(conn: ServerConnection, providerId: String): Boolean =
        pick(conn).removeProviderCredential(conn, providerId)

    override suspend fun getConfig(conn: ServerConnection): ServerConfigResponse =
        pick(conn).getConfig(conn)

    override suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse =
        pick(conn).getGlobalConfig(conn)

    override suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        pick(conn).updateConfig(conn, patch)

    override suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        pick(conn).updateGlobalConfig(conn, patch)

    override suspend fun disposeGlobal(conn: ServerConnection): Boolean =
        pick(conn).disposeGlobal(conn)

    override suspend fun disposeInstance(conn: ServerConnection): Boolean =
        pick(conn).disposeInstance(conn)
}
