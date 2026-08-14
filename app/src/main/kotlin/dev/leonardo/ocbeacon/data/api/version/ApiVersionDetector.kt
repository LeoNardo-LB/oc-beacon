package dev.leonardo.ocbeacon.data.api.version

import dev.leonardo.ocbeacon.data.api.auth

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
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

        // 2026-08-14 修复（#132 联动）：探测彻底失败必须返回 UNKNOWN 而非默认 V1。
        // 旧行为默认 V1 会让 checkHealth 把已知 V2 服务器降级为 V1 → 后续所有
        // V1 路径请求（/project、/global/event）打到 V2 的 SPA fallback → HTML
        // 解析错误 + SSE 假死"正在连接"。UNKNOWN 语义：healthy=false + 保留原版本。
        AppLogger.w(TAG, "Could not detect API version at $url, returning UNKNOWN")
        return DetectionResult(ApiVersion.UNKNOWN)
    }

    private suspend fun tryV2(url: String, username: String, password: String?): DetectionResult? {
        return try {
            val conn = ServerConnection.from(url, username, password, ApiVersion.V2)
            val response = apiClient.httpClient.get("${conn.baseUrl}/api/health") {
                auth(conn)
            }
            if (!response.status.isSuccess()) return null

            // 防御 1：content-type 必须是 JSON——SPA fallback 的 HTML 页面不算健康响应
            val contentType = response.contentType()
            if (contentType == null || !contentType.match(ContentType.Application.Json)) {
                AppLogger.w(TAG, "V2 probe at $url: non-JSON content-type $contentType, not V2")
                return null
            }

            val body: JsonObject = apiClient.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val version = body["version"]?.jsonPrimitive?.content
            val healthy = body["healthy"]?.jsonPrimitive?.content?.toBoolean() ?: false
            // V2 特征字段：pid（实测 V2 响应必有，V1 过渡形态无）
            val hasPid = body["pid"] != null

            // 防御 2（核心修复）：版本交叉验证——只有确认是 V2 才判定 V2。
            // 判定规则（按优先级）：
            //   1. version 解析为 2.x → 明确 V2
            //   2. 响应含 pid 字段 → V2 特征（V2 预发布版 version 为 "0.0.0-next-xxx"，major=0 解析不出 2.x）
            //   3. version 缺失且无 pid → 过渡形态（如 1.18.18 的 /api/health 只返回 {"healthy":true}）
            //      → 不是 V2 → 回退 V1，避免 V2ApiClient 请求不存在的 /api/* 路径拿到 SPA HTML fallback
            val isV2 = ApiVersion.fromVersionString(version) == ApiVersion.V2 || hasPid
            if (healthy && isV2) {
                DetectionResult(ApiVersion.V2, version)
            } else {
                AppLogger.i(TAG, "V2 probe at $url: healthy=$healthy version=$version hasPid=$hasPid — cross-check failed, not V2")
                null
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "V2 probe failed for $url: ${e.message}")
            null
        }
    }

    private suspend fun tryV1(url: String, username: String, password: String?): DetectionResult? {
        return try {
            val conn = ServerConnection.from(url, username, password, ApiVersion.V1)
            val response = apiClient.httpClient.get("${conn.baseUrl}/global/health") {
                auth(conn)
            }
            if (!response.status.isSuccess()) return null

            // 防御：content-type 必须是 JSON
            val contentType = response.contentType()
            if (contentType == null || !contentType.match(ContentType.Application.Json)) {
                AppLogger.w(TAG, "V1 probe at $url: non-JSON content-type $contentType, not V1")
                return null
            }

            val body: JsonObject = apiClient.json.parseToJsonElement(response.bodyAsText()).jsonObject
            val version = body["version"]?.jsonPrimitive?.content
            val healthy = body["healthy"]?.jsonPrimitive?.content?.toBoolean() ?: false

            if (healthy) DetectionResult(ApiVersion.V1, version) else null
        } catch (e: Exception) {
            AppLogger.d(TAG, "V1 probe failed for $url: ${e.message}")
            null
        }
    }
}
