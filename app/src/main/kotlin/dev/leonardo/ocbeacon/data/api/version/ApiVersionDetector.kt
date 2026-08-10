package dev.leonardo.ocbeacon.data.api.version

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ApiVersionDetector"

/**
 * 检测 OpenCode Server 的 API 版本（V1 或 V2）。
 *
 * 策略：
 * 1. 尝试 V2 健康端点 `GET /api/health`（V2 专属路径）
 * 2. 若成功 → V2，提取版本号
 * 3. 若失败 → 尝试 V1 健康端点 `GET /global/health`
 * 4. 若成功 → V1，提取版本号
 * 5. 两者均失败 → UNKNOWN（回退到 V1 行为）
 *
 * V2 响应格式: `{ "healthy": true, "version": "2.x.x", "pid": {} }`
 * V1 响应格式: `{ "healthy": true, "version": "1.x.x" }`（或带 uptime 字段）
 */
@Singleton
class ApiVersionDetector @Inject constructor(
    private val apiClient: ApiClient
) {
    data class DetectionResult(
        val version: ApiVersion,
        val serverVersionString: String? = null
    )

    /**
     * 探测指定连接的 API 版本。
     * 使用无版本的 ServerConnection（默认 V1）发起探测请求。
     */
    suspend fun detect(url: String, username: String = "opencode", password: String? = null): DetectionResult {
        // 先尝试 V2：GET /api/health
        val v2Result = tryV2(url, username, password)
        if (v2Result != null) {
            AppLogger.i(TAG, "Detected V2 API at $url (version=${v2Result.serverVersionString})")
            return v2Result
        }

        // 回退到 V1：GET /global/health
        val v1Result = tryV1(url, username, password)
        if (v1Result != null) {
            AppLogger.i(TAG, "Detected V1 API at $url (version=${v1Result.serverVersionString})")
            return v1Result
        }

        AppLogger.w(TAG, "Could not detect API version at $url, defaulting to V1")
        return DetectionResult(ApiVersion.V1)
    }

    private suspend fun tryV2(url: String, username: String, password: String?): DetectionResult? {
        return try {
            val conn = ServerConnection.from(url, username, password, ApiVersion.V2)
            val response = apiClient.httpClient.get("${conn.baseUrl}/api/health") {
                conn.authHeader?.let { header("Authorization", it) }
            }
            if (!response.status.isSuccess()) return null

            val body: JsonObject = apiClient.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val version = body["version"]?.jsonPrimitive?.content
            val healthy = body["healthy"]?.jsonPrimitive?.content?.toBoolean() ?: true

            if (healthy) DetectionResult(ApiVersion.V2, version) else null
        } catch (e: Exception) {
            AppLogger.d(TAG, "V2 probe failed for $url: ${e.message}")
            null
        }
    }

    private suspend fun tryV1(url: String, username: String, password: String?): DetectionResult? {
        return try {
            val conn = ServerConnection.from(url, username, password, ApiVersion.V1)
            val response = apiClient.httpClient.get("${conn.baseUrl}/global/health") {
                conn.authHeader?.let { header("Authorization", it) }
            }
            if (!response.status.isSuccess()) return null

            val body: JsonObject = apiClient.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val version = body["version"]?.jsonPrimitive?.content
            val healthy = body["healthy"]?.jsonPrimitive?.content?.toBoolean() ?: true

            if (healthy) DetectionResult(ApiVersion.V1, version) else null
        } catch (e: Exception) {
            AppLogger.d(TAG, "V1 probe failed for $url: ${e.message}")
            null
        }
    }
}
