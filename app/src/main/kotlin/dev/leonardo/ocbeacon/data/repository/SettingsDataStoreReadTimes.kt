package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SESSION_READ_TIMES_PREFIX = "session_read_times_"
private const val UNREAD_BASELINE_PREFIX = "unread_baseline_"
private const val ALL_READ_PREFIX = "all_read_"
/** 最后回复时间（全局，sessionId → 最近完成的 assistant 回复 created）。会话 id 全局唯一，无需按服务器隔离。 */
private const val LAST_REPLY_TIME_KEY = "session_last_reply_time"

private val readTimesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val readTimesSerializer = MapSerializer(String.serializer(), Long.serializer())

private fun readTimesKey(serverId: String) = stringPreferencesKey(SESSION_READ_TIMES_PREFIX + serverId)
private fun baselineKey(serverId: String) = longPreferencesKey(UNREAD_BASELINE_PREFIX + serverId)
private fun allReadKey(serverId: String) = longPreferencesKey(ALL_READ_PREFIX + serverId)
private val lastReplyTimeKey = stringPreferencesKey(LAST_REPLY_TIME_KEY)

/** 该服务器的"一键已读"时间戳（epoch ms）：此前的所有回复都算已读。无记录为 0。 */
fun SettingsDataStore.allReadAt(serverId: String): Flow<Long> =
    dataStore.data.map { prefs -> prefs[allReadKey(serverId)] ?: 0L }

/** 一键已读：记录当前时刻为全局已读时间（消除该服务器所有小红点）。 */
suspend fun SettingsDataStore.markAllSessionsRead(serverId: String) {
    dataStore.edit { prefs ->
        prefs[allReadKey(serverId)] = System.currentTimeMillis()
    }
}

/**
 * 最后回复时间（持久化）：sessionId → 最近一次完成的 assistant 回复 created。
 * 由 EventDispatcher 在消息完成时写入（单例后台收集，应用重启后红点不丢失）。
 */
fun SettingsDataStore.lastReplyTimes(): Flow<Map<String, Long>> =
    dataStore.data.map { prefs ->
        val json = prefs[lastReplyTimeKey]
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
    }

/** 全量保存最后回复时间 map。 */
suspend fun SettingsDataStore.saveLastReplyTimes(times: Map<String, Long>) {
    dataStore.edit { prefs ->
        prefs[lastReplyTimeKey] = readTimesJson.encodeToString(readTimesSerializer, times)
    }
    AppLogger.d("UnreadDiag", "[persist] saved ${times.size} entries: ${times.entries.take(3)}")
}

/** 该服务器各会话的最后已读时间（sessionId → lastReadAt epoch ms），用于未读提示判定。 */
fun SettingsDataStore.sessionReadTimes(serverId: String): Flow<Map<String, Long>> =
    dataStore.data.map { prefs ->
        val json = prefs[readTimesKey(serverId)]
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
    }

/**
 * 未读基线（epoch ms）：功能启用时刻。基线之前的消息不算未读——
 * 防止升级后所有历史会话（从未打开过）全部显示红点。
 * 返回当前值（无则写入 now 并返回）。
 */
suspend fun SettingsDataStore.ensureUnreadBaseline(serverId: String): Long {
    val existing = dataStore.data.first()[baselineKey(serverId)]
    if (existing != null) return existing
    val now = System.currentTimeMillis()
    dataStore.edit { prefs ->
        if (prefs[baselineKey(serverId)] == null) {
            prefs[baselineKey(serverId)] = now
        }
    }
    return now
}

/** 将会话标记为已读（记录当前时间戳）。 */
suspend fun SettingsDataStore.markSessionRead(serverId: String, sessionId: String) {
    dataStore.edit { prefs ->
        val current = prefs[readTimesKey(serverId)]?.let {
            runCatching { readTimesJson.decodeFromString(readTimesSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        prefs[readTimesKey(serverId)] = readTimesJson.encodeToString(
            readTimesSerializer,
            current + (sessionId to System.currentTimeMillis())
        )
    }
}
