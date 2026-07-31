package dev.leonardo.ocbeacon.data.api.provider

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.dto.request.*
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

interface ProviderApi {
    /**
     * Get available providers and models.
     * GET /config/providers
     */
    suspend fun getProviders(conn: ServerConnection): ProvidersResponse

    /**
     * Get provider catalog with connection status.
     * GET /provider
     */
    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse

    /**
     * Get available auth methods for providers.
     * GET /provider/auth
     */
    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>>

    /**
     * Start OAuth authorization for a provider.
     * POST /provider/{providerID}/oauth/authorize
     */
    suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization?

    /**
     * Complete OAuth authorization for a provider.
     * POST /provider/{providerID}/oauth/callback
     */
    suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String? = null
    ): Boolean

    /**
     * Set API key auth for provider.
     * PUT /auth/{providerID}
     */
    suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean

    /**
     * Remove stored auth for provider.
     * DELETE /auth/{providerID}
     */
    suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean

    /**
     * Get current server config.
     * GET /config
     */
    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse

    /**
     * Get global server config.
     * GET /global/config
     */
    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse

    /**
     * Patch server config.
     * PATCH /config
     */
    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse

    /**
     * Patch global server config.
     * PATCH /global/config
     */
    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse

    /**
     * Dispose global instances and force provider/auth state refresh.
     * POST /global/dispose
     */
    suspend fun disposeGlobal(conn: ServerConnection): Boolean

    /**
     * Dispose current instance.
     * POST /instance/dispose
     */
    suspend fun disposeInstance(conn: ServerConnection): Boolean
}

@Singleton
class ProviderApiImpl @Inject constructor(
    private val apiClient: ApiClient
) : ProviderApi {

    companion object {
        private const val TAG = "ProviderApi"
    }

    private val httpClient get() = apiClient.httpClient
    private val json get() = apiClient.json

    /**
     * Get available providers and models.
     * GET /config/providers
     */
    override suspend fun getProviders(conn: ServerConnection): ProvidersResponse {
        return httpClient.get("${conn.baseUrl}/config/providers") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get provider catalog with connection status.
     * GET /provider
     */
    override suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse {
        return httpClient.get("${conn.baseUrl}/provider") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get available auth methods for providers.
     * GET /provider/auth
     */
    override suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        return httpClient.get("${conn.baseUrl}/provider/auth") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Start OAuth authorization for a provider.
     * POST /provider/{providerID}/oauth/authorize
     */
    override suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? {
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/authorize") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("method" to methodIndex))
        }
        val body = response.bodyAsText().trim()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "authorizeProviderOauth: status=${response.status} body=$body")
        }

        if (!response.status.isSuccess()) return null
        if (body.isBlank() || body == "null") return ProviderOauthAuthorization()

        return runCatching {
            json.decodeFromString(ProviderOauthAuthorization.serializer(), body)
        }.getOrElse {
            // Some server builds return an empty object for headless mode.
            ProviderOauthAuthorization()
        }
    }

    /**
     * Complete OAuth authorization for a provider.
     * POST /provider/{providerID}/oauth/callback
     */
    override suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String?
    ): Boolean {
        val body = if (code != null) mapOf("method" to methodIndex, "code" to code)
        else mapOf("method" to methodIndex)
        if (BuildConfig.DEBUG) Log.d(TAG, "completeProviderOauth: POST /provider/$providerId/oauth/callback body=$body")
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/callback") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (BuildConfig.DEBUG) {
            val responseBody = response.bodyAsText()
            Log.d(TAG, "completeProviderOauth: status=${response.status}, body=$responseBody")
        }
        return response.status.isSuccess()
    }

    /**
     * Set API key auth for provider.
     * PUT /auth/{providerID}
     */
    override suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean {
        val response = httpClient.put("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("type" to "api", "key" to apiKey))
        }
        return response.status.isSuccess()
    }

    /**
     * Remove stored auth for provider.
     * DELETE /auth/{providerID}
     */
    override suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "removeProviderAuth: DELETE ${conn.baseUrl}/auth/$providerId")
        val response = httpClient.delete("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (BuildConfig.DEBUG) {
            val body = response.bodyAsText()
            Log.d(TAG, "removeProviderAuth: status=${response.status}, body=$body")
        }
        return response.status.isSuccess()
    }

    /**
     * Get current server config.
     * GET /config
     */
    override suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get global server config.
     * GET /global/config
     */
    override suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Patch server config.
     * PATCH /config
     */
    override suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    /**
     * Patch global server config.
     * PATCH /global/config
     */
    override suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    /**
     * Dispose global instances and force provider/auth state refresh.
     * POST /global/dispose
     */
    override suspend fun disposeGlobal(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/global/dispose") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    /**
     * Dispose current instance.
     * POST /instance/dispose
     */
    override suspend fun disposeInstance(conn: ServerConnection): Boolean {
        val response = httpClient.post("${conn.baseUrl}/instance/dispose") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }
}
