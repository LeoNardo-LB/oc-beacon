package dev.leonardo.ocbeacon.domain.model

import java.util.Base64

/**
 * 服务器能力位（#172）——版本/类型差异在连接对象上的显式投影，UI 门控只读能力
 * 不读版本。null 版本（未知/未加载）→ 全能力开放（与原 `version != X` 比较的
 * permissive 语义一致）。
 *
 * #276 步骤②（设计 §2.2）：扩为 of(serverType, apiVersion)——DSH 分支五个初始
 * 能力位全置 false（share/todo 面缺失、background/active 域缺失、settings 特权
 * 面 UI 不开放）；UI 入口按能力位隐藏，不写服务器类型特判。compactionAsync 例外
 * （#276 接口补全后为 true，见字段注释）。
 *
 * #276 UI 卡（2026-08-31 缺口清单补位）：新增六位覆盖原「无对应位」入口——
 * terminal/fileRead/sessionDelete/vcs/fileSearch/commands。OpenCode V1/V2/UNKNOWN
 * 全 true（两代端点均存在，null 版本 permissive 语义与原五位一致）；DSH 全 false
 * （§2.6 终局确认：PTY/shell 域缺失、文件内容读无方法、无 session.delete、
 * vcs 无对应、find 无对应、command 执行无对应）。
 *
 * #276 后端接口补全（2026-08-31 第二批）：三位（revert/messageDelete/shell
 * Command）——DSH 52 方法面终局无 revert/unrevert（撤销/重做链路整体缺失）、
 * 无消息删除、无 shell 命令域 → 全 false；OpenCode V1/V2 均有对应端点 → 全 true。
 * UI 撤销/重做/消息长按撤销入口与 shell 命令栏按位隐藏。
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
     * DSH（#276 接口补全修订）：compact 走 /compact 命令通道——HTTP 受理即回，
     * 完成由 compaction/end → SessionCompacted 事件通告 = 异步语义 true
     *（旧 false 按 V1 路径本地置态又秒杀，59ms 分割线闪现 bug 同款）。
     */
    val compactionAsync: Boolean,
    /**
     * #276 终验 V5：压缩与模型无关（DSH /compact 走斜杠命令通道，不进模型——
     * 无模型选择也须发 RPC）；OpenCode V1/V2 summarize/compact 端点带
     * providerID/modelID → false（客户端「no model selected」护栏维持）。
     */
    val compactionModelIndependent: Boolean,
    /** 终端 PTY 入口（DSH 无 PTY 域，§2.6 终局确认；OpenCode V1/V2 均有 /pty）。 */
    val terminalSupported: Boolean,
    /** 文件内容查看（DSH 无读取方法——host.openPath 是宿主侧特权打开；目录树另算：host.listDirectory 存在）。 */
    val fileReadSupported: Boolean,
    /** 会话删除（DSH 52 方法面无 session.delete——存档≠删除）。 */
    val sessionDeleteSupported: Boolean,
    /** Git/vcs 面板（DSH 无 vcs 方法）。 */
    val vcsSupported: Boolean,
    /** 文件搜索（DSH 无 find 对应方法）。 */
    val fileSearchSupported: Boolean,
    /** 斜杠命令面板（DSH 无 command 执行端点；listCommands 已空列表降级）。 */
    val commandsSupported: Boolean,
    /** 撤销/重做（revert/unrevert；DSH 52 方法面无对应——撤回入口按位隐藏）。 */
    val revertSupported: Boolean,
    /** 消息删除（DELETE message 端点；DSH 无对应——当前无 UI 入口，防御位）。 */
    val messageDeleteSupported: Boolean,
    /** shell 命令（session.shell 域；DSH 无对应——shell 命令栏与 ！ 前缀通道按位隐藏）。 */
    val shellCommandSupported: Boolean,
    /**
     * #276 终验 V6：会话导出载荷是 ZIP 归档（DSH GET /api/session.export 响应体
     * 即 ZIP 流，§5 P-4）——落盘显示名须 .zip；OpenCode 导出是 JSON 文档
     * （{"info","messages"} 流式拼接）→ false（维持 .json）。
     */
    val exportIsArchive: Boolean,
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
                    compactionAsync = true,
                    compactionModelIndependent = true,
                    terminalSupported = false,
                    fileReadSupported = false,
                    sessionDeleteSupported = false,
                    vcsSupported = false,
                    fileSearchSupported = false,
                    commandsSupported = false,
                    revertSupported = false,
                    messageDeleteSupported = false,
                    shellCommandSupported = false,
                    exportIsArchive = true,
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
                compactionModelIndependent = false,
                terminalSupported = true,
                fileReadSupported = true,
                sessionDeleteSupported = true,
                vcsSupported = true,
                fileSearchSupported = true,
                commandsSupported = true,
                revertSupported = true,
                messageDeleteSupported = true,
                shellCommandSupported = true,
                exportIsArchive = false,
            )
            else -> ServerCapabilities( /* V1 / UNKNOWN / null：全开放 */
                shareSupported = true,
                backgroundSessionsSupported = apiVersion == null,
                runningSessionsFilterSupported = apiVersion == null,
                configEditable = true,
                compactionAsync = false,
                compactionModelIndependent = false,
                terminalSupported = true,
                fileReadSupported = true,
                sessionDeleteSupported = true,
                vcsSupported = true,
                fileSearchSupported = true,
                commandsSupported = true,
                revertSupported = true,
                messageDeleteSupported = true,
                shellCommandSupported = true,
                exportIsArchive = false,
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
