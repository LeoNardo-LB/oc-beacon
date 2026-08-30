package dev.leonardo.ocbeacon.domain.model

import java.util.Base64

/**
 * 服务器能力位（#172）——版本/类型差异在连接对象上的显式投影，UI 门控只读能力
 * 不读版本。null 版本（未知/未加载）→ 全能力开放（与原 `version != X` 比较的
 * permissive 语义一致）。
 *
 * #276 步骤②（设计 §2.2）：扩为 of(serverType, apiVersion)——DSH 分支现有五个
 * 能力位全置 false（share/todo 面缺失、background/active 域缺失、settings 特权
 * 面 UI 不开放、compaction 域缺失）；UI 入口按能力位隐藏，不写服务器类型特判。
 * 能力位缺口（无对应位、待 UI 卡扩）：PTY/终端、文件内容读取、会话删除、
 * vcs/git、文件搜索——本期不为此扩 UI 判断。
 */
data class ServerCapabilities(
    /** 会话分享（V2 无 share 端点；DSH 无 share 域）。 */
    val shareSupported: Boolean,
    /** 后台会话/堆积队列（V2 专属；DSH 无 background 域）。 */
    val backgroundSessionsSupported: Boolean,
    /** 运行中会话过滤（V2 active sessions 专属；DSH 无该端点）。 */
    val runningSessionsFilterSupported: Boolean,
    /** 全局配置可写（V2 /api/config 只读，PATCH 404——backlog #85；DSH settings 特权面不开放 UI）。 */
    val configEditable: Boolean,
    /**
     * 压缩异步化（#217 分割线包揽）：V2 compact HTTP 立即返回（steer 异步），
     * 进行中/终态由 SSE compaction.started/delta/ended 驱动；V1 summarize HTTP
     * 同步挂起至完成、SSE 只有单个 compacted 完成事件——HTTP 返回即终态。
     * null 版本（未知/未加载）按 V1 语义（保守：HTTP 返回即终态）。
     * DSH：compaction 事件族存在但无 HTTP 端点 → false。
     */
    val compactionAsync: Boolean,
) {
    companion object {
        /**
         * #276 三分入口：serverType==Dsh 优先于 apiVersion（DSH 条目不参与
         * V1/V2 探测，apiVersion 恒 V1 缺省）。
         */
        fun of(serverType: ServerType, apiVersion: ApiVersion?): ServerCapabilities =
            when (serverType) {
                ServerType.Dsh -> ServerCapabilities(
                    shareSupported = false,
                    backgroundSessionsSupported = false,
                    runningSessionsFilterSupported = false,
                    configEditable = false,
                    compactionAsync = false,
                )
                ServerType.OpenCode -> ofOpenCode(apiVersion)
            }

        /** 既有单参签名（兼容旧调用方）——语义等价 of(OpenCode, apiVersion)。 */
        fun of(apiVersion: ApiVersion?): ServerCapabilities = ofOpenCode(apiVersion)

        private fun ofOpenCode(apiVersion: ApiVersion?): ServerCapabilities = when (apiVersion) {
            ApiVersion.V2 -> ServerCapabilities(
                shareSupported = false,
                backgroundSessionsSupported = true,
                runningSessionsFilterSupported = true,
                configEditable = false,
                compactionAsync = true,
            )
            else -> ServerCapabilities( /* V1 / UNKNOWN / null：全开放 */
                shareSupported = true,
                backgroundSessionsSupported = apiVersion == null,
                runningSessionsFilterSupported = apiVersion == null,
                configEditable = true,
                compactionAsync = false,
            )
        }
    }
}

data class ServerConnection(
    val baseUrl: String,
    val authHeader: String?,
    /** 检测到的 OpenCode Server API 版本（V1/V2），默认 V1（旧服务器兼容） */
    val apiVersion: ApiVersion = ApiVersion.V1,
    /**
     * 服务器类型（#276 步骤①；设计 §2.1）：DSH 条目下各域 *ApiImpl.pick 三分
     * 优先路由到 DshApiClient；apiVersion 不参与路由。DSH 传输层忽略
     * [authHeader]（无鉴权，§1.6 P-1）。
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

    /** 能力位派生（#172/#276）——纯映射，每次构造新鲜。 */
    val capabilities: ServerCapabilities
        get() = ServerCapabilities.of(serverType, apiVersion)

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
                "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
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
