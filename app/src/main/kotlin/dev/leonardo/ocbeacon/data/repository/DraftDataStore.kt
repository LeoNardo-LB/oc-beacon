package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.domain.model.Draft
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DraftDataStore"
private const val DRAFTS_FILE = "session_drafts.json"

@Singleton
class DraftDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) : dev.leonardo.ocbeacon.domain.repository.DraftRepository {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(context.filesDir, DRAFTS_FILE)

    /** 内存缓存，延迟加载。 */
    private var drafts: MutableMap<String, Draft>? = null

    private fun ensureLoaded(): MutableMap<String, Draft> {
        drafts?.let { return it }
        val loaded = try {
            val content = file.takeIf { it.exists() }?.readText()
            if (content.isNullOrBlank()) {
                mutableMapOf()
            } else {
                json.decodeFromString<Map<String, Draft>>(content).toMutableMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load drafts, starting fresh: ${e.message}")
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
            file.writeText(json.encodeToString(map))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist drafts: ${e.message}")
        }
    }
}
