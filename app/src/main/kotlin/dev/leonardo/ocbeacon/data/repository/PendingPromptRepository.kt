package dev.leonardo.ocbeacon.data.repository

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
 * 基于文件的 JSON 存储，保存乐观待处理 prompt，使其能在应用重启后保留。
 *
 * 写入是同步的并由 `@Synchronized` 保护——数据量极小（每个进行中的发送一条
 * 记录），此处正确性比吞吐量更重要。
 *
 * Hilt 作用域为 [Singleton]，因为它被 [ChatViewModel]（保存/移除）和核对
 * 路径（加载/验证）在整个应用生命周期内共享。
 */
@Singleton
class PendingPromptRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File get() = File(context.filesDir, PENDING_PROMPTS_FILE)

    // 延迟加载的缓存；null = 尚未从磁盘读取。所有访问都通过
    // @Synchronized 方法内的 [ensureLoaded] 进行。
    private var records: MutableMap<String, PendingPromptRecord>? = null

    /** 返回 [sessionId] 的所有已持久化待处理 prompt，按从旧到新排序。 */
    @Synchronized
    fun getForSession(sessionId: String): List<PendingPromptRecord> =
        ensureLoaded().values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    /** 返回所有会话的全部已持久化待处理 prompt，按从旧到新排序。 */
    @Synchronized
    fun loadAll(): List<PendingPromptRecord> =
        ensureLoaded().values.sortedBy { it.createdAt }

    /** 同步持久化一条待处理 prompt，以 [PendingPromptRecord.messageId] 为键。 */
    @Synchronized
    fun save(record: PendingPromptRecord) {
        ensureLoaded()[record.messageId] = record
        persist()
    }

    /** 按消息 id 移除待处理 prompt（不存在则为空操作）。 */
    @Synchronized
    fun remove(messageId: String) {
        if (ensureLoaded().remove(messageId) != null) persist()
    }

    /** 清除所有已持久化的待处理 prompt。 */
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
