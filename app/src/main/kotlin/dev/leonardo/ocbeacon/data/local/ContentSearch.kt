package dev.leonardo.ocbeacon.data.local

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * FTS5 DDL（unicode61 单字分词——中文短词零依赖方案）。
 *
 * #478 根修：external-content 表——content=cached_parts，FTS 影子表只存倒排
 * 索引，原文与列值一律回表读（旧形态 message_fts_content 全文镜像 613MB =
 * 库 92%，热表修剪/归档机制健全却拦不住库无界增长）。列按名对齐 content 表
 * （text/sessionId/messageId）；role/partId 不再冗余入索引（role 查询 JOIN
 * cached_messages 取现值），索引行身份 = cached_parts 物理 rowid。
 */
object MessageFtsSchema {
    const val TABLE = "message_fts"
    const val CREATE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `$TABLE` USING fts5(" +
            "text, " +
            "sessionId UNINDEXED, " +
            "messageId UNINDEXED, " +
            "content='cached_parts', " +
            "content_rowid='rowid', " +
            "tokenize = 'unicode61')"

    /**
     * #478：content 表同步触发器（FTS5 external-content 标准模式）。一致性下沉
     * 到 SQLite 层，覆盖 Room/调用方回调够不着的全部路径——FK CASCADE（prune/
     * 删会话/消息行 REPLACE 级联）、迁移 DELETE、开机空 part 清扫——根治旧
     * 手动维护「prune 不删 FTS 行」的孤儿堆积（181,682 行 vs 热表 6.6k）。
     *
     * 只挂 INSERT/DELETE，不挂 UPDATE：流式 48ms delta append（UPDATE 路径）
     * 不重索引——避免长 part 每批按全文重写倒排 + FTS5 段合并风暴；终值由
     * 下一次快照 REPLACE（upsertParts = DELETE+INSERT，必经触发器）收敛，
 * 索引更新时机与旧手动维护（仅快照路径调用 indexTextParts）等价。
     */
    val TRIGGERS = listOf(
        "CREATE TRIGGER IF NOT EXISTS cached_parts_fts_ai AFTER INSERT ON cached_parts " +
            "WHEN new.type = 'text' BEGIN " +
            "INSERT INTO `$TABLE`(rowid, text, sessionId, messageId) " +
            "VALUES (new.rowid, new.text, new.sessionId, new.messageId); END",
        "CREATE TRIGGER IF NOT EXISTS cached_parts_fts_ad AFTER DELETE ON cached_parts " +
            "WHEN old.type = 'text' BEGIN " +
            "INSERT INTO `$TABLE`(`$TABLE`, rowid, text, sessionId, messageId) " +
            "VALUES ('delete', old.rowid, old.text, old.sessionId, old.messageId); END",
    )

    /**
     * #478：建表后一次性回填——与 [TRIGGERS] 的 ai 触发器同形同过滤（只索引
     * text 行）。**不可用 FTS5 'rebuild' 命令**：rebuild 对 external content
     * 表全量索引 content 表每一行（含 reasoning 等），与触发器 `WHEN type='text'`
     * 语义冲突——被误索引的非 text 行此后删除时 ad 触发器不清理（WHEN 不满足），
     * 倒排孤儿永久残留（宿主 SQLite C10 实证），根因②以新形态复辟。
     */
    const val BACKFILL =
        "INSERT INTO `$TABLE`(rowid, text, sessionId, messageId) " +
            "SELECT rowid, text, sessionId, messageId FROM cached_parts WHERE type = 'text'"
}

/**
 * #272：消息内容全文索引（FTS5）+ BM25 检索。
 *
 * #478 根修后形态：索引表 = external-content（content=cached_parts），同步全部
 * 由 SQLite 触发器承担（见 [MessageFtsSchema.TRIGGERS]）——本类不再提供手动
 * 维护入口（旧 indexTextParts/clearSession/deleteMessage 拆除）。行为变化：
 * - 索引行随热表行同生共死（prune/归档裁剪/CASCADE 删除即清，孤儿不再堆积）；
 *   旧「prune 不删 FTS 行（冷数据可搜）」设计废止——pruned 内容本就 JOIN
 *   cached_messages 过滤不可见，可搜性实际未变。
 * - 流式期索引滞后到下一次快照 REPLACE 收敛（与旧手动维护时机等价）。
 * - 只索引 text part（user/assistant 正文，落库本就不截断）；reasoning/工具输出不入索引（用户裁决）。
 * - 运行时探测 FTS5 可用性：API<30 系统无 FTS5 模块 → available=false，调用方走 LIKE 降级。
 * - #546：引擎触点全部改走 Room 2.8 useWriterConnection/useReaderConnection +
 *   usePrepared（KMP 连接 API，官方推荐的低层访问姿势）——openHelper（Support
 *   API）在 setDriver(bundled) 模式下抛 "no SupportSQLiteOpenHelper.Factory was
 *   configured"（真机 13:33 实测），use*Connection 在 driver/Support 两路径下
 *   均收敛（Support 路径经内部适配）。
 */
@Singleton
class MessageFtsIndex @Inject constructor(
    private val database: OcBeaconDatabase,
    private val databaseRecovery: DatabaseRecovery,
) {
    private val lock = Any()
    private val ensureMutex = Mutex()
    private var ensured = false
    private var unavailable = false

    /**
     * 幂等建表；返回 FTS5 是否可用（不可用 = 无 fts5 模块，调用方走 LIKE 降级）。
     * #272 V3 勘误：小米 ROM（SDK 36）也可能无 fts5 模块——失败根因必须留日志。
     *
     * #478：正常路径表由 MIGRATION_9_10 建好（含过滤回填），此处只做幂等
     * 保险（CREATE/TRIGGER 均 IF NOT EXISTS）；表确实缺失时（老库未搜索过、
     * 迁移前首次搜索）建表后立即从 cached_parts text 行回填一次。
     * 日志走 android.util.Log：本方法可能在 DB 初始化路径上被触发，AppLogger
     * 持久化写库会重入。
     *
     * #546：suspend 化（useConnection 是挂起 API）；单飞互斥由 [ensureMutex]
     * 承担（flag 读写仍轻量同步，双检防止并发首搜重复 DDL）。
     */
    suspend fun ensureAvailable(): Boolean {
        synchronized(lock) {
            if (ensured) return true
            if (unavailable) return false
        }
        return ensureMutex.withLock {
            synchronized(lock) {
                if (ensured) return true
                if (unavailable) return false
            }
            try {
                var existed = false
                database.useWriterConnection { conn ->
                    conn.usePrepared(
                        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '${MessageFtsSchema.TABLE}'",
                    ) { existed = it.step() }
                    // DDL 经 prepare+step 执行（IF NOT EXISTS 幂等）
                    conn.usePrepared(MessageFtsSchema.CREATE) { it.step() }
                    MessageFtsSchema.TRIGGERS.forEach { ddl ->
                        conn.usePrepared(ddl) { it.step() }
                    }
                    if (!existed) {
                        conn.usePrepared(MessageFtsSchema.BACKFILL) { it.step() }
                        android.util.Log.i("MessageFtsIndex", "[478] FTS5 external-content table created + backfilled")
                    }
                }
                synchronized(lock) { ensured = true }
                true
            } catch (e: Exception) {
                android.util.Log.w(
                    "MessageFtsIndex",
                    "FTS5 unavailable, LIKE fallback engaged: " + e.javaClass.simpleName + ": " + e.message,
                    e,
                )
                synchronized(lock) { unavailable = true }
                false
            }
        }
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
        // #478：role 不再冗余存索引列（external-content 化），过滤改走 JOIN 现值
        filter.role?.let { where.append("AND mm.role = ? "); args.add(it) }
        filter.timeFrom?.let { where.append("AND mm.created >= ? "); args.add(it.toString()) }
        filter.timeTo?.let { where.append("AND mm.created <= ? "); args.add(it.toString()) }
        args.add(filter.limit.toString())
        where.append("ORDER BY score LIMIT ?")
        // #478：message_fts.sessionId/messageId 为 external-content 回表列（从
        // cached_parts 按 rowid 取）；role 同上改 mm.role；snippet 由 FTS5 从
        // content 表原文生成，bm25 走倒排——查询语义与旧形态一致。
        val sql = (
            "SELECT message_fts.sessionId, message_fts.messageId, mm.role, " +
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
    /** #546：经 useReaderConnection/usePrepared 执行（driver/Support 两路径通用），参数全部文本绑定（与旧 SimpleSQLiteQuery 行为等价，INTEGER affinity 上下文自动数值化）。 */
    private suspend fun queryHits(sql: String, args: Array<out String>): List<ContentSearchHit> =
        databaseRecovery.withCorruptionRecovery {
            database.useReaderConnection { conn ->
                conn.usePrepared(sql) { stmt ->
                    args.forEachIndexed { i, arg -> stmt.bindText(i + 1, arg) }
                    val out = mutableListOf<ContentSearchHit>()
                    while (stmt.step()) {
                        out += ContentSearchHit(
                            sessionId = stmt.getText(0),
                            messageId = stmt.getText(1),
                            role = if (stmt.isNull(2)) "" else stmt.getText(2),
                            snippet = if (stmt.isNull(3)) "" else stmt.getText(3),
                            created = if (stmt.isNull(4)) 0L else stmt.getLong(4),
                            rank = if (stmt.isNull(5)) null else stmt.getDouble(5),
                        )
                    }
                    out
                }
            }
        } ?: emptyList()
}
