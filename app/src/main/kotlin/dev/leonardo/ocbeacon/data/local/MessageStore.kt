package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息本地缓存存储（Room）。
 *
 * 限量策略：每会话最近 [SESSION_MESSAGE_LIMIT] 条；翻页拉到窗口外的更早消息
 * 默认不落库（persistOldBeyondWindow=false），避免"写了又被裁"循环。
 */
@Singleton
class MessageStore @Inject constructor(
    private val dao: MessageDao,
    private val json: Json,
    private val databaseRecovery: DatabaseRecovery,
) : MessageCacheRepository {

    override suspend fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        persistOldBeyondWindow: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        runCatching {
            databaseRecovery.withCorruptionRecovery {
                val oldestId = dao.oldestMessageId(sessionId)
                val oldestCreated = oldestId?.let { dao.messageCreatedAt(it) }
                val toPersist = if (persistOldBeyondWindow || oldestCreated == null) {
                    messages
                } else {
                    messages.filter { m -> m.info.time.created >= oldestCreated }
                }
                if (toPersist.isEmpty()) {
                    if (BuildConfig.DEBUG) {
                        AppLogger.d(TAG, "[upsert] session=$sessionId: all ${messages.size} msgs outside window (oldest cached=$oldestCreated), skip persist")
                    }
                    return@withCorruptionRecovery
                }

                dao.upsertMessages(
                    toPersist.map { m ->
                        CachedMessageEntity(
                            id = m.info.id,
                            sessionId = sessionId,
                            created = m.info.time.created,
                            role = m.info.role,
                            payload = json.encodeToString(m.info),
                        )
                    },
                )
                dao.upsertParts(
                    toPersist.flatMap { m ->
                        m.parts.map { p ->
                            CachedPartEntity(
                                id = p.id,
                                messageId = m.info.id,
                                sessionId = sessionId,
                                type = p.typeName(),
                                text = (p as? Part.Text)?.text,
                                payload = json.encodeToString(p),
                            )
                        }
                    },
                )
                val pruned = dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)
                if (BuildConfig.DEBUG && pruned > 0) {
                    AppLogger.d(TAG, "[prune] session=$sessionId: removed $pruned oldest msgs (limit=$SESSION_MESSAGE_LIMIT)")
                }
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "MessageStore upsert failed (memory view unaffected)", e)
        }
    }

    /** Room Flow：本地库变化 → 自动发新值。损坏时无法包 suspend 恢复（保持现状，写路径已恢复）。 */
    override fun observeMessages(sessionId: String): Flow<List<MessageWithParts>> =
        dao.observeMessages(sessionId).map { entities ->
            if (entities.isEmpty()) return@map emptyList()
            // 一次批量查询所有 parts，消除 N+1（原逐条 dao.partsForMessages）。
            val partsByMsg = dao.partsForMessages(entities.map { it.id })
                .groupBy { it.messageId }
            entities.map { toMessageWithParts(it, partsByMsg[it.id] ?: emptyList()) }
        }

    /** 游标分页读：beforeId 非空取更早，否则取最新 limit 条。 */
    override suspend fun loadRange(sessionId: String, limit: Int, beforeId: String?): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                val entities = dao.messagesForSession(sessionId, limit, beforeId)
                if (entities.isEmpty()) return@withCorruptionRecovery emptyList()
                val partsByMsg = dao.partsForMessages(entities.map { it.id })
                    .groupBy { it.messageId }
                entities.map { toMessageWithParts(it, partsByMsg[it.id] ?: emptyList()) }
            } ?: emptyList()
        }

    override suspend fun oldestMessageId(sessionId: String): String? =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery { dao.oldestMessageId(sessionId) }
        }

    override suspend fun messageCreatedAt(messageId: String): Long? =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery { dao.messageCreatedAt(messageId) }
        }

    override suspend fun clearSession(sessionId: String) {
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery { dao.clearSession(sessionId) }
        }
    }

    // ---- 映射 ----------------------------------------------------

    private fun toMessageWithParts(
        entity: CachedMessageEntity,
        partEntities: List<CachedPartEntity>,
    ): MessageWithParts {
        val info = json.decodeFromString<Message>(entity.payload)
        val parts = partEntities.mapNotNull { partEntity ->
            partEntity.payload?.let {
                runCatching { json.decodeFromString<Part>(it) }
                    .getOrNull()
            }
        }
        return MessageWithParts(info = info, parts = parts)
    }

    /**
     * Part 子类 → type 字符串。与 [dev.leonardo.ocbeacon.domain.model.PartSerializer]
     * 的分发键一致（Permission/Question 在序列化器中未映射，此处补全以穷尽 sealed）。
     */
    private fun Part.typeName(): String = when (this) {
        is Part.Text -> "text"
        is Part.Reasoning -> "reasoning"
        is Part.Tool -> "tool"
        is Part.StepStart -> "step-start"
        is Part.StepFinish -> "step-finish"
        is Part.File -> "file"
        is Part.Snapshot -> "snapshot"
        is Part.Patch -> "patch"
        is Part.Subtask -> "subtask"
        is Part.Compaction -> "compaction"
        is Part.Retry -> "retry"
        is Part.Abort -> "abort"
        is Part.Agent -> "agent"
        is Part.Permission -> "permission"
        is Part.Question -> "question"
        is Part.SessionTurn -> "session-turn"
        is Part.Unknown -> "unknown"
    }

    companion object {
        private const val TAG = "MessageStore"
        const val SESSION_MESSAGE_LIMIT = 1000
    }
}
