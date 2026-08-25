package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 未读红点持久化存储（C5 存储归属拆分，自 SettingsDataStore 迁出——纯代码移动，
 * 同一 DataStore 实例、同键名、同序列化格式，**零数据迁移**）。
 *
 * 本 store 只负责 readTimes / allReadAt / lastReplyTime 的持久化与**单调保护**
 * （maxOf 写入不倒退）。红点三铁律的**执行者**在 [UnreadBadgeService] /
 * [EventDispatcher]，不在本层：
 * 1. **maxCompleted 只增不减**——水位线内存合并取 max（UnreadBadgeService）；
 *    落盘侧由本 store 的 markSessionRead / markAllSessionsRead maxOf 单调保护。
 * 2. **连接停止 ≠ 会话删除**——clearForServer/clearAll 不清红点数据，只有
 *    SessionDeleted 才移除条目（UnreadBadgeService.removeSession）。
 * 3. **markSessionIdle 解耦**——客户端终结戳不喂水位线（#171 时钟域纯度）；
 *    本 store 只接收服务器 completed 域的已读位置。
 */
@Singleton
class UnreadStateStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private const val SESSION_READ_TIMES_PREFIX = "session_read_times_"
        private const val ALL_READ_PREFIX = "all_read_"
        private const val UNREAD_STATE_V2_MIGRATED_KEY = "unread_state_v2_migrated"
        private val LAST_REPLY_TIME_KEY = stringPreferencesKey("session_last_reply_time")

        private val readTimesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val readTimesSerializer = MapSerializer(String.serializer(), Long.serializer())

        private fun readTimesKey(serverId: String) = stringPreferencesKey(SESSION_READ_TIMES_PREFIX + serverId)
        private fun allReadKey(serverId: String) = longPreferencesKey(ALL_READ_PREFIX + serverId)
    }

    /** 该服务器的"一键已读"时间戳（服务器 completed）：此前的所有回复都算已读。无记录为 0。 */
    fun allReadAt(serverId: String): Flow<Long> =
        dataStore.data.map { prefs -> prefs[allReadKey(serverId)] ?: 0L }

    /** 一键已读：记录全局已读位置（已知会话最后完成消息的 completed，服务器时刻），消除所有小红点。
     * maxOf 单调保护：全量重同步旧数据/服务器时钟异常导致 globalMax 变小时不倒退 allReadAt。 */
    suspend fun markAllSessionsRead(serverId: String, globalMax: Long) {
        dataStore.edit { prefs ->
            prefs[allReadKey(serverId)] = maxOf(prefs[allReadKey(serverId)] ?: 0L, globalMax)
        }
    }

    /** 该服务器各会话的最后已读时间（sessionId → 最后消费的完成消息 completed），用于未读提示判定。 */
    fun sessionReadTimes(serverId: String): Flow<Map<String, Long>> =
        dataStore.data.map { prefs ->
            val json = prefs[readTimesKey(serverId)]
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
        }

    /** 将会话标记为已读（记录最后消费的完成消息 completed，服务器时刻）。
     * maxOf 单调保护：双 VM 乱序写入时已读位置不倒退。 */
    suspend fun markSessionRead(serverId: String, sessionId: String, completedTs: Long) {
        dataStore.edit { prefs ->
            val current = prefs[readTimesKey(serverId)]?.let {
                runCatching { readTimesJson.decodeFromString(readTimesSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            prefs[readTimesKey(serverId)] = readTimesJson.encodeToString(
                readTimesSerializer,
                current + (sessionId to maxOf(current[sessionId] ?: 0L, completedTs))
            )
        }
    }

    /** 最后完成回复时间（持久化）：sessionId → 最后完成 assistant 消息的 completed（**服务器时刻**）。
     *  EventDispatcher 后台收集，应用重启后未读红点可恢复。 */
    fun lastCompletedReplyTimes(): Flow<Map<String, Long>> =
        dataStore.data.map { prefs ->
            val json = prefs[LAST_REPLY_TIME_KEY]
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
        }

    /** 全量保存最后完成回复时间 map（值域：服务器 completed）。 */
    suspend fun saveLastCompletedReplyTimes(times: Map<String, Long>) {
        dataStore.edit { prefs ->
            prefs[LAST_REPLY_TIME_KEY] = readTimesJson.encodeToString(readTimesSerializer, times)
        }
        AppLogger.d("UnreadDiag", "[persist] saved ${times.size} entries: ${times.entries.take(3)}")
    }

    /**
     * 一次性迁移：清空已读标记（readTimes/allReadAt/旧 lastReplyTime）——值域从客户端 now
     * 变为服务器 completed，旧值不可比。幂等。
     */
    suspend fun runUnreadStateV2Migration() {
        dataStore.edit { prefs ->
            if (prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] == true) return@edit
            val keys = prefs.asMap().keys.filter {
                it.name.startsWith(SESSION_READ_TIMES_PREFIX) ||
                    it.name.startsWith(ALL_READ_PREFIX) ||
                    it == LAST_REPLY_TIME_KEY // 旧客户端 now 域值不可比，迁移时清空（之后复用存服务器域 maxCompleted）
            }
            keys.forEach { prefs.remove(it) }
            prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] = true
        }
    }
}
