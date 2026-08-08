package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.domain.model.Draft
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DraftDataStore"

@Singleton
class DraftDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
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
                mutableMapOf()
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
