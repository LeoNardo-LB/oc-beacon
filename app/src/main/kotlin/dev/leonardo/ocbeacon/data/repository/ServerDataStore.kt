package dev.leonardo.ocbeacon.data.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.data.api.system.SystemApi
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
 * ServerDataStore - manages saved OpenCode servers using DataStore.
 */
@Singleton
class ServerDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: SystemApi,
    private val json: Json
) {
    
    private val serversKey = stringPreferencesKey(SERVERS_KEY)
    
    /**
     * Get all saved servers as Flow
     */
    val servers: Flow<List<ServerConfig>> = dataStore.data.map { preferences ->
        val serversJson = preferences[serversKey] ?: "[]"
        try {
            json.decodeFromString<List<ServerConfig>>(serversJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode servers", e)
            emptyList()
        }
    }
    
    /**
     * Get all servers (alias for servers Flow)
     */
    fun getAllServers(): Flow<List<ServerConfig>> = servers
    
    /**
     * Add a new server
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
     * Update a server
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
     * Delete a server
     */
    suspend fun deleteServer(serverId: String) {
        val currentServers = servers.firstOrNull() ?: emptyList()
        val updatedServers = currentServers.filter { it.id != serverId }
        
        saveServers(updatedServers)
    }
    
    /**
     * Check server health
     */
    suspend fun checkHealth(server: ServerConfig): Result<ServerHealth> {
        return try {
            val conn = ServerConnection.from(server.url, server.username, server.password)
            val health = api.getHealth(conn)
            
            // Update server health status
            val updatedServer = server.copy(
                isHealthy = health.healthy,
                lastConnected = System.currentTimeMillis()
            )
            updateServer(updatedServer)
            
            Result.success(health)
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed for ${server.url}", e)
            
            // Mark as unhealthy
            val updatedServer = server.copy(isHealthy = false)
            updateServer(updatedServer)
            
            Result.failure(e)
        }
    }
    
    /**
     * Check server health (alias returning boolean)
     */
    suspend fun checkServerHealth(server: ServerConfig): Boolean {
        return checkHealth(server).isSuccess
    }
    
    /**
     * Get server by ID
     */
    suspend fun getServer(serverId: String): ServerConfig? {
        return servers.firstOrNull()?.find { it.id == serverId }
    }
    
    // ============ Private ============
    
    private suspend fun saveServers(servers: List<ServerConfig>) {
        dataStore.edit { preferences ->
            val serversJson = json.encodeToString(servers)
            preferences[serversKey] = serversJson
        }
    }
}
