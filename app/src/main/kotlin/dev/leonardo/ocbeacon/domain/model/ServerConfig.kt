package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 服务器配置 —— 存储的服务器连接详情
 */
@Serializable
data class ServerConfig(
    val id: String, // UUID
    val url: String, // 例如 http://192.168.1.100:4096
    val username: String = "opencode",
    val password: String? = null,
    val name: String? = null, // 用户友好的名称
    val autoConnect: Boolean = false,
    val lastConnected: Long? = null,
    val isHealthy: Boolean = false,
    /** 检测到的 OpenCode Server API 版本（V1/V2/UNKNOWN），默认 V1 兼容旧服务器 */
    val apiVersion: ApiVersion = ApiVersion.V1,
    /** 服务器报告的 OpenCode 版本号（如 "2.0.1"），用于 UI 展示 */
    val serverVersion: String? = null,
    /** 2026-08-28（#251 根因修复）：该条目由调试通道创建/激活。autoConnect 对
     *  调试条目是**系统管理位**（「最近激活的调试后端至多一个自连」），对手动
     *  条目是用户管理位——调试激活只降级被标记条目，手动 pin 永不受影响。 */
    val fromDebugChannel: Boolean = false
) {
    val displayName: String
        get() = name ?: url
    
    val host: String
        get() = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore(":")
        }
    
    val port: Int
        get() = try {
            val parsed = java.net.URL(url)
            val explicitPort = parsed.port
            if (explicitPort != -1) {
                explicitPort
            } else {
                parsed.defaultPort // http 为 80，https 为 443
            }
        } catch (e: Exception) {
            url.substringAfterLast(":").toIntOrNull() ?: 80
        }

    companion object {
        /**
         * 2026-08-28（#251 根因修复）：调试后端提升——目标条目置自连 + 打调试标记；
         * 其余**被标记**且自连的条目降级（调试后端集合中至多自连一个 = 最近激活者）。
         *
         * 根因背景：调试通道激活曾无条件给条目写 autoConnect=true，一次性测试后端
         * （如 Host-4200-V1）从此永久加入开机自连集合——服务冷启全量连接（SSE +
         * GET /question 轮询）挂满陈旧后端（真机实证 Auto-connecting 2 server(s)）。
         * 无标记的手动条目不受影响（用户 pin 是用户管理位）。
         */
        fun applyDebugBackendPromotion(
            servers: List<ServerConfig>,
            targetId: String
        ): List<ServerConfig> = servers.map {
            when {
                it.id == targetId -> it.copy(autoConnect = true, fromDebugChannel = true)
                it.fromDebugChannel && it.autoConnect -> it.copy(autoConnect = false)
                else -> it
            }
        }

        /**
         * 2026-08-28（#253）：计算本次提升中被降级自连的服务器 id——供调用方
         * 对仍处于连接状态的后端发起断连（关闭切换后首帧过渡暴露：sweep 先于
         * promote 连上的陈旧后端，在同一启动周期内被摘除）。
         */
        fun computeDemotedAutoConnectIds(
            servers: List<ServerConfig>,
            targetId: String
        ): List<String> {
            val updated = applyDebugBackendPromotion(servers, targetId).associateBy { it.id }
            return servers
                .filter { it.autoConnect && updated[it.id]?.autoConnect == false }
                .map { it.id }
        }

        /**
         * 判断两组 (url, username) 是否指向同一 OpenCode 后端。
         *
         * 归一化：协议 + host 小写、端口显式化（默认端口补全）、路径去尾斜杠。
         * 用于防止到同一后端的两条 SSE 连接投递重复事件（backlog #34）。
         */
        fun sameBackend(urlA: String, userA: String?, urlB: String, userB: String?): Boolean {
            return normalizeBackendKey(urlA, userA) == normalizeBackendKey(urlB, userB)
        }

        private fun normalizeBackendKey(url: String, username: String?): String {
            return try {
                val u = java.net.URL(url)
                val host = u.host.lowercase()
                val port = if (u.port != -1) u.port else u.defaultPort
                val path = u.path.trimEnd('/')
                "${u.protocol.lowercase()}://$host:$port$path@${username ?: ""}"
            } catch (e: Exception) {
                // 解析失败：回退到弱归一化（去尾斜杠 + 小写），保证不崩。
                "${url.trimEnd('/').lowercase()}@${username ?: ""}"
            }
        }
    }
}

/**
 * 服务器健康状态 —— 健康检查结果
 */
@Serializable
data class ServerHealth(
    val healthy: Boolean = false,
    val version: String? = null,
    val uptime: Long? = null
)
