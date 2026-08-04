package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.favoriteKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ============ 跨服务器会话收藏 ============

// 每服务器收藏的会话 id（stringSet）。前缀 + serverId。
private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"
// 全局跨服务器收藏顺序——"serverId:sessionId" 键的列表（JSON）。
private val CROSS_SERVER_FAVORITE_ORDER_KEY = stringPreferencesKey("cross_server_favorite_order")
// 以 "serverId:sessionId" 为键的离线快照（JSON map）。
private val FAVORITE_SESSION_SNAPSHOTS_KEY = stringPreferencesKey("favorite_session_snapshots")

private val favoritesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val favoriteSnapshotMapSerializer =
    MapSerializer(String.serializer(), FavoriteSessionSnapshot.serializer())
private val favoriteOrderSerializer = ListSerializer(String.serializer())

private fun favoriteSessionsKey(serverId: String) =
    stringSetPreferencesKey(FAVORITE_SESSIONS_PREFIX + serverId)

/** 特定服务器收藏的会话 id。 */
fun SettingsDataStore.favoriteSessionIds(serverId: String): Flow<Set<String>> =
    dataStore.data.map { preferences ->
        preferences[favoriteSessionsKey(serverId)] ?: emptySet()
    }

/** 全局跨服务器收藏顺序——"serverId:sessionId" 键的列表。 */
val SettingsDataStore.crossServerFavoriteOrder: Flow<List<String>>
    get() = dataStore.data.map { preferences ->
        val json = preferences[CROSS_SERVER_FAVORITE_ORDER_KEY]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { favoritesJson.decodeFromString(favoriteOrderSerializer, json) }
                .getOrDefault(emptyList())
        }
    }

/** 以 "serverId:sessionId" 为键的离线快照。 */
val SettingsDataStore.favoriteSessionSnapshots: Flow<Map<String, FavoriteSessionSnapshot>>
    get() = dataStore.data.map { preferences ->
        val json = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]
        if (json.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching { favoritesJson.decodeFromString(favoriteSnapshotMapSerializer, json) }
                .getOrDefault(emptyMap())
        }
    }

/** 将某会话加入服务器的收藏，并持久化其离线快照。 */
suspend fun SettingsDataStore.addFavoriteSession(
    serverId: String,
    sessionId: String,
    snapshot: FavoriteSessionSnapshot,
) {
    val key = favoriteKey(serverId, sessionId)
    dataStore.edit { preferences ->
        val favKey = favoriteSessionsKey(serverId)
        preferences[favKey] = (preferences[favKey] ?: emptySet()) + sessionId
        val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
            runCatching { favoritesJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
        preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
            favoritesJson.encodeToString(favoriteSnapshotMapSerializer, snaps + (key to snapshot))
    }
}

/** 将某会话从服务器收藏移除，并清除其快照。 */
suspend fun SettingsDataStore.removeFavoriteSession(serverId: String, sessionId: String) {
    val key = favoriteKey(serverId, sessionId)
    dataStore.edit { preferences ->
        val favKey = favoriteSessionsKey(serverId)
        val current = preferences[favKey] ?: emptySet()
        if (sessionId in current) {
            preferences[favKey] = current - sessionId
        }
        val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
            runCatching { favoritesJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
        if (key in snaps) {
            preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
                favoritesJson.encodeToString(favoriteSnapshotMapSerializer, snaps - key)
        }
    }
}

/** 替换整个跨服务器收藏顺序。 */
suspend fun SettingsDataStore.setCrossServerFavoriteOrder(order: List<String>) {
    dataStore.edit { preferences ->
        preferences[CROSS_SERVER_FAVORITE_ORDER_KEY] =
            favoritesJson.encodeToString(favoriteOrderSerializer, order)
    }
}

/** 在跨服务器顺序列表中 upsert 或移除单个收藏键。 */
suspend fun SettingsDataStore.setCrossServerFavoriteOrderItem(key: String, favorite: Boolean) {
    dataStore.edit { preferences ->
        val current = preferences[CROSS_SERVER_FAVORITE_ORDER_KEY]?.let {
            runCatching { favoritesJson.decodeFromString(favoriteOrderSerializer, it) }
                .getOrDefault(emptyList())
        } ?: emptyList()
        val updated = if (favorite) {
            if (key in current) current else current + key
        } else {
            current - key
        }
        preferences[CROSS_SERVER_FAVORITE_ORDER_KEY] =
            favoritesJson.encodeToString(favoriteOrderSerializer, updated)
    }
}

/** 保存或替换 (server, session) 对的快照。 */
suspend fun SettingsDataStore.saveFavoriteSessionSnapshot(
    serverId: String,
    sessionId: String,
    snapshot: FavoriteSessionSnapshot,
) {
    val key = favoriteKey(serverId, sessionId)
    dataStore.edit { preferences ->
        val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
            runCatching { favoritesJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
        preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
            favoritesJson.encodeToString(favoriteSnapshotMapSerializer, snaps + (key to snapshot))
    }
}

/** 清除 (server, session) 对的快照。 */
suspend fun SettingsDataStore.clearFavoriteSessionSnapshot(serverId: String, sessionId: String) {
    val key = favoriteKey(serverId, sessionId)
    dataStore.edit { preferences ->
        val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
            runCatching { favoritesJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                .getOrDefault(emptyMap())
        } ?: emptyMap()
        if (key in snaps) {
            preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
                favoritesJson.encodeToString(favoriteSnapshotMapSerializer, snaps - key)
        }
    }
}
