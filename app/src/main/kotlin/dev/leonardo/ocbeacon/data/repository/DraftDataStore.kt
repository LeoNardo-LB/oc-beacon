package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    /** 内存缓存，延迟加载（同步读——DataStore 首次读是 IO；runBlocking 一次性成本可接受）。 */
    private var drafts: MutableMap<String, Draft>? = null

    private fun ensureLoaded(): MutableMap<String, Draft> {
        drafts?.let { return it }
        val loaded = try {
            val content = runBlocking { dataStore.data.first() }[draftsKey]
            if (content.isNullOrBlank()) {
                // 一次性迁移：旧 File 格式 → DataStore（迁移后删除旧文件，幂等）
                migrateFromLegacyFile().toMutableMap()
            } else {
                json.decodeFromString<Map<String, Draft>>(content).toMutableMap()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load drafts, starting fresh: ${e.message}")
            mutableMapOf()
        }
        drafts = loaded
        return loaded
    }

    /** 读取旧 File 格式草稿（若有），迁移到 DataStore 并删除旧文件。幂等：文件不存在直接返回空。 */
    private fun migrateFromLegacyFile(): Map<String, Draft> {
        val legacyFile = File(context.filesDir, LEGACY_DRAFTS_FILE)
        if (!legacyFile.exists()) return emptyMap()
        return try {
            val content = legacyFile.readText()
            val legacy = if (content.isNullOrBlank()) emptyMap()
            else json.decodeFromString<Map<String, Draft>>(content)
            if (legacy.isNotEmpty()) {
                persist(legacy)
            }
            legacyFile.delete()
            AppLogger.i(TAG, "Migrated ${legacy.size} drafts from legacy file")
            legacy
        } catch (e: Exception) {
            AppLogger.w(TAG, "Legacy draft migration failed (file kept): ${e.message}")
            emptyMap()
        }
    }

    override fun getDraft(sessionId: String): Draft? {
        val d = ensureLoaded()[sessionId]
        return if (d != null && !d.isEmpty) d else null
    }

    override fun saveDraft(sessionId: String, draft: Draft) {
        val map = ensureLoaded()
        if (draft.isEmpty) {
            map.remove(sessionId)
        } else {
            map[sessionId] = draft
        }
        persist(map)
    }

    override fun getDraftSessionIds(): Set<String> =
        ensureLoaded().filter { !it.value.isEmpty }.keys

    override fun clearDraft(sessionId: String) {
        val map = ensureLoaded()
        if (map.remove(sessionId) != null) {
            persist(map)
        }
    }

    private fun persist(map: Map<String, Draft>) {
        try {
            runBlocking {
                dataStore.edit { prefs ->
                    prefs[draftsKey] = json.encodeToString(map)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist drafts: ${e.message}")
        }
    }
}
