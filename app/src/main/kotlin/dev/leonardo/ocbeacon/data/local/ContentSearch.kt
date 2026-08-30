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

    /** 幂等建表；返回 FTS5 是否可用（不可用 = API<30 无模块，调用方走 LIKE 降级）。 */
    fun ensureAvailable(): Boolean = synchronized(lock) {
        if (ensured) return true
        if (unavailable) return false
        try {
            database.openHelper.writableDatabase.execSQL(MessageFtsSchema.CREATE)
            ensured = true
            true
        } catch (e: SQLiteException) {
            // 典型："no such module: fts5"（API<30）——永久标记，不再重试
            unavailable = true
            false
        }
    }

    /**
     * 索引一批 text part（upsert 语义：按 partId 先删后插）。
     * 阻塞式 SQL——调用方必须在 IO 上下文（MessageStore 写路径已在 IO/事务内）。
     */
    fun indexTextParts(sessionId: String, items: List<IndexedTextPart>) {
        if (items.isEmpty() || !ensureAvailable()) return
        val db = database.openHelper.writableDatabase
        for (item in items) {
            db.execSQL("DELETE FROM ${MessageFtsSchema.TABLE} WHERE partId = ?", arrayOf(item.partId))
            db.execSQL(
                "INSERT INTO ${MessageFtsSchema.TABLE}(sessionId, messageId, partId, role, text) VALUES(?, ?, ?, ?, ?)",
                arrayOf(sessionId, item.messageId, item.partId, item.role, item.text),
            )
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
            "snippet(message_fts, 0, '[', ']', '…', 16), mm.created, bm25(message_fts) " +
            "FROM message_fts JOIN cached_messages mm ON mm.id = message_fts.messageId " +
            where
        )
        return queryHits(sql, args.toTypedArray())
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
