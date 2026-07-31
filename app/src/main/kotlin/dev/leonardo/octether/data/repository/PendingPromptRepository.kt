package dev.leonardo.octether.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PendingPromptRepository"
private const val PENDING_PROMPTS_FILE = "pending_prompts.json"

/**
 * File-backed JSON store for optimistic pending prompts so they survive app restarts.
 *
 * Writes are synchronous and guarded by `@Synchronized` — the volume is tiny (one
 * record per in-flight send) and correctness matters more than throughput here.
 *
 * Hilt-scoped [Singleton] because it is shared by [ChatViewModel] (save/remove) and
 * the reconciliation path (load/verify) across the app lifetime.
 */
@Singleton
class PendingPromptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File get() = File(context.filesDir, PENDING_PROMPTS_FILE)

    // Lazily-loaded cache; null = not yet read from disk. All access goes through
    // [ensureLoaded] inside @Synchronized methods.
    private var records: MutableMap<String, PendingPromptRecord>? = null

    /** Returns all persisted pending prompts for [sessionId], oldest first. */
    @Synchronized
    fun getForSession(sessionId: String): List<PendingPromptRecord> =
        ensureLoaded().values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    /** Returns every persisted pending prompt across all sessions, oldest first. */
    @Synchronized
    fun loadAll(): List<PendingPromptRecord> =
        ensureLoaded().values.sortedBy { it.createdAt }

    /** Persist a pending prompt synchronously, keyed by [PendingPromptRecord.messageId]. */
    @Synchronized
    fun save(record: PendingPromptRecord) {
        ensureLoaded()[record.messageId] = record
        persist()
    }

    /** Remove a pending prompt by its message id (no-op if absent). */
    @Synchronized
    fun remove(messageId: String) {
        if (ensureLoaded().remove(messageId) != null) persist()
    }

    /** Wipe every persisted pending prompt. */
    @Synchronized
    fun clear() {
        ensureLoaded().clear()
        persist()
    }

    private fun ensureLoaded(): MutableMap<String, PendingPromptRecord> {
        records?.let { return it }
        records = try {
            file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<Map<String, PendingPromptRecord>>(it).toMutableMap() }
                ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pending prompts: ${e.message}", e)
            mutableMapOf()
        }
        return records!!
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(ensureLoaded()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist pending prompts: ${e.message}", e)
        }
    }
}
