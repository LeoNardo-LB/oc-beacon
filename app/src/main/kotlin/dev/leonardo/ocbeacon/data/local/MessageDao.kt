package dev.leonardo.ocbeacon.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(entities: List<CachedMessageEntity>)

    /**
     * #266：骨架消息**存在即跳过**（IGNORE）——供增量落盘（appendPartTexts）使用。
     * 此前骨架走 REPLACE：REPLACE = DELETE + INSERT，而 cached_parts 对
     * cached_messages 是 ON DELETE CASCADE → 每次 48ms flush 都先级联删光该
     * 消息全部 part 行、再只插回本批 delta → 行停留在最后一批（真机插桩实证：
     * text 行恒等于最后一个 delta 批，重启/重同步前渲染依赖内存才完整）。
     * IGNORE 保证 FK 依赖存在且永不触发级联；元数据更新仍由全量快照路径负责。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessagesIfAbsent(entities: List<CachedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParts(entities: List<CachedPartEntity>)

    /**
     * #97（H-6）：增量追加 part 文本——流式 delta 落盘（O(delta) 写，替代全量重写）。
     * UPSERT 语义：part 行不存在时插入（V2 流式中 REST 快照可能未到，
     * 纯 UPDATE 影响 0 行 → 增量丢失——2026-08-14 模拟器 DB 实测发现）。
     *
     * #134（D2-L62）：追加幂等去重——flush（增量 append）与 persistSseUpdate
     * （全量 REPLACE）并发时，全量快照可能已含 flush 刚写入内存的 delta，
     * 随后 append 同一 delta → 文本重复。CASE 分支：行尾已等于 delta 则跳过。
     *
     * #266（2026-08-30 真机插桩定音）：DO UPDATE 内**未限定列名在部分设备
     * SQLite 上解析到 excluded 新行**（substr(text,…) 与 text 均取 :delta 本身
     * → CASE 恒真 → 每次追加实际覆盖为本次 delta，行停留在最后一批）。
     * 旧行引用必须以**表名限定**（cached_parts.text）；同时去掉 OR REPLACE——
     * upsert 目标已是主键冲突，REPLACE 语义在部分版本会整行短路。
     */
    @Query(
        "INSERT INTO cached_parts (id, messageId, sessionId, type, text, payload) " +
        "VALUES (:partId, :messageId, :sessionId, :type, :delta, NULL) " +
        "ON CONFLICT(id) DO UPDATE SET text = CASE " +
        "  WHEN substr(cached_parts.text, -length(:delta)) = :delta THEN cached_parts.text " +
        "  ELSE COALESCE(cached_parts.text, '') || :delta END"
    )
    suspend fun appendPartText(partId: String, messageId: String, sessionId: String, type: String, delta: String)

    /** #97（H-6）：ended 时覆盖最终文本（防增量与 REST 快照漂移）。 */
    @Query("UPDATE cached_parts SET text = :text WHERE id = :partId")
    suspend fun updatePartText(partId: String, text: String)

    /**
     * #228（炸弹清扫）：SSE 残留的空 Text/Reasoning part 一次性删除。
     * #223 时代只堵住了增殖源头与 merge 入口（existing 侧）；Room 里已落盘的
     * 历史炸弹行（实测最大单消息 4488 个空 reasoning part）会随会话种子回灌
     * 热视图。空文本 = 零信息（delta 到达有 idx<0 重建兜底），删除安全。
     *
     * #230 勘误（2026-08-26 深夜追凶）：落库映射 text=(p as? Part.Text)?.text ——
     * **Reasoning 行的 text 列恒 NULL（内容在 payload JSON，#79 截断设计）**。
     * 初版谓词 `text IS NULL` 把全部健康 reasoning 行误判为空 → 每次开机误删
     * 全部 reasoning 缓存（服务器可重拉、未被察觉，但行为错误）。修正：
     * - delta 路径的空行：text = ''（INSERT 以 delta 为初值）；
     * - 快照路径的空 reasoning：text IS NULL 且 payload 内 text 字段为空串。
     */
    @Query(
        "DELETE FROM cached_parts WHERE type IN ('reasoning', 'text') AND (" +
            "text = '' OR (type = 'reasoning' AND text IS NULL AND payload LIKE '%\"text\":\"\"%')" +
            ")"
    )
    suspend fun deleteEmptyStreamParts(): Int

    /** 分页读：最新 limit 条（无游标）。 */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId " +
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun messagesForSession(sessionId: String, limit: Int): List<CachedMessageEntity>

    /**
     * 分页读：取比 beforeId 更早的 limit 条（游标分页，向旧方向）。
     *
     * 2026-09-01（定位跳转失效根因——DSH id 形态 vs V1）：原谓词 id < beforeId
     * 依赖 ULID 字典序 = 时间序，对 DSH 整装消息 id（seq-N）字符串序 ≠ 数字序
     *（seq-9 > seq-4096）→ older 窗口漏单/多位 seq 错位 → 快速定位 loadAround
     * 本地分支窗口错乱。统一改为时间主 + id 次复合游标（对 ULID 与 seq-N 同时
     * 正确；id 仅在同 created 细粒度内作 tie-break）。
     */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId " +
            "AND (created < :created OR (created = :created AND id < :beforeId)) " +
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun messagesBefore(sessionId: String, created: Long, beforeId: String, limit: Int): List<CachedMessageEntity>

    /**
     * 分页读：取比 afterId 更新的 limit 条（游标分页，向新方向，loadAround 本地分支用）。
     * 与 [messagesBefore] 同款 2026-09-01 修复：id 字典序前提 → created 主游标。
     */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId " +
            "AND (created > :created OR (created = :created AND id > :afterId)) " +
            "ORDER BY created ASC, id ASC LIMIT :limit",
    )
    suspend fun messagesAfter(sessionId: String, created: Long, afterId: String, limit: Int): List<CachedMessageEntity>

    /** 快速导航全量列表：role='user' 的最近 limit 条（created 降序）。
     *  role 是独立字段值（user/assistant/synthetic/compaction/system），
     *  role='user' 天然排除 synthetic（其 role='synthetic'）。 */
    @Query(
        "SELECT * FROM cached_messages WHERE sessionId = :sessionId AND role = 'user' " +
            "ORDER BY created DESC, id DESC LIMIT :limit",
    )
    suspend fun userMessages(sessionId: String, limit: Int): List<CachedMessageEntity>

    /** 单条消息查询（loadAround 本地分支取 target 用）。 */
    @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId AND id = :messageId")
    suspend fun messageById(sessionId: String, messageId: String): CachedMessageEntity?

    /** Room Flow：本地库变化 → 自动发新值。 */
    @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created DESC, id DESC")
    fun observeMessages(sessionId: String): Flow<List<CachedMessageEntity>>

    @Query("SELECT * FROM cached_parts WHERE messageId IN (:messageIds)")
    suspend fun partsForMessages(messageIds: List<String>): List<CachedPartEntity>

    /**
     * 分块 IN 查询（#59：Room 单条 @Query 无法处理 SQLite IN 999 变量上限，
     * 原分块逻辑散落 MessageStore 业务层——下沉 DAO 统一封装）。
     */
    suspend fun partsForMessagesChunked(messageIds: List<String>): List<CachedPartEntity> {
        if (messageIds.isEmpty()) return emptyList()
        if (messageIds.size <= SQLITE_IN_VARIABLE_LIMIT) {
            return partsForMessages(messageIds)
        }
        return messageIds.chunked(SQLITE_IN_VARIABLE_LIMIT)
            .flatMap { chunk -> partsForMessages(chunk) }
    }

    companion object {
        /** SQLite 默认 SQLITE_MAX_VARIABLE_NUMBER=999；留余量取 900。 */
        const val SQLITE_IN_VARIABLE_LIMIT = 900
    }

    @Query("SELECT id FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT 1")
    suspend fun oldestMessageId(sessionId: String): String?

    @Query("SELECT created FROM cached_messages WHERE id = :messageId")
    suspend fun messageCreatedAt(messageId: String): Long?

    /** 当前会话热表消息数（算 overflow 用）。 */
    @Query("SELECT COUNT(*) FROM cached_messages WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: String): Int

    /** 待 prune 的最老消息（created ASC 前 [limit] 条）——归档前查询。 */
    @Query("SELECT * FROM cached_messages WHERE sessionId = :sessionId ORDER BY created ASC, id ASC LIMIT :limit")
    suspend fun oldestMessages(sessionId: String, limit: Int): List<CachedMessageEntity>

    @Query(
        "DELETE FROM cached_messages WHERE sessionId = :sessionId AND id NOT IN " +
            "(SELECT id FROM cached_messages WHERE sessionId = :sessionId " +
            "ORDER BY created DESC, id DESC LIMIT :limit)",
    )
    suspend fun pruneToLimit(sessionId: String, limit: Int): Int

    @Query("DELETE FROM cached_messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)
}
