package dev.leonardo.ocbeacon.data.local

import androidx.room.withTransaction
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.runCatchingCancellable
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
 * （时间窗口 + 200 条/512KB 分桶）。#271：桶无上限（全量保留），无自动淘汰。
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

    /** #272：FTS5 内容索引（运行时探测可用性；API<30 自动降级 LIKE）。 */
    private val fts = MessageFtsIndex(database, databaseRecovery)

    /**
     * #97（H-6）：SSE delta 增量落盘——按 part 追加文本（O(delta) 写），
     * 替代原每 48ms 批整条消息 JSON 编码 + 全行重写（写放大 ~20/s）。
     * 消息骨架（元数据）由调用方随请求传入（handler 持有内存最新状态）；
     * delta 追加到 part 行，ended 时由 [upsertMessages] 全量覆盖最终文本。
     */
    override suspend fun appendPartTexts(
        sessionId: String,
        messages: List<MessageWithParts>,
        deltas: List<PartDelta>,
    ) = withContext(Dispatchers.IO) {
        if (deltas.isEmpty() || messages.isEmpty()) return@withContext
        // #230（残余通道封堵）：SSE started 的 part 注册可能以 null/blank delta
        // 进批 → INSERT 出 text=NULL 的零信息行（实测每助手消息一条
        // `<msg>_reasoning_ord_0` NULL 行，启动清扫再删、开 会话再写的循环）。
        // 过滤后行等到首个非空 delta 才由 UPSERT INSERT 建立——语义不变。
        val realDeltas = deltas.filter { it.delta.isNotBlank() }
        if (realDeltas.isEmpty()) return@withContext
        runCatchingCancellable {
            databaseRecovery.withCorruptionRecovery {
                // #10（2026-09-01 FK 787 根治）：骨架插入与 delta 追加**同事务原子提交**。
                // 原两步独立提交间可插入并发 replaceSessionMessages（prefetchJumpTargets
                // 服务器权威替换：clear+重写，权威集不含流式中消息）→ 骨架被清 →
                // appendPartText FK 787 → 本批 delta 全丢（真机 logs 表两次 ERROR 定音）。
                // 事务化后：整体先于替换提交（一并被权威重写收敛）或整体后于（骨架+
                // 部件俱在）——FK 孤儿结构性不可能。
                //
                // 骨架消息存在即跳过（#266：原 REPLACE = DELETE+INSERT，FK
                // ON DELETE CASCADE 会把该消息全部 part 行级联删光，随后
                // 只插回本批 delta → part 行永远停留在最后一批）。IGNORE
                // 只保证 part 的 FK 依赖存在，永不触发级联。
                database.withTransaction {
                    dao.insertMessagesIfAbsent(messages.map { m ->
                        CachedMessageEntity(
                            id = m.info.id,
                            sessionId = sessionId,
                            created = m.info.time.created,
                            role = m.info.role,
                            payload = json.encodeToString(m.info),
                        )
                    })
                    realDeltas.forEach { d ->
                        dao.appendPartText(
                            partId = d.partId,
                            messageId = d.messageId,
                            sessionId = d.sessionId,
                            type = d.type,
                            delta = d.delta,
                        )
                    }
                }
            }
        }.onFailure { e ->
            AppLogger.e(TAG, "appendPartTexts failed (memory view unaffected)", e)
        }
    }

    /** #228（炸弹清扫）：全库删除空 Text/Reasoning part——Room 历史炸弹行（#223 残留）
     *  随会话种子回灌热视图打挂 merge；空文本零信息，删除安全。 */
    override suspend fun sweepEmptyStreamParts(): Int = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            databaseRecovery.withCorruptionRecovery { dao.deleteEmptyStreamParts() } ?: 0
        }.onFailure { e ->
            AppLogger.e(TAG, "sweepEmptyStreamParts failed", e)
        }.getOrDefault(0)
    }

    /** #97（H-6）：ended 覆盖最终文本（防增量与 REST 快照漂移）。 */
    override suspend fun updatePartText(
        sessionId: String,
        partId: String,
        text: String,
    ) {
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                databaseRecovery.withCorruptionRecovery { dao.updatePartText(partId, text) }
            }.onFailure { e ->
                AppLogger.e(TAG, "updatePartText failed (memory view unaffected)", e)
            }
        }
    }

    override suspend fun upsertMessages(
        sessionId: String,
        messages: List<MessageWithParts>,
        persistOldBeyondWindow: Boolean,
    ) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext
        runCatchingCancellable {
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

                // #10（2026-09-01 FK 787 同源加固）：消息行 REPLACE（= DELETE+INSERT，
                // 对 cached_parts 级联删）与 parts 重写同事务原子——并发权威替换
                // （replaceSessionMessages 的 clear+重写）插入两步之间时，parts 落库
                // 同样触发 FK 787。归档/裁剪（archiveOverflow 自持事务）保持事务外，
                // 不构成嵌套。
                // [299-probe] DEBUG 观测：页落库分段计时（tx/FTS/归档）
                val probeT0 = android.os.SystemClock.elapsedRealtime()
                // #299 续项：写前快照现存 part 文本——FTS 只索引「新增或文本变化」
                // 的 part（幂等跳过；原 DELETE FROM fts WHERE partId=? 在 FTS5 虚表
                // 上全表扫描 ~600ms/次，重进场/多写者重复索引同一批 part 时是页
                // 后处理的主成本，真机探针 fts=13.6s/50 msgs 实证）。
                val incomingIds = toPersist.flatMap { m ->
                    m.parts.mapIndexed { index, p -> p.id.ifEmpty { "${m.info.id}_p$index" } }
                }
                val existingTexts = HashMap<String, String?>()
                incomingIds.chunked(SQLITE_IN_CHUNK).forEach { chunk ->
                    dao.existingPartTexts(chunk).forEach { existingTexts[it.id] = it.text }
                }
                database.withTransaction {
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
                            m.parts.mapIndexed { index, p ->
                                CachedPartEntity(
                                    // 2026-08-12 修复：空 part id（REST 加载历史产生 ""）
                                    // 主键冲突互相覆盖（实测 35 条 user 消息只剩 1 条有 parts）
                                    // ——落库时生成稳定唯一 id（消息 id + 位置索引）
                                    id = p.id.ifEmpty { "${m.info.id}_p$index" },
                                    messageId = m.info.id,
                                    sessionId = sessionId,
                                    type = p.typeName(),
                                    text = (p as? Part.Text)?.text,
                                    // #79 P0（2026-08-18）：tool part 落库截断——
                                    // 工具返回值占 DB 97%（12.4MB/28MB 实测），本地只存
                                    // 500 字符预览；内存渲染不受影响（消息在内存时完整），
                                    // 服务器保留全量可重拉。非 tool part 原样。
                                    payload = when (p) {
                                        // #79：tool（output/input/metadata）落库截 500 字符预览
                                        // ——DB 体积大头（97% 实测），全量服务器可重拉。
                                        // #271（2026-08-30 用户裁决）：reasoning 截断取消——
                                        // 全量保留，重进思考卡内容完整；text part 本就不截。
                                        is Part.Tool -> ToolOutputTruncator.truncateIfNeeded(json.encodeToString(p))
                                        else -> json.encodeToString(p)
                                    },
                                )
                            }
                        },
                    )
                }
                val probeT1 = android.os.SystemClock.elapsedRealtime()
                // #272：FTS5 增量索引（#299 幂等收窄：仅「新增或文本变化」的 part
                // ——文本未变跳过，重进场零 FTS；FTS 行不删，冷数据保持可搜）
                fts.indexTextParts(
                    sessionId,
                    toPersist.flatMap { m ->
                        m.parts.mapIndexed { index, p ->
                            (p as? Part.Text)?.let { t ->
                                val partId = p.id.ifEmpty { "${m.info.id}_p$index" }
                                // 快照文本一致 → 索引行已正确，跳过
                                if (existingTexts[partId] == t.text) null
                                else IndexedTextPart(
                                    partId = partId,
                                    messageId = m.info.id,
                                    role = m.info.role,
                                    text = t.text,
                                    existing = existingTexts.containsKey(partId),
                                )
                            }
                        }.filterNotNull()
                    },
                )
                val probeT2 = android.os.SystemClock.elapsedRealtime()
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
                if (BuildConfig.DEBUG) {
                    val probeT3 = android.os.SystemClock.elapsedRealtime()
                    AppLogger.d(
                        TAG,
                        "[299-probe] upsert n=" + toPersist.size + " tx=" + (probeT1 - probeT0) +
                            "ms fts=" + (probeT2 - probeT1) + "ms archive=" + (probeT3 - probeT2) +
                            "ms total=" + (probeT3 - probeT0) + "ms overflow=" + overflow,
                    )
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
        val buckets = runCatchingCancellable {
            val candidates = dao.oldestMessages(sessionId, overflow)
            if (candidates.isEmpty()) return@runCatchingCancellable emptyList()
            val partsByMsg = partsForMessagesChunked(candidates.map { it.id })
                .groupBy { it.messageId }
            // 逐条容错：单条 payload 解码失败只跳过该条（记日志），不影响整批归档。
            // 否则一条坏消息会导致全部 overflow 消息归档失败 → 整批数据丢失（一期语义降级）。
            val messages = candidates.mapNotNull { entity ->
                runCatchingCancellable {
                    ArchivedMessageDto(
                        info = json.decodeFromString<Message>(entity.payload),
                        parts = (partsByMsg[entity.id] ?: emptyList()).mapNotNull { pe ->
                            pe.payload?.let { runCatchingCancellable { json.decodeFromString<Part>(it) }.getOrNull() }
                        },
                    )
                }.onFailure { e ->
                    AppLogger.e(TAG, "[archive] session=$sessionId: skip undecodable msg ${entity.id}", e)
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
            // #271（2026-08-30 用户裁决）：冷存桶 LRU 淘汰移除——全量保留，
            // 桶无上限；占用统计+手动清理由设置页承担（防失控兜底）。
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
                    // 2026-09-01（定位跳转失效根因）：DAO 谓词改为 created 主游标——
                    // 先取 beforeId 的 created（DSH seq-N / ULID 通用；缺失回退纯 id 无条件
                    // 下限 = 空窗口，由调用方按「无更早」处理）。
                    val created = dao.messageCreatedAt(beforeId)
                    if (created != null) {
                        dao.messagesBefore(sessionId, created, beforeId, limit)
                    } else {
                        emptyList()
                    }
                } else {
                    dao.messagesForSession(sessionId, limit)
                }
                if (entities.isEmpty()) return@withCorruptionRecovery emptyList()
                val partsByMsg = partsForMessagesChunked(entities.map { it.id })
                    .groupBy { it.messageId }
                entities.map { toMessageWithParts(it, partsByMsg[it.id] ?: emptyList()) }
            } ?: emptyList()
        }

    /** 向新方向游标分页读（loadAround 本地分支用）。模式同 [loadRange]，仅 DAO 查询方向不同。 */
    override suspend fun loadRangeNewer(sessionId: String, limit: Int, afterId: String): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                val created = dao.messageCreatedAt(afterId)
                if (created == null) return@withCorruptionRecovery emptyList()
                val entities = dao.messagesAfter(sessionId, created, afterId, limit)
                if (entities.isEmpty()) return@withCorruptionRecovery emptyList()
                val partsByMsg = partsForMessagesChunked(entities.map { it.id })
                    .groupBy { it.messageId }
                entities.map { toMessageWithParts(it, partsByMsg[it.id] ?: emptyList()) }
            } ?: emptyList()
        }

    /** 快速导航全量列表：role='user' 的最近 limit 条（含 parts）。 */
    override suspend fun userMessages(sessionId: String, limit: Int): List<MessageWithParts> =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                val entities = dao.userMessages(sessionId, limit)
                if (entities.isEmpty()) return@withCorruptionRecovery emptyList()
                val partsByMsg = partsForMessagesChunked(entities.map { it.id })
                    .groupBy { it.messageId }
                entities.map { toMessageWithParts(it, partsByMsg[it.id] ?: emptyList()) }
            } ?: emptyList()
        }

    /** 单条消息查询（loadAround 本地分支取 target）。null = 不在热表。 */
    override suspend fun messageById(sessionId: String, messageId: String): MessageWithParts? =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                val entity = dao.messageById(sessionId, messageId)
                    ?: return@withCorruptionRecovery null
                val parts = dao.partsForMessagesChunked(listOf(messageId))
                toMessageWithParts(entity, parts)
            }
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
                    // #272：FTS 行级联清除（会话删除=本地全清）
                    fts.clearSession(sessionId)
                }
            }
        }
    }

    /**
     * 2026-08-16（快速定位缺失根治·对账）：服务器权威全量替换热表（清+写
     * 同事务原子）。归档不动——若被替换集小于热表限额，历史归档保持分层。
     */
    override suspend fun replaceSessionMessages(sessionId: String, messages: List<MessageWithParts>) {
        if (messages.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                // [299-probe] DEBUG 观测
                val probeT0 = android.os.SystemClock.elapsedRealtime()
                // #299 续项：clear 前快照（replace 删行重建——文本未变的 part 其
                // FTS 行仍正确，跳过重索引）
                val existingTexts = HashMap<String, String?>()
                messages.flatMap { m ->
                    m.parts.mapIndexed { index, p -> p.id.ifEmpty { "${m.info.id}_p$index" } }
                }.chunked(SQLITE_IN_CHUNK).forEach { chunk ->
                    dao.existingPartTexts(chunk).forEach { existingTexts[it.id] = it.text }
                }
                databaseRecovery.withCorruptionRecovery {
                    database.withTransaction {
                        dao.clearSession(sessionId)
                        upsertInTransaction(sessionId, messages, existingTexts)
                    }
                }
                val probeT1 = android.os.SystemClock.elapsedRealtime()
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "[299-probe] replace n=" + messages.size + " txTotal=" + (probeT1 - probeT0) + "ms")
                }
            }.onFailure { e ->
                AppLogger.e(TAG, "replaceSessionMessages failed (keep existing cache)", e)
            }
        }
    }

    /** 事务内写入（不嵌套 withTransaction；不触发归档——对账场景写入量=服务器全量，超限由下次常规 upsert 的 prune 管理）。
     *  [existingTexts]：写前快照（#299 FTS 幂等收窄判据，由调用方采集传入）。 */
    private suspend fun upsertInTransaction(
        sessionId: String,
        messages: List<MessageWithParts>,
        existingTexts: Map<String, String?>,
    ) {
        dao.upsertMessages(
            messages.map { m ->
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
            messages.flatMap { m ->
                m.parts.mapIndexed { index, p ->
                    CachedPartEntity(
                        id = p.id.ifEmpty { "${m.info.id}_p$index" },
                        messageId = m.info.id,
                        sessionId = sessionId,
                        type = p.typeName(),
                        text = (p as? Part.Text)?.text,
                        // #299 续项：与 upsertMessages 对齐 #79 工具载荷截断——原全量
                        // encode 把 #79 省下的 97% DB 体积整会话写回（prefetch 对账
                        // 路径每进场一次），大 tool 会话（test-lab 实测）后处理拖到
                        // 数十秒；内存渲染不受影响（内存态完整，DB 只存 500 字符预览）。
                        payload = when (p) {
                            is Part.Tool -> ToolOutputTruncator.truncateIfNeeded(json.encodeToString(p))
                            else -> json.encodeToString(p)
                        },
                    )
                }
            },
        )
        // #272：REST_AUTHORITY 全量替换路径同样维护 FTS 索引（#299 幂等收窄同上）
        fts.indexTextParts(
            sessionId,
            messages.flatMap { m ->
                m.parts.mapIndexed { index, p ->
                    (p as? Part.Text)?.let { t ->
                        val partId = p.id.ifEmpty { "${m.info.id}_p$index" }
                        if (existingTexts[partId] == t.text) null
                        else IndexedTextPart(
                            partId = partId,
                            messageId = m.info.id,
                            role = m.info.role,
                            text = t.text,
                            existing = existingTexts.containsKey(partId),
                        )
                    }
                }.filterNotNull()
            },
        )
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
            // #49+#50（2026-08-11）：原实现每桶 1 查询 + 1 touch 写（N+1）；
            // 现一次查询 limit 个桶（每桶 ≥1 条消息 → limit 桶必凑满），按需
            // 解码 + 每桶只取窗口内最新 need 条——消除 N+1 且减少解压浪费。
            // 语义：SQL 保证返回桶 bucketEnd < beforeCreated → 桶内全部在窗口内。
            val buckets = archiveDao.latestBefore(sessionId, beforeCreated, limit = limit.coerceAtLeast(1))
            if (buckets.isEmpty()) return@withCorruptionRecovery emptyList()
            val result = mutableListOf<MessageWithParts>()
            var need = limit
            for (bucket in buckets) {
                if (need <= 0) break
                val decoded = runCatchingCancellable { decodeBucket(bucket) }.getOrElse { e ->
                    AppLogger.e(TAG, "[dearchive] session=$sessionId bucket=${bucket.id}: decode failed, skipping", e)
                    emptyList()
                }
                archiveDao.touch(bucket.id, clock())
                // #72 根治：latestBefore 已按 bucketStart 相交返回"可能含更早消息"的桶——
                // 桶内按消息级 created 过滤（游标推进到消息级时桶内剩余消息不再被跳过）
                val inWindow = decoded.filter { it.info.time.created < beforeCreated }
                if (BuildConfig.DEBUG && inWindow.isNotEmpty()) {
                    AppLogger.d(TAG, "[dearchive] session=$sessionId bucket=${bucket.id}: ${inWindow.size}/${decoded.size} msgs (before=$beforeCreated)")
                }
                // 桶内升序 → takeLast 取最新 need 条（该桶更旧部分不解压浪费到结果中）
                val take = inWindow.takeLast(need)
                result.addAll(take)
                need -= take.size
            }
            result.sortedBy { it.info.time.created }
        } ?: emptyList()
    }

    override suspend fun hasArchivedMessages(sessionId: String, beforeCreated: Long): Boolean =
        withContext(Dispatchers.IO) {
            databaseRecovery.withCorruptionRecovery {
                // #72：latestBefore 现按 bucketStart 相交——相交桶内最老消息 = bucketStart
                // < beforeCreated → 桶内必有更早消息（与 loadArchivedRange 的 filter 语义一致）
                archiveDao.latestBefore(sessionId, beforeCreated, limit = 1).isNotEmpty()
            } ?: false
        }

    /** 解压单个冷存桶 → MessageWithParts 列表（created 升序）。 */
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
                runCatchingCancellable { json.decodeFromString<Part>(it) }
                    .getOrNull()
            }
        }
        return MessageWithParts(info = info, parts = parts)
    }

    /**
     * Part 子类 → type 字符串。与 [dev.leonardo.ocbeacon.domain.model.PartSerializer]
     * 的分发键一致（#200 F01 后双向对称；缓存回读 payload 无 type 字段，
     * 序列化器经顶层字段推断路径解码——见 PartSerializer 兜底分支）。
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

        /** SQLite IN 参数段长（#299 快照批查）。 */
        private const val SQLITE_IN_CHUNK = 500
        const val SESSION_MESSAGE_LIMIT = MessageCacheRepository.SESSION_MESSAGE_LIMIT
        const val ARCHIVE_BUCKET_WINDOW_MS = 86_400_000L          // 1 天
        const val ARCHIVE_BUCKET_MAX_BYTES = 512 * 1024           // 512KB（调研约束）
        const val ARCHIVE_BUCKET_MAX_MESSAGES = 200
    }
}
