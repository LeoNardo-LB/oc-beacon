package dev.leonardo.ocbeacon.data.github

import dev.leonardo.ocbeacon.data.security.SecretCipher
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GitHubAuth"

/** device flow 端点（github.com/login/device/code 与 github.com/login/oauth/access_token）。 */
internal object GitHubDeviceEndpoints {
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val API_BASE = "https://api.github.com"
}

/** device flow 起始响应（user_code + verification_uri + interval）。 */
data class DeviceCodeRequest(
    val userCode: String,
    val verificationUri: String,
    val deviceCode: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

/** token 轮询结果。 */
sealed class DeviceFlowResult {
    data class Success(val accessToken: String) : DeviceFlowResult()
    /** authorization_pending / slow_down（slow_down 自动加大间隔）。 */
    object Pending : DeviceFlowResult()
    /** expires_in 超时 / access_denied / unsupported_grant_type。 */
    data class Failed(val reason: String) : DeviceFlowResult()
}

/**
 * GitHub App device flow 认证（#151）。
 *
 * 官方语义（spec §认证 已核验）：请求 device code → 展示 8 位码 → 轮询 token；
 * client_secret 嵌入 APK 的风险已知情接受（secret 单独无法完成用户级操作）。
 * token 经 [SecretCipher] 加密存储（与服务器密码同款 v1: 前缀密文）。
 */
@Singleton
class GitHubDeviceFlowAuth @Inject constructor(
    private val json: Json,
    private val tokenStore: GitHubTokenStore,
) {
    /**
     * GitHub 专用 client（#151 真机实证）：强制 HTTP/1.1——手机直连 github.com 时
     * h2 协商会被网络中间层干扰（"Required SETTINGS preface not received"），
     * HTTP/1.1 更稳；不与 LAN opencode 共用 client（那个保持 h2/SSE 长连接语义）。
     */
    private val client = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 20_000
        }
        engine {
            config {
                protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            }
        }
    }

    suspend fun requestDeviceCode(clientId: String, clientSecret: String): Result<DeviceCodeRequest> = runCatching {
        val resp = client.post(GitHubDeviceEndpoints.DEVICE_CODE_URL) {
            header(HttpHeaders.Accept, "application/json")
            // 真机实证修复（#151）：必须显式 form 编码——不设时 Ktor 发 text/plain，
            // GitHub 回错误 JSON（无 user_code），原 `!!` 解析抛 NPE
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            setBody("client_id=$clientId&client_secret=$clientSecret&scope=public_repo")
        }
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        // 防御解析：错误响应（如 400/403）带 error 字段，给出可读失败而非 NPE
        obj["error"]?.jsonPrimitive?.content?.let { error(it) }
        DeviceCodeRequest(
            userCode = obj["user_code"]?.jsonPrimitive?.content ?: error("missing user_code"),
            verificationUri = obj["verification_uri"]?.jsonPrimitive?.content
                ?: "https://github.com/login/device",
            deviceCode = obj["device_code"]?.jsonPrimitive?.content ?: error("missing device_code"),
            intervalSeconds = obj["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
            expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 900,
        )
    }.onFailure { AppLogger.w(TAG, "requestDeviceCode failed", it) }

    /**
     * 轮询一次 token 状态。调用方按 [DeviceCodeRequest.intervalSeconds] 节奏调用；
     * slow_down 时返回 Pending 并由调用方（VM）读取 nextIntervalSeconds 加大间隔。
     */
    suspend fun pollToken(
        clientId: String,
        clientSecret: String,
        deviceCode: String,
    ): Result<DeviceFlowResult> = runCatching {
        val resp = client.post(GitHubDeviceEndpoints.TOKEN_URL) {
            header(HttpHeaders.Accept, "application/json")
            // 同 requestDeviceCode：显式 form 编码（#151 真机实证修复）
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            setBody(
                "client_id=$clientId&client_secret=$clientSecret&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            )
        }
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val accessToken: String? = obj["access_token"]?.jsonPrimitive?.content
        when {
            accessToken != null -> DeviceFlowResult.Success(accessToken)
            else -> when (obj["error"]?.jsonPrimitive?.content) {
                "authorization_pending", "slow_down" -> DeviceFlowResult.Pending
                else -> DeviceFlowResult.Failed(obj["error"]?.jsonPrimitive?.content ?: "unknown")
            }
        }
    }.onFailure { AppLogger.w(TAG, "pollToken failed", it) }
}
