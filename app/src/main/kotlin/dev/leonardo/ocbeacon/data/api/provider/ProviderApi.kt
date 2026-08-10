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
    suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean

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

@Singleton
class ProviderApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : ProviderApi {

    override suspend fun getProviders(conn: ServerConnection): ProvidersResponse =
        if (conn.apiVersion.isV2) v2.getProviders(conn) else v1.getProviders(conn)

    override suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse =
        if (conn.apiVersion.isV2) v2.listProviderCatalog(conn) else v1.listProviderCatalog(conn)

    override suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> =
        if (conn.apiVersion.isV2) v2.getProviderAuthMethods(conn) else v1.getProviderAuthMethods(conn)

    override suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? =
        if (conn.apiVersion.isV2) v2.authorizeProviderOauth(conn, providerId, methodIndex)
        else v1.authorizeProviderOauth(conn, providerId, methodIndex)

    override suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Boolean =
        if (conn.apiVersion.isV2) v2.completeProviderOauth(conn, providerId, methodIndex, code)
        else v1.completeProviderOauth(conn, providerId, methodIndex, code)

    override suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean =
        if (conn.apiVersion.isV2) v2.setProviderApiKey(conn, providerId, apiKey)
        else v1.setProviderApiKey(conn, providerId, apiKey)

    override suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean =
        if (conn.apiVersion.isV2) v2.removeProviderAuth(conn, providerId) else v1.removeProviderAuth(conn, providerId)

    override suspend fun getConfig(conn: ServerConnection): ServerConfigResponse =
        if (conn.apiVersion.isV2) v2.getConfig(conn) else v1.getConfig(conn)

    override suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse =
        if (conn.apiVersion.isV2) v2.getGlobalConfig(conn) else v1.getGlobalConfig(conn)

    override suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        if (conn.apiVersion.isV2) v2.updateConfig(conn, patch) else v1.updateConfig(conn, patch)

    override suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse =
        if (conn.apiVersion.isV2) v2.updateGlobalConfig(conn, patch) else v1.updateGlobalConfig(conn, patch)

    override suspend fun disposeGlobal(conn: ServerConnection): Boolean =
        if (conn.apiVersion.isV2) v2.disposeGlobal(conn) else v1.disposeGlobal(conn)

    override suspend fun disposeInstance(conn: ServerConnection): Boolean =
        if (conn.apiVersion.isV2) v2.disposeInstance(conn) else v1.disposeInstance(conn)
}
