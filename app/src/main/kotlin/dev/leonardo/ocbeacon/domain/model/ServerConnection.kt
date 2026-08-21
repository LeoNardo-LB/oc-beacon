package dev.leonardo.ocbeacon.domain.model

import java.util.Base64

/**
 * 服务器能力位（#172）——版本差异在连接对象上的显式投影，UI 门控只读能力不读版本。
 * null 版本（未知/未加载）→ 全能力开放（与原 `version != X` 比较的 permissive 语义一致）。
 */
data class ServerCapabilities(
    /** 会话分享（V2 无 share 端点）。 */
    val shareSupported: Boolean,
    /** 后台会话/堆积队列（V2 专属）。 */
    val backgroundSessionsSupported: Boolean,
    /** 运行中会话过滤（V2 active sessions 专属）。 */
    val runningSessionsFilterSupported: Boolean,
    /** 全局配置可写（V2 /api/config 只读，PATCH 404——backlog #85）。 */
    val configEditable: Boolean,
) {
    companion object {
        fun of(apiVersion: ApiVersion?): ServerCapabilities = when (apiVersion) {
            ApiVersion.V2 -> ServerCapabilities(
                shareSupported = false,
                backgroundSessionsSupported = true,
                runningSessionsFilterSupported = true,
                configEditable = false,
            )
            else -> ServerCapabilities( /* V1 / UNKNOWN / null：全开放 */
                shareSupported = true,
                backgroundSessionsSupported = apiVersion == null,
                runningSessionsFilterSupported = apiVersion == null,
                configEditable = true,
            )
        }
    }
}

data class ServerConnection(
    val baseUrl: String,
    val authHeader: String?,
    /** 检测到的 OpenCode Server API 版本（V1/V2），默认 V1（旧服务器兼容） */
    val apiVersion: ApiVersion = ApiVersion.V1
) {
    /**
     * API 端点基础路径。
     * - V1: 直接使用 baseUrl（如 http://host:4096）
     * - V2: 追加 /api 前缀（如 http://host:4096/api）
     */
    val apiBase: String
        get() = when (apiVersion) {
            ApiVersion.V2 -> baseUrl + "/api"
            else -> baseUrl
        }

    /** 能力位派生（#172）——纯映射，每次构造新鲜。 */
    val capabilities: ServerCapabilities
        get() = ServerCapabilities.of(apiVersion)

    companion object {
        fun from(
            url: String,
            username: String = "opencode",
            password: String? = null,
            apiVersion: ApiVersion = ApiVersion.V1
        ): ServerConnection {
            val base = url.trimEnd('/')
            val auth = if (password != null) {
                val credentials = "$username:$password"
                "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
            } else {
                null
            }
            return ServerConnection(base, auth, apiVersion)
        }
    }
}
