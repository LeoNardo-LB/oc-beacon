package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.logging.AppLogger

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.security.SecretCipher
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerHealth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerDataStore"
private const val SERVERS_KEY = "servers"

/**
 * ServerDataStore——使用 DataStore 管理已保存的 OpenCode 服务器。
 */
@Singleton
class ServerDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: SystemApi,
    private val json: Json,
    private val secretCipher: SecretCipher,
    private val versionDetector: dev.leonardo.ocbeacon.data.api.version.ApiVersionDetector
) {
    
    private val serversKey = stringPreferencesKey(SERVERS_KEY)
    
    /**
     * 以 Flow 形式获取所有已保存的服务器（密码已解密为明文，供内存/网络层使用）
     */
    val servers: Flow<List<ServerConfig>> = dataStore.data.map { preferences ->
        val serversJson = preferences[serversKey] ?: "[]"
        try {
            json.decodeFromString<List<ServerConfig>>(serversJson)
                .map { it.withDecryptedPassword() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to decode servers", e)
            emptyList()
        }
    }
    
    /**
     * 获取所有服务器（servers Flow 的别名）
     */
    fun getAllServers(): Flow<List<ServerConfig>> = servers
    
    /**
     * 添加新服务器
     */
    suspend fun addServer(
        url: String,
        username: String = "opencode",
        password: String? = null,
        name: String? = null,
        autoConnect: Boolean = false
    ): ServerConfig {
        val server = ServerConfig(
            id = UUID.randomUUID().toString(),
            url = url.trimEnd('/'),
            username = username,
            password = password,
            name = name,
            autoConnect = autoConnect,
            lastConnected = null,
            isHealthy = false
        )
        
        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers + server
        
        saveServers(updatedServers)
        
        return server
    }
    
    /**
     * 更新服务器
     */
    suspend fun updateServer(server: ServerConfig) {
        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers.map { 
            if (it.id == server.id) server else it 
        }
        
        saveServers(updatedServers)
    }

    suspend fun setAutoConnect(serverId: String, autoConnect: Boolean) {
        val server = getServer(serverId) ?: return
        updateServer(server.copy(autoConnect = autoConnect))
    }
    
    /**
     * 删除服务器
     */
    suspend fun deleteServer(serverId: String) {
        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers.filter { it.id != serverId }
        
        saveServers(updatedServers)
    }
    
    /**
     * 检查服务器健康状态并检测 API 版本（V1/V2）。
     * 检测结果持久化到 ServerConfig 中。
     */
    suspend fun checkHealth(server: ServerConfig): Result<ServerHealth> {
        return try {
            // 优先使用版本检测器探测 API 版本和健康状态
            val detection = versionDetector.detect(server.url, server.username, server.password)

            val health = ServerHealth(
                healthy = detection.version != dev.leonardo.ocbeacon.domain.model.ApiVersion.UNKNOWN,
                version = detection.serverVersionString
            )

            // 更新服务器健康状态和 API 版本
            val updatedServer = server.copy(
                isHealthy = health.healthy,
                lastConnected = System.currentTimeMillis(),
                apiVersion = detection.version,
                serverVersion = detection.serverVersionString
            )
            updateServer(updatedServer)

            Result.success(health)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Health check failed for ${server.url}", e)

            // 标记为不健康
            val updatedServer = server.copy(isHealthy = false)
            updateServer(updatedServer)

            Result.failure(e)
        }
    }
    
    /**
     * 检查服务器健康状态（返回布尔值的别名）
     */
    suspend fun checkServerHealth(server: ServerConfig): Boolean {
        return checkHealth(server).isSuccess
    }
    
    /**
     * 按 ID 获取服务器
     */
    suspend fun getServer(serverId: String): ServerConfig? {
        return servers.firstOrNull()?.find { it.id == serverId }
    }
    
    // ============ 私有 ============
    
    private suspend fun saveServers(servers: List<ServerConfig>) {
        dataStore.edit { preferences ->
            val serversJson = json.encodeToString(servers.map { it.withEncryptedPassword() })
            preferences[serversKey] = serversJson
        }
    }

    /** 读取时解密密码；旧明文数据透明兼容；密钥失效时降级为无密码（不阻塞加载） */
    private fun ServerConfig.withDecryptedPassword(): ServerConfig {
        val pw = password ?: return this
        return copy(
            password = runCatching { secretCipher.decrypt(pw) }.getOrElse {
                AppLogger.w(TAG, "Failed to decrypt password for ${url}", it)
                null
            }
        )
    }

    /** 写入时加密密码；已加密数据幂等；加密失败时降级保持明文（不阻塞功能，Keystore 故障极罕见） */
    private fun ServerConfig.withEncryptedPassword(): ServerConfig {
        val pw = password ?: return this
        return copy(
            password = if (pw.startsWith("v1:")) {
                pw
            } else {
                runCatching { secretCipher.encrypt(pw) }.getOrElse {
                    AppLogger.w(TAG, "Failed to encrypt password for ${url}", it)
                    pw
                }
            }
        )
    }
}
