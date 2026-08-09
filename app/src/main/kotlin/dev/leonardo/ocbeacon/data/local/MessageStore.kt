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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消息本地缓存存储（Room）。
 *
 * 限量策略：每会话最近 [SESSION_MESSAGE_LIMIT] 条；翻页拉到窗口外的更早消息
 * 默认不落库（persistOldBeyondWindow=false），避免"写了又被裁"循环。
 *
 * 归档：超 [SESSION_MESSAGE_LIMIT] 时，prune 删除前先整桶 zstd 归档到 archive_buckets
 * （时间窗口 + 200 条分桶），桶级 [ARCHIVE_BUCKET_LIMIT] TLRU 保护。
 */
@Singleton
class MessageStore @Inject constructor(
    private val dao: MessageDao,
    private val archiveDao: ArchiveBucketDao,
    private val json: Json,
    private val databaseRecovery: DatabaseRecovery,
    private val clock: () -> Long = System::currentTimeMillis,
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
                // ---- 归档编排（prune 前）：count → 查 overflow 最老 → 归档 → prune 删 ----
                val total = dao.countForSession(sessionId)
                val overflow = (total - SESSION_MESSAGE_LIMIT).coerceAtLeast(0)
                if (overflow > 0) {
                    archiveOverflow(sessionId, overflow)
                }
                val pruned = dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)
                if (BuildConfig.DEBUG && pruned > 0) {
                    AppLogger.d(TAG, "[prune] session=$sessionId: removed $pruned oldest msgs (limit=$SESSION_MESSAGE_LIMIT)")
                }
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "MessageStore upsert failed (memory view unaffected)", e)
        }
    }

    /**
     * 归档"将被 prune 的最老 [overflow] 条"（此时仍在热表，可查完整 payload）。
     * 按时间窗口分桶 → zstd 压缩 → 写归档表。
     * 失败不抛（归档是增强，正确性仍由热表 + 服务端保证；数据按一期行为丢弃）。
     */
    private suspend fun archiveOverflow(sessionId: String, overflow: Int) {
        runCatching {
            val candidates = dao.oldestMessages(sessionId, overflow)
            if (candidates.isEmpty()) return@runCatching
            val partsByMsg = dao.partsForMessages(candidates.map { it.id })
                .groupBy { it.messageId }
            val messages = candidates.map { entity ->
                ArchivedMessageDto(
                    info = json.decodeFromString<Message>(entity.payload),
                    parts = (partsByMsg[entity.id] ?: emptyList()).mapNotNull { pe ->
                        pe.payload?.let { runCatching { json.decodeFromString<Part>(it) }.getOrNull() }
                    },
                )
            }
            val buckets = buildArchiveBuckets(sessionId, messages)
            buckets.forEach { bucket -> archiveDao.upsert(bucket) }
            enforceArchiveLimit(sessionId)
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[archive] session=$sessionId: archived ${messages.size} msgs → ${buckets.size} buckets")
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "[archive] session=$sessionId: archive failed (data dropped as before)", e)
        }
    }

    /** 按时间窗口分桶；超 [ARCHIVE_BUCKET_MAX_MESSAGES] 条切子桶。返回待写桶列表。 */
    internal fun buildArchiveBuckets(sessionId: String, messages: List<ArchivedMessageDto>): List<ArchiveBucketEntity> {
        val now = clock()
        return messages.groupBy { m ->
            m.info.time.created / ARCHIVE_BUCKET_WINDOW_MS
        }.flatMap { (_, group) ->
            group.chunked(ARCHIVE_BUCKET_MAX_MESSAGES).map { chunk ->
                val jsonBytes = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
                ArchiveBucketEntity(
                    sessionId = sessionId,
                    bucketStart = chunk.minOf { it.info.time.created },
                    bucketEnd = chunk.maxOf { it.info.time.created },
                    messageCount = chunk.size,
                    uncompressedSize = jsonBytes.size,
                    payload = ZstdCodec.compress(jsonBytes),
                    createdAt = now,
                    lastAccessedAt = now,
                )
            }
        }
    }

    /** 保护上限：每会话超 [ARCHIVE_BUCKET_LIMIT] 桶时删最久未访问。 */
    private suspend fun enforceArchiveLimit(sessionId: String) {
        val current = archiveDao.count(sessionId)
        if (current <= ARCHIVE_BUCKET_LIMIT) return
        val excess = current - ARCHIVE_BUCKET_LIMIT
        archiveDao.leastAccessed(sessionId, excess).forEach { archiveDao.delete(it.id) }
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "[archive] session=$sessionId: evicted $excess least-accessed buckets (limit=$ARCHIVE_BUCKET_LIMIT)")
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
            databaseRecovery.withCorruptionRecovery {
                dao.clearSession(sessionId)
                archiveDao.clearSession(sessionId)
            }
        }
    }

    override suspend fun loadArchivedRange(
        sessionId: String,
        limit: Int,
        beforeCreated: Long,
    ): List<MessageWithParts> = withContext(Dispatchers.IO) {
        databaseRecovery.withCorruptionRecovery {
            val result = mutableListOf<MessageWithParts>()
            var beforeEnd = beforeCreated
            var need = limit
            while (need > 0) {
                val buckets = archiveDao.latestBefore(sessionId, beforeEnd, limit = 1)
                if (buckets.isEmpty()) break
                val bucket = buckets[0]
                val decoded = runCatching { decodeBucket(bucket) }.getOrElse { e ->
                    AppLogger.e(TAG, "[dearchive] session=$sessionId bucket=${bucket.id}: decode failed, skipping", e)
                    emptyList()
                }
                archiveDao.touch(bucket.id, clock())
                result.addAll(decoded)
                if (BuildConfig.DEBUG && decoded.isNotEmpty()) {
                    AppLogger.d(TAG, "[dearchive] session=$sessionId bucket=${bucket.id}: ${decoded.size} msgs (before=$beforeEnd)")
                }
                need -= decoded.size
                beforeEnd = bucket.bucketStart  // 下个桶必须更早（用桶起点做游标，避免边界重复）
                if (decoded.isEmpty()) continue  // 坏桶跳过，游标已推进到 bucketStart，不会死循环
            }
            result
        } ?: emptyList()
    }

    override suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                archiveDao.latestBefore(sessionId, beforeCreated, limit = 1).isNotEmpty()
            } ?: false
        }

    /** 解压单个归档桶 → MessageWithParts 列表（created 升序）。 */
    private fun decodeBucket(bucket: ArchiveBucketEntity): List<MessageWithParts> {
        val bytes = ZstdCodec.decompress(bucket.payload, bucket.uncompressedSize)
        val dtos = json.decodeFromString<List<ArchivedMessageDto>>(bytes.decodeToString())
        return dtos.map { dto -> MessageWithParts(info = dto.info, parts = dto.parts) }
            .sortedBy { it.info.time.created }
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
        const val ARCHIVE_BUCKET_WINDOW_MS = 86_400_000L          // 1 天
        const val ARCHIVE_BUCKET_MAX_BYTES = 512 * 1024           // 512KB（调研约束）
        const val ARCHIVE_BUCKET_MAX_MESSAGES = 200
        const val ARCHIVE_BUCKET_LIMIT = 200                      // 每会话桶保护上限 ≈ 20 万条历史
    }
}
