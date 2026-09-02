package dev.leonardo.ocbeacon.data.local

import android.database.sqlite.SQLiteException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单条内容检索命中。rank = FTS5 bm25()（越小越相关）；LIKE 降级路径 rank=null（按时间排序）。 */
data class ContentSearchHit(
    val sessionId: String,
    val messageId: String,
    val role: String,
    val snippet: String,
    val created: Long,
    val rank: Double?,
)

/** 检索过滤条件（全部可选，组合生效）。 */
data class ContentSearchFilter(
    val sessionId: String? = null,
    val role: String? = null,
    val timeFrom: Long? = null,
    val timeTo: Long? = null,
    val limit: Int = 50,
)

/**
 * #272/Q6c：过滤取值 token（UI 过滤 chip ↔ 过滤条件共享的稳定词表）。
 * role 值与消息表 role 字段一致（user/assistant）；时间档为 UI 约定档位。
 */
object ContentSearchFilterValues {
    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
    const val TIME_RANGE_7D = "7d"
    const val TIME_RANGE_30D = "30d"
}

/** FTS5 DDL（unicode61 单字分词——中文短词零依赖方案；版本号留重建路径）。 */
object MessageFtsSchema {
    const val TABLE = "message_fts"
    const val CREATE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING fts5(" +
            "text, " +
            "sessionId UNINDEXED, " +
            "messageId UNINDEXED, " +
            "partId UNINDEXED, " +
            "role UNINDEXED, " +
            "tokenize = 'unicode61')"
}

/** 索引单元：一条 text part 的可检索内容。 */
data class IndexedTextPart(
    val partId: String,
    val messageId: String,
    val role: String,
    val text: String,
    /**
     * #299 续项：该 part 索引前在 cached_parts 已有行（文本变化重索引）——
     * 仅此类项需要 DELETE 旧行；新 part 无 FTS 行可删（FTS 行只可能跟随
     * cached_parts 行存在），免 FTS5 虚表全扫（~600ms/次）。
     */
    val existing: Boolean = false,
)

/**
 * #272：消息内容全文索引（FTS5）+ BM25 检索。
 *
 * - 只索引 text part（user/assistant 正文，落库本就不截断）；reasoning/工具输出不入索引（用户裁决）。
 * - 索引行独立于热/冷分层：prune 不删 FTS 行（冷数据可搜）；删会话按 sessionId 清。
 * - 陈旧 FTS 行（消息被单独删除的残余）由查询 JOIN cached_messages 天然过滤，不显示。
 * - 运行时探测 FTS5 可用性：API<30 系统无 FTS5 模块 → available=false，调用方走 LIKE 降级。
 * - 非线程安全内部状态由 [synchronized] 保护；方法为阻塞式，调用方须在 IO 上下文。
 */
@Singleton
class MessageFtsIndex @Inject constructor(
    private val database: OcBeaconDatabase,
    private val databaseRecovery: DatabaseRecovery,
) {
    private val lock = Any()
    private var ensured = false
    private var unavailable = false

    /**
     * 幂等建表；返回 FTS5 是否可用（不可用 = 无 fts5 模块，调用方走 LIKE 降级）。
     * #272 V3 勘误：小米 ROM（SDK 36）也可能无 fts5 模块——失败根因必须留日志。
     */
    fun ensureAvailable(): Boolean = synchronized(lock) {
        if (ensured) return true
        if (unavailable) return false
        try {
            val db = database.openHelper.writableDatabase
            db.execSQL(MessageFtsSchema.CREATE)
            ensured = true
            // 一次性回填：建表时把既有 text part 全量灌入索引（后续增量由写路径维护）。
            // 仅建表当次执行（表已存在时不触发）；role 经消息表回查。
            db.execSQL(
                "INSERT INTO ${MessageFtsSchema.TABLE}(sessionId, messageId, partId, role, text) " +
                    "SELECT p.sessionId, p.messageId, p.id, " +
                    "COALESCE((SELECT mm.role FROM cached_messages mm WHERE mm.id = p.messageId), ''), " +
                    "COALESCE(p.text, '') FROM cached_parts p WHERE p.type = 'text'"
            )
            android.util.Log.i("MessageFtsIndex", "FTS5 virtual table ready (backfilled)")
            true
        } catch (e: SQLiteException) {
            android.util.Log.w(
                "MessageFtsIndex",
                "FTS5 unavailable, LIKE fallback engaged: " + e.javaClass.simpleName + ": " + e.message,
                e,
            )
            unavailable = true
            false
        }
    }

    /**
     * 索引一批 text part（upsert 语义：按 partId 先删后插）。
     * 阻塞式 SQL——调用方必须在 IO 上下文（MessageStore 写路径已在 IO/事务内）。
     */
    /**
     * #299 续项（2026-09-02）：整批单事务——原逐条裸 execSQL 各自 autocommit，
     * 118 msgs 页（~200 text part）≈ 400 次闪存 fsync 事务 ≈ 20s（真机实测，
     * RPC 仅 1.9s）；批事务后整页 1 次 fsync。嵌套调用（replaceSessionMessages
     * 在 Room withTransaction 内调用）走 Android 嵌套事务（savepoint）语义不变。
     */
    fun indexTextParts(sessionId: String, items: List<IndexedTextPart>) {
        if (items.isEmpty() || !ensureAvailable()) return
        val db = database.openHelper.writableDatabase
        // 防御性降级：批事务失败（如测试环境 DB 关闭竞态）不外抛——索引是增强，
        // 与本文件既有降级哲学一致（缺行仅影响可搜性，下次 upsert 幂等补齐）。
        try {
            db.beginTransaction()
            for (item in items) {
                if (item.existing) {
                    db.execSQL("DELETE FROM ${MessageFtsSchema.TABLE} WHERE partId = ?", arrayOf(item.partId))
                }
                db.execSQL(
                    "INSERT INTO ${MessageFtsSchema.TABLE}(sessionId, messageId, partId, role, text) VALUES(?, ?, ?, ?, ?)",
                    arrayOf(sessionId, item.messageId, item.partId, item.role, item.text),
                )
            }
            db.setTransactionSuccessful()
        } catch (e: android.database.SQLException) {
            android.util.Log.w("MessageFtsIndex", "batch index failed (degraded): " + e.message)
        } finally {
            runCatching { db.endTransaction() }
        }
    }

    /** 删会话级联清理（会话删除时与热表/冷存同事务调用）。 */
    fun clearSession(sessionId: String) {
        if (!ensureAvailable()) return
        database.openHelper.writableDatabase
            .execSQL("DELETE FROM ${MessageFtsSchema.TABLE} WHERE sessionId = ?", arrayOf(sessionId))
    }

    /**
     * BM25 内容检索。FTS5 不可用时降级 LIKE（rank=null，按时间倒序）。
     * 查询词以短语包裹（防 FTS5 语法注入），unicode61 一元分词下自然匹配字序列。
     */
    suspend fun search(query: String, filter: ContentSearchFilter): List<ContentSearchHit> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            if (ensureAvailable()) searchFts(trimmed, filter) else searchLike(trimmed, filter)
        }
    }

    private suspend fun searchFts(query: String, filter: ContentSearchFilter): List<ContentSearchHit> {
        // #272：多词 = 隐式 AND（逐词短语包裹防 FTS5 语法注入）；unicode61 单字分词下中文逐字可命中
        val phrase = query.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { term -> "\"" + term.replace("\"", "\"\"") + "\"" }
        val where = StringBuilder("WHERE message_fts MATCH ? ")
        val args = mutableListOf(phrase)
        filter.sessionId?.let { where.append("AND message_fts.sessionId = ? "); args.add(it) }
        filter.role?.let { where.append("AND message_fts.role = ? "); args.add(it) }
        filter.timeFrom?.let { where.append("AND mm.created >= ? "); args.add(it.toString()) }
        filter.timeTo?.let { where.append("AND mm.created <= ? "); args.add(it.toString()) }
        args.add(filter.limit.toString())
        where.append("ORDER BY score LIMIT ?")
        val sql = (
            "SELECT message_fts.sessionId, message_fts.messageId, message_fts.role, " +
            "snippet(message_fts, 0, '[', ']', '…', 16), mm.created, bm25(message_fts) AS score " +
            "FROM message_fts JOIN cached_messages mm ON mm.id = message_fts.messageId " +
            where
        )
        val hits = queryHits(sql, args.toTypedArray())
        if (hits.isEmpty()) {
            android.util.Log.d("MessageFtsIndex", "FTS search 0 hits: query=$query sessionId=${filter.sessionId} role=${filter.role}")
        }
        return hits
    }

    /** LIKE 降级（FTS5 不可用）：子串匹配，无相关性排序。 */
    private suspend fun searchLike(query: String, filter: ContentSearchFilter): List<ContentSearchHit> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val where = StringBuilder("WHERE p.type = 'text' AND p.text LIKE ? ESCAPE '\\' ")
        val args = mutableListOf("%" + escaped + "%")
        filter.sessionId?.let { where.append("AND p.sessionId = ? "); args.add(it) }
        filter.role?.let { where.append("AND mm.role = ? "); args.add(it) }
        filter.timeFrom?.let { where.append("AND mm.created >= ? "); args.add(it.toString()) }
        filter.timeTo?.let { where.append("AND mm.created <= ? "); args.add(it.toString()) }
        args.add(filter.limit.toString())
        where.append("ORDER BY mm.created DESC LIMIT ?")
        val sql = (
            "SELECT p.sessionId, p.messageId, mm.role, p.text, mm.created, NULL " +
            "FROM cached_parts p JOIN cached_messages mm ON mm.id = p.messageId " +
            where
        )
        return queryHits(sql, args.toTypedArray()).map { hit ->
            val mid = hit.snippet.length / 2
            val cut = maxOf(0, mid - 40)
            hit.copy(snippet = "…" + hit.snippet.substring(cut) + "…")
        }
    }
    private suspend fun queryHits(sql: String, args: Array<out Any?>): List<ContentSearchHit> =
        databaseRecovery.withCorruptionRecovery {
            val cursor = database.openHelper.readableDatabase.query(sql, args)
            val out = mutableListOf<ContentSearchHit>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    out += ContentSearchHit(
                        sessionId = c.getString(0),
                        messageId = c.getString(1),
                        role = c.getString(2) ?: "",
                        snippet = c.getString(3) ?: "",
                        created = if (c.isNull(4)) 0L else c.getLong(4),
                        rank = if (c.isNull(5)) null else c.getDouble(5),
                    )
                }
            }
            out
        } ?: emptyList()
}
