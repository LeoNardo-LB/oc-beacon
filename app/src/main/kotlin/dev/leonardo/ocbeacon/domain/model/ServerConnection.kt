package dev.leonardo.ocbeacon.domain.model

import java.util.Base64

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
            ApiVersion.V2 -> "$baseUrl/api"
            else -> baseUrl
        }

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
