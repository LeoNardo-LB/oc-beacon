package dev.leonardo.ocbeacon.data.local

import androidx.room.withTransaction
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
 * （时间窗口 + 200 条/512KB 分桶），桶级 [ARCHIVE_BUCKET_LIMIT] TLRU 保护。
 * 归档入库与热表裁剪在同一 [withTransaction] 内完成（原子，防崩溃后热表/归档并存→重复归档）。
 */
@Singleton
class MessageStore @Inject constructor(
    private val dao: MessageDao,
    private val archiveDao: ArchiveBucketDao,
    private val json: Json,
    private val databaseRecovery: DatabaseRecovery,
    private val database: OcBeaconDatabase,
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
                // ---- 归档编排（prune 前）：count → 查 overflow 最老 → 归档+裁剪原子化 ----
                // overflow>0 时 archiveOverflow 内含事务（upsertAll+pruneToLimit 原子，并返回裁剪数）；
                // overflow==0 时无需裁剪（已在限额内）。裁剪不再单独无条件执行——避免与事务内裁剪重复。
                val total = dao.countForSession(sessionId)
                val overflow = (total - SESSION_MESSAGE_LIMIT).coerceAtLeast(0)
                if (overflow > 0) {
                    val pruned = archiveOverflow(sessionId, overflow)
                    if (BuildConfig.DEBUG && pruned > 0) {
                        AppLogger.d(TAG, "[prune] session=$sessionId: removed $pruned oldest msgs (limit=$SESSION_MESSAGE_LIMIT)")
                    }
                }
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "MessageStore upsert failed (memory view unaffected)", e)
        }
    }

    /**
     * 归档"将被 prune 的最老 [overflow] 条"（此时仍在热表，可查完整 payload）→ 原子裁剪。
     *
     * 流程：按时间窗口分桶 → zstd 压缩（CPU，在事务外执行，不持写锁）→
     * 与裁剪同事务入库（[withTransaction]：archiveDao.upsertAll + dao.pruneToLimit，
     * 防崩溃后"消息既在热表又在归档→下次重复归档→去归档看到重复"）。
     *
     * 归档是增强：压缩/入库失败则降级为"仅裁剪"（保持一期"数据丢弃"语义，绝不因归档失败
     * 而让热表无限增长）。返回实际裁剪条数。
     */
    private suspend fun archiveOverflow(sessionId: String, overflow: Int): Int {
        // 1. 归档增强（best-effort）：查最老 overflow 条 → 组装 DTO → 压缩分桶（事务外）。
        val buckets = runCatching {
            val candidates = dao.oldestMessages(sessionId, overflow)
            if (candidates.isEmpty()) return@runCatching emptyList()
            val partsByMsg = partsForMessagesChunked(candidates.map { it.id })
                .groupBy { it.messageId }
            // 逐条容错：单条 payload 解码失败只跳过该条（记日志），不影响整批归档。
            // 否则一条坏消息会导致全部 overflow 消息归档失败 → 整批数据丢失（一期语义降级）。
            val messages = candidates.mapNotNull { entity ->
                runCatching {
                    ArchivedMessageDto(
                        info = json.decodeFromString<Message>(entity.payload),
                        parts = (partsByMsg[entity.id] ?: emptyList()).mapNotNull { pe ->
                            pe.payload?.let { runCatching { json.decodeFromString<Part>(it) }.getOrNull() }
                        },
                    )
                }.onFailure { e ->
                    AppLogger.e(TAG, "[archive] session=$sessionId: skip undecodable msg ${entity.id} (${e.message})")
                }.getOrNull()
            }
            buildArchiveBuckets(sessionId, messages)
        }.onFailure { e ->
            AppLogger.e(TAG, "[archive] session=$sessionId: archive build failed (prune-only fallback)", e)
        }.getOrDefault(emptyList())

        // 2. 裁剪（必须）：归档成功 → 与 upsertAll 同事务（原子）；归档失败 → 单独裁剪（防无限增长）。
        val pruned = if (buckets.isNotEmpty()) {
            database.withTransaction {
                archiveDao.upsertAll(buckets)
                dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)
            }
        } else {
            dao.pruneToLimit(sessionId, SESSION_MESSAGE_LIMIT)
        }

        if (buckets.isNotEmpty()) {
            enforceArchiveLimit(sessionId)
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "[archive] session=$sessionId: archived ${buckets.sumOf { it.messageCount }} msgs → ${buckets.size} buckets; pruned $pruned")
            }
        }
        return pruned
    }

    /**
     * 按时间窗口分桶；超 [ARCHIVE_BUCKET_MAX_MESSAGES] 条或 [ARCHIVE_BUCKET_MAX_BYTES] 字节切子桶。
     *
     * 字节上限守 Android CursorWindow（2MB）：单桶未压缩 JSON > 512KB 时递归对半切分，
     * 直至 ≤512KB（单条消息本身超 512KB 时无法再切，原样入桶——此时压缩后 BLOB 仍远低于 2MB，
     * 见 [ZstdCodec]；最坏情况仅此一条）。
     */
    internal fun buildArchiveBuckets(sessionId: String, messages: List<ArchivedMessageDto>): List<ArchiveBucketEntity> {
        val now = clock()
        return messages.groupBy { m ->
            m.info.time.created / ARCHIVE_BUCKET_WINDOW_MS
        }.flatMap { (_, group) ->
            group.chunked(ARCHIVE_BUCKET_MAX_MESSAGES).flatMap { chunk -> splitByByteLimit(chunk, now, sessionId) }
        }
    }

    /** 单个 200 条 chunk：若未压缩 JSON ≤ 512KB 直接成桶，否则对半递归直至满足字节上限。 */
    private fun splitByByteLimit(
        chunk: List<ArchivedMessageDto>,
        now: Long,
        sessionId: String,
    ): List<ArchiveBucketEntity> {
        val jsonBytes = json.encodeToString(chunk).toByteArray(Charsets.UTF_8)
        if (jsonBytes.size <= ARCHIVE_BUCKET_MAX_BYTES || chunk.size <= 1) {
            return listOf(chunk.toBucket(sessionId, now, jsonBytes))
        }
        val mid = chunk.size / 2
        return splitByByteLimit(chunk.subList(0, mid), now, sessionId) +
            splitByByteLimit(chunk.subList(mid, chunk.size), now, sessionId)
    }

    private fun List<ArchivedMessageDto>.toBucket(sessionId: String, now: Long, jsonBytes: ByteArray): ArchiveBucketEntity =
        ArchiveBucketEntity(
            sessionId = sessionId,
            bucketStart = minOf { it.info.time.created },
            bucketEnd = maxOf { it.info.time.created },
            messageCount = size,
            uncompressedSize = jsonBytes.size,
            payload = ZstdCodec.compress(jsonBytes),
            createdAt = now,
            lastAccessedAt = now,
        )

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
            val partsByMsg = partsForMessagesChunked(entities.map { it.id })
                .groupBy { it.messageId }
            entities.map { toMessageWithParts(it, partsByMsg[it.id] ?: emptyList()) }
        }

    /** 游标分页读：beforeId 非空取更早，否则取最新 limit 条。 */
    override suspend fun loadRange(sessionId: String, limit: Int, beforeId: String?): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                // #51：拆两条查询（无 OR 子句，避免 SQLite 放弃复合索引）
                val entities = if (beforeId != null) {
                    dao.messagesBefore(sessionId, beforeId, limit)
                } else {
                    dao.messagesForSession(sessionId, limit)
                }
                if (entities.isEmpty()) return@withCorruptionRecovery emptyList()
                val partsByMsg = partsForMessagesChunked(entities.map { it.id })
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
                // 热表 + 归档同事务清空（原子）：避免清一半崩溃留下半边残留。
                database.withTransaction {
                    dao.clearSession(sessionId)
                    archiveDao.clearSession(sessionId)
                }
            }
        }
    }

    /**
     * 游标分页读归档：从 [beforeCreated] 往更早方向逐桶解压。
     *
     * 注：返回顺序是 advisory——按桶粒度凑满 [limit]，桶间/桶内可能非严格 created 升序
     * （APPEND_ONLY 合并容忍；UI 侧统一按 created 重排，见 [decodeBucket] 与 UseCase 的 merge）。
     * 跨桶可能因 ULID 毫秒精度出现同 bucketEnd 游标跳过（极罕见，接受现状）。
     */
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
        is Part.Shell -> "shell"
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

    /**
     * 分块批量查询 parts：SQLite 的 IN 子句有 999 变量上限，
     * 大会话（>999 条消息）直接 IN 查询会抛 SQLiteException:
     * "too many SQL variables"（2026-08-10 模拟器实证 1896 条消息会话触发）。
     * 切成 ≤900 的块分别查询后合并——结果与单次查询等价。
     */
    private suspend fun partsForMessagesChunked(messageIds: List<String>): List<CachedPartEntity> =
        dao.partsForMessagesChunked(messageIds)

    companion object {
        private const val TAG = "MessageStore"
        const val SESSION_MESSAGE_LIMIT = 1000
        const val ARCHIVE_BUCKET_WINDOW_MS = 86_400_000L          // 1 天
        const val ARCHIVE_BUCKET_MAX_BYTES = 512 * 1024           // 512KB（调研约束）
        const val ARCHIVE_BUCKET_MAX_MESSAGES = 200
        const val ARCHIVE_BUCKET_LIMIT = 200                      // 每会话桶保护上限 ≈ 20 万条历史
    }
}
