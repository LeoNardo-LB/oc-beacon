package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SESSION_READ_TIMES_PREFIX = "session_read_times_"
private const val ALL_READ_PREFIX = "all_read_"
private const val UNREAD_STATE_V2_MIGRATED_KEY = "unread_state_v2_migrated"

private val readTimesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val readTimesSerializer = MapSerializer(String.serializer(), Long.serializer())

private fun readTimesKey(serverId: String) = stringPreferencesKey(SESSION_READ_TIMES_PREFIX + serverId)
private fun allReadKey(serverId: String) = longPreferencesKey(ALL_READ_PREFIX + serverId)

/** 该服务器的"一键已读"时间戳（服务器 completed）：此前的所有回复都算已读。无记录为 0。 */
fun SettingsDataStore.allReadAt(serverId: String): Flow<Long> =
    dataStore.data.map { prefs -> prefs[allReadKey(serverId)] ?: 0L }

/** 一键已读：记录全局已读位置（已知会话最后完成消息的 completed，服务器时刻），消除所有小红点。 */
suspend fun SettingsDataStore.markAllSessionsRead(serverId: String, globalMax: Long) {
    dataStore.edit { prefs ->
        prefs[allReadKey(serverId)] = globalMax
    }
}

/** 该服务器各会话的最后已读时间（sessionId → 最后消费的完成消息 completed），用于未读提示判定。 */
fun SettingsDataStore.sessionReadTimes(serverId: String): Flow<Map<String, Long>> =
    dataStore.data.map { prefs ->
        val json = prefs[readTimesKey(serverId)]
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
    }

/** 将会话标记为已读（记录最后消费的完成消息 completed，服务器时刻）。 */
suspend fun SettingsDataStore.markSessionRead(serverId: String, sessionId: String, completedTs: Long) {
    dataStore.edit { prefs ->
        val current = prefs[readTimesKey(serverId)]?.let {
            runCatching { readTimesJson.decodeFromString(readTimesSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        prefs[readTimesKey(serverId)] = readTimesJson.encodeToString(
            readTimesSerializer,
            current + (sessionId to completedTs)
        )
    }
}

/**
 * 一次性迁移：清空已读标记（readTimes/allReadAt）——值域从客户端 now 变为服务器 completed，旧值不可比。幂等。
 */
suspend fun SettingsDataStore.runUnreadStateV2Migration() {
    dataStore.edit { prefs ->
        if (prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] == true) return@edit
        val keys = prefs.asMap().keys.filter {
            it.name.startsWith(SESSION_READ_TIMES_PREFIX) || it.name.startsWith(ALL_READ_PREFIX)
        }
        keys.forEach { prefs.remove(it) }
        prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] = true
    }
}
