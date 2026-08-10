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
    val isHealthy: Boolean = false
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
