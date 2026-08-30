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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
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
    }.flowOn(Dispatchers.IO)
    // flowOn（StrictMode 首轮发现 P2，2026-08-19）：map 内的 DataStore 反序列化 +
    // Keystore 解密（记忆化未命中的首次解密）固定在 IO 线程执行——收集方
    // （ViewModel Main 作用域 / resolveConnection 高频调用）不再把 Keystore
    // 慢调用带回主线程。
    
    /**
     * 添加新服务器
     */
    suspend fun addServer(
        url: String,
        username: String = "opencode",
        password: String? = null,
        name: String? = null,
        autoConnect: Boolean = false,
        /** #276：服务器类型沿传（DSH 条目三分路由 + 探测跳过依据；缺省 OpenCode）。 */
        serverType: dev.leonardo.ocbeacon.domain.model.ServerType = dev.leonardo.ocbeacon.domain.model.ServerType.OpenCode,
        /** 2026-08-17 根治（debug 激活 not found）：尊重调用方指定的 id——
         *  原实现无条件 UUID.randomUUID()，调用方（MainActivity debug 激活）
         *  持有的 id 与落盘 id 不一致 → 后续 resolveConnection 全部
         *  "Server config not found" + 跳过版本探测停留 V1。 */
        id: String = UUID.randomUUID().toString()
    ): ServerConfig {
        val server = ServerConfig(
            id = id,
            url = url.trimEnd('/'),
            username = username,
            password = password,
            name = name,
            autoConnect = autoConnect,
            lastConnected = null,
            isHealthy = false,
            serverType = serverType,
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
     * 2026-08-28（#251 根因修复）：调试后端提升——目标条目置自连 + 打调试标记，
     * 其余被标记且自连的条目降级（调试后端至多自连最近激活的一个）。
     * 纯逻辑见 [ServerConfig.applyDebugBackendPromotion]；此处只负责持久化。
     * @return 被降级自连的服务器 id（#253：供调用方对仍连接者发起断连）。
     */
    suspend fun promoteDebugBackend(targetId: String): List<String> {
        val current = servers.firstOrNull() ?: emptyList()
        val updated = ServerConfig.applyDebugBackendPromotion(current, targetId)
        if (updated != current) saveServers(updated)
        return ServerConfig.computeDemotedAutoConnectIds(current, targetId)
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
            // 优先使用版本检测器探测 API 版本和健康状态。
            // #150 方案 B（2026-08-21）：传入持久化的 apiVersion 作探测排序提示——
            // 已知 V1 的服务器先探 /global/health（省一次白跑 /api/health 的 RTT）。
            // 探测失败仍返回 UNKNOWN（下方保留原版本的 #132 语义不变）。
            val detection = versionDetector.detect(
                server.url, server.username, server.password,
                knownVersion = server.apiVersion,
                serverType = server.serverType, // #276：DSH 跳过 health 双探
            )

            val health = ServerHealth(
                healthy = detection.version != dev.leonardo.ocbeacon.domain.model.ApiVersion.UNKNOWN,
                version = detection.serverVersionString
            )

            // 更新服务器健康状态和 API 版本。
            // 探测失败（UNKNOWN）时保留原 apiVersion——不得把已知 V2 服务器
            // 降级为 V1（2026-08-14 #132 联动：降级后 V1 路径请求 V2 → SPA HTML
            // 解析错误 + SSE 假死）。
            val updatedServer = server.copy(
                isHealthy = health.healthy,
                lastConnected = System.currentTimeMillis(),
                apiVersion = if (detection.version == dev.leonardo.ocbeacon.domain.model.ApiVersion.UNKNOWN) {
                    server.apiVersion
                } else {
                    detection.version
                },
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
