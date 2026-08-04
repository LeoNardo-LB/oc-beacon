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
