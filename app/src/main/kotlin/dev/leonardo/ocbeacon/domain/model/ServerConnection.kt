package dev.leonardo.ocbeacon.domain.model

import java.util.Base64

/**
 * 服务器连接 —— 纯数据值对象（#391 切片 2）。
 *
 * 连接对象不再承载能力 getter（原 `capabilities`）与集中的服务器类型能力分支：能力一律
 * 经 [dev.leonardo.ocbeacon.domain.adapter.ServerAdapterResolver] 派生，由解析器提供。
 * 连接只保留路由与身份所需的最小数据：地址、鉴权头、API 版本、服务器类型。
 */
data class ServerConnection(
    val baseUrl: String,
    val authHeader: String?,
    /** 检测到的 OpenCode Server API 版本（V1/V2），默认 V1（旧服务器兼容）。 */
    val apiVersion: ApiVersion = ApiVersion.V1,
    /**
     * 服务器类型（#276 步骤①）：适配器按该维度完成类型内子策略路由；
     * DSH 传输层忽略 [authHeader]（无 Basic 鉴权）。
     */
    val serverType: ServerType = ServerType.OpenCode
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

    companion object {
        fun from(
            url: String,
            username: String = "opencode",
            password: String? = null,
            apiVersion: ApiVersion = ApiVersion.V1,
            serverType: ServerType = ServerType.OpenCode
        ): ServerConnection {
            val base = url.trimEnd('/')
            val auth = if (password != null) {
                val credentials = "$username:$password"
                "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
            } else {
                null
            }
            return ServerConnection(base, auth, apiVersion, serverType)
        }

        /**
         * #276：从持久化配置构造（data 层 resolveConnection 单点换用）——
         * serverType 沿传，避免各处手拼参数漏带新维度。
         */
        fun from(config: ServerConfig): ServerConnection =
            from(config.url, config.username, config.password, config.apiVersion, config.serverType)
    }
}
