package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.safeCatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DraftDataStore"
private const val LEGACY_DRAFTS_FILE = "session_drafts.json"

@Singleton
class DraftDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationContext private val context: Context,
) : dev.leonardo.ocbeacon.domain.repository.DraftRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val draftsKey = stringPreferencesKey("session_drafts")

    /**
     * 懒加载内存缓存——首次访问时从 DataStore 异步读取（suspend）。
     * 后续访问纯内存，无 IO 开销。
     *
     * 缓存以 [loadLock] 保护并发加载（多个协程同时首次访问只触发一次磁盘读取）。
     */
    @Volatile
    private var cached: Map<String, Draft>? = null
    private val loadMutex = Mutex()

    /** 确保内存缓存已加载；若未加载则同步阻塞当前协程（suspend，不阻塞线程）完成首次加载。 */
    private suspend fun ensureLoaded(): Map<String, Draft> {
        cached?.let { return it }
        return loadMutex.withLock {
            cached?.let { return it }
            val loaded = loadFromDisk()
            cached = loaded
            loaded
        }
    }

    /** 从 DataStore 读取草稿映射；若 DataStore 无数据则尝试从旧 File 格式迁移。 */
    private suspend fun loadFromDisk(): Map<String, Draft> = safeCatch(
        block = {
            val content = dataStore.data.first()[draftsKey]
            if (content.isNullOrBlank()) {
                // 一次性迁移：旧 File 格式 → DataStore（迁移后删除旧文件，幂等）
                migrateFromLegacyFile()
            } else {
                json.decodeFromString<Map<String, Draft>>(content)
            }
        },
        fallback = { e ->
            AppLogger.w(TAG, "Failed to load drafts, starting fresh: ${e.message}")
            emptyMap()
        }
    )

    /** 读取旧 File 格式草稿（若有），迁移到 DataStore 并删除旧文件。幂等：文件不存在直接返回空。 */
    private suspend fun migrateFromLegacyFile(): Map<String, Draft> = safeCatch(
        block = {
            val legacyFile = File(context.filesDir, LEGACY_DRAFTS_FILE)
            if (!legacyFile.exists()) return@safeCatch emptyMap()
            val content = legacyFile.readText()
            val legacy = if (content.isNullOrBlank()) emptyMap()
            else json.decodeFromString<Map<String, Draft>>(content)
            if (legacy.isNotEmpty()) {
                persist(legacy)
            }
            legacyFile.delete()
            AppLogger.i(TAG, "Migrated ${legacy.size} drafts from legacy file")
            legacy
        },
        fallback = { e ->
            AppLogger.w(TAG, "Legacy draft migration failed (file kept): ${e.message}")
            emptyMap()
        }
    )

    override suspend fun getDraft(sessionId: String): Draft? {
        val d = ensureLoaded()[sessionId]
        return if (d != null && !d.isEmpty) d else null
    }

    override suspend fun saveDraft(sessionId: String, draft: Draft) {
        val map = ensureLoaded().toMutableMap()
        if (draft.isEmpty) {
            map.remove(sessionId)
        } else {
            map[sessionId] = draft
        }
        cached = map
        persist(map)
    }

    override suspend fun getDraftSessionIds(): Set<String> =
        ensureLoaded().filter { !it.value.isEmpty }.keys

    override suspend fun clearDraft(sessionId: String) {
        val map = ensureLoaded().toMutableMap()
        if (map.remove(sessionId) != null) {
            cached = map
            persist(map)
        }
    }

    /** 将映射持久化到 DataStore（suspend，不阻塞调用线程）。 */
    private suspend fun persist(map: Map<String, Draft>) = safeCatch(
        block = {
            dataStore.edit { prefs ->
                prefs[draftsKey] = json.encodeToString(map)
            }
        },
        fallback = { e ->
            AppLogger.e(TAG, "Failed to persist drafts", e)
        }
    )
}
