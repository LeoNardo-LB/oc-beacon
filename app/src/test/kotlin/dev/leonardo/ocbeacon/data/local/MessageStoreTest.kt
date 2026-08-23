package dev.leonardo.ocbeacon.data.local

import android.content.Context
import androidx.room.withTransaction
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageStoreTest {

    private val dao = mockk<MessageDao>(relaxed = true)
    private val archiveDao = mockk<ArchiveBucketDao>(relaxed = true)
    private val database = mockk<OcBeaconDatabase>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    // 真实恢复组件（mockk Context 即可）：保证 block 参数被执行，
    // 现有 dao 交互断言依然有效；损坏场景由 DatabaseRecoveryTest 覆盖。
    private val databaseRecovery = DatabaseRecovery(mockk<Context>(relaxed = true))
    // storeImpl：internal buildArchiveBuckets 直测用；store：接口类型验证 MessageStore 满足
    // MessageCacheRepository 契约 + 接口默认参数值（persistOldBeyondWindow=false / beforeId=null）生效。
    private val storeImpl: MessageStore =
        MessageStore(dao, archiveDao, json, databaseRecovery, database, clock = { 1_000_000L })
    private val store: MessageCacheRepository = storeImpl

    @Before
    fun stubTransaction() {
        // RoomDatabase.withTransaction 是顶层 suspend 扩展（room-runtime，facade
        // androidx.room.RoomDatabaseKt）；relaxed mock 会把其委托的实例方法桩为 no-op
        // 而不执行 block → 归档/裁剪交互无法被验证。桩扩展函数本身使其直接调用 block，
        // 事务体内的 archiveDao/dao 调用回归可验证。
        // 注：扩展函数被 mockk 记录时 receiver(database) 也在 args 里（firstArg 是 receiver），
        // 故按类型筛出唯一的 Function1（block），稳过按下标取。
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            val block = invocation.args.first { it is Function1<*, *> } as (suspend () -> Any?)
            block.invoke()
        }
    }

    @After
    fun unstubTransaction() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun msg(id: String, created: Long): MessageWithParts = MessageWithParts(
        info = Message.User(
            id = id,
            sessionId = "ses_1",
            time = TimeInfo(created = created),
        ),
        parts = listOf(
            Part.Text(id = "part_$id", sessionId = "ses_1", messageId = id, text = "hello"),
        ),
    )

    @Test
    fun upsertMessages_serializesInfoAndParts() = runTest {
        val m = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(m))

        coVerify {
            dao.upsertMessages(any())
            dao.upsertParts(any())
        }
    }

    @Test
    fun upsertMessages_persistOldBeyondWindowFalse_skipsMessagesOlderThanOldestCached() = runTest {
        // 本地已有 msg_3（created=300）为最旧 → 窗口边界 = 300
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_3"
        coEvery { dao.messageCreatedAt("msg_3") } returns 300L
        val older = msg("msg_1", 100)
        val newer = msg("msg_4", 400)

        store.upsertMessages("ses_1", listOf(older, newer), persistOldBeyondWindow = false)

        // 只 upsert msg_4；msg_1 被跳过（在窗口外）
        coVerify(exactly = 1) { dao.upsertMessages(match { entities -> entities.any { it.id == "msg_4" } }) }
        coVerify(exactly = 0) { dao.upsertMessages(match { entities -> entities.any { it.id == "msg_1" } }) }
    }

    @Test
    fun upsertMessages_persistOldBeyondWindowTrue_writesAll() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_3"
        coEvery { dao.messageCreatedAt("msg_3") } returns 300L
        val older = msg("msg_1", 100)
        val newer = msg("msg_4", 400)

        store.upsertMessages("ses_1", listOf(older, newer), persistOldBeyondWindow = true)

        coVerify(exactly = 1) { dao.upsertMessages(match { entities -> entities.any { it.id == "msg_1" } }) }
    }

    @Test
    fun upsertMessages_underLimit_doesNotPrune() = runTest {
        // 无 overflow（countForSession relaxed=0 → total=0 ≤ limit）→ 不裁剪。
        // 裁剪仅与归档同事务在 overflow>0 时发生（见 upsertMessages_overflow_*）。
        coEvery { dao.oldestMessageId("ses_1") } returns null
        val m = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(m))

        coVerify(exactly = 0) { dao.pruneToLimit(any(), any()) }
    }

    @Test
    fun loadRange_passesBeforeCursor() = runTest {
        store.loadRange("ses_1", limit = 50, beforeId = "msg_5")

        coVerify(exactly = 1) { dao.messagesBefore("ses_1", "msg_5", 50) }
    }

    @Test
    fun oldestMessageId_delegates() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_1"
        coEvery { dao.oldestMessageId("ses_2") } returns null

        assertEquals("msg_1", store.oldestMessageId("ses_1"))
        assertNull(store.oldestMessageId("ses_2"))
    }

    @Test
    fun upsertMessages_overflow_archivesOldestBeforePrune() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_0"
        coEvery { dao.messageCreatedAt("msg_0") } returns 0L
        // 当前 1003 条 → overflow=3
        coEvery { dao.countForSession("ses_1") } returns 1003
        val old1 = msg("msg_1", 100)
        val old2 = msg("msg_2", 200)
        val old3 = msg("msg_3", 300)
        coEvery { dao.oldestMessages("ses_1", 3) } returns
            listOf(
                CachedMessageEntity("msg_1", "ses_1", 100, "user", json.encodeToString(old1.info)),
                CachedMessageEntity("msg_2", "ses_1", 200, "user", json.encodeToString(old2.info)),
                CachedMessageEntity("msg_3", "ses_1", 300, "user", json.encodeToString(old3.info)),
            )
        // parts：让每个 msg 的 part 可查（#59：走 chunked 委托）
        coEvery { dao.partsForMessagesChunked(listOf("msg_1", "msg_2", "msg_3")) } returns emptyList()

        store.upsertMessages("ses_1", listOf(msg("msg_4", 400)), persistOldBeyondWindow = false)

        // 归档先于 prune：archiveDao.upsertAll 被调用（批量，事务内），裁剪同事务仅一次。
        coVerify(exactly = 1) { archiveDao.upsertAll(any()) }
        coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
        // 核心顺序不变量：归档必须先于 prune（禁止"prune 后查最老归档"——那时 payload 已删）
        coVerifyOrder {
            archiveDao.upsertAll(any())
            dao.pruneToLimit("ses_1", 1000)
        }
    }

    @Test
    fun upsertMessages_noOverflow_doesNotArchiveOrPrune() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns null
        coEvery { dao.countForSession("ses_1") } returns 999

        store.upsertMessages("ses_1", listOf(msg("msg_1", 100)), persistOldBeyondWindow = false)

        coVerify(exactly = 0) { archiveDao.upsertAll(any()) }
        coVerify(exactly = 0) { dao.pruneToLimit(any(), any()) }
    }

    @Test
    fun upsertMessages_windowSkip_noArchiveNoPrune() = runTest {
        // 窗口外消息全部跳过 → 不落库 → 不触发归档
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_9"
        coEvery { dao.messageCreatedAt("msg_9") } returns 900L
        val older = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(older), persistOldBeyondWindow = false)

        coVerify(exactly = 0) { archiveDao.upsertAll(any()) }
        coVerify(exactly = 0) { dao.upsertMessages(any()) }
    }

    @Test
    fun loadArchivedRange_decodesAndReturnsMessages() = runTest {
        // 预编码：构造一个真实冷存桶（复用 buildArchiveBuckets 产生的 payload 格式）
        val msgs = listOf(
            ArchivedMessageDto(msg("msg_1", 100).info, msg("msg_1", 100).parts),
            ArchivedMessageDto(msg("msg_2", 200).info, msg("msg_2", 200).parts),
        )
        // 直接构造桶（绕过 DAO）：用 json 手动序列化压缩
        val jsonBytes = json.encodeToString(msgs).toByteArray(Charsets.UTF_8)
        val realBucket = ArchiveBucketEntity(
            id = 1L, sessionId = "ses_1",
            bucketStart = 100L, bucketEnd = 200L,
            messageCount = 2, uncompressedSize = jsonBytes.size,
            payload = ZstdCodec.compress(jsonBytes),
            createdAt = 1L, lastAccessedAt = 1L,
        )
        coEvery { archiveDao.latestBefore("ses_1", 1000L, any()) } returns listOf(realBucket)

        val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 1000L)

        assertEquals(2, result.size)
        assertEquals("msg_1", result[0].info.id)
        assertEquals("msg_2", result[1].info.id)
        // 读取后 touch 更新 lastAccessedAt
        coVerify { archiveDao.touch(1L, any()) }
    }

    @Test
    fun loadArchivedRange_noBuckets_returnsEmpty() = runTest {
        coEvery { archiveDao.latestBefore("ses_1", 1000L, any()) } returns emptyList()

        val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 1000L)

        assertEquals(0, result.size)
    }

    @Test
    fun hasArchivedMessages_trueWhenBucketExists() = runTest {
        coEvery { archiveDao.latestBefore("ses_1", 1000L, 1) } returns listOf(mockk<ArchiveBucketEntity>())

        assertEquals(true, store.hasArchivedMessages("ses_1", 1000L))
    }

    @Test
    fun hasArchivedMessages_falseWhenNone() = runTest {
        coEvery { archiveDao.latestBefore("ses_1", 1000L, 1) } returns emptyList()

        assertEquals(false, store.hasArchivedMessages("ses_1", 1000L))
    }

    @Test
    fun loadArchivedRange_corruptBucket_skipsAndContinues() = runTest {
        // 一个坏桶（payload 乱码）+ 之后无更多桶
        val corruptBucket = ArchiveBucketEntity(
            id = 1L, sessionId = "ses_1",
            bucketStart = 100L, bucketEnd = 200L,
            messageCount = 2, uncompressedSize = 100,
            payload = ByteArray(50) { 0x7F.toByte() },  // 乱码，无法解压
            createdAt = 1L, lastAccessedAt = 1L,
        )
        // 第一次调用（beforeCreated=1000）命中返回坏桶；continue 后游标推进到 bucketStart=100，
        // 第二次 latestBefore("ses_1", 100L, ...) 不匹配此 stub，relaxed mock 返回空 → 退出循环。
        coEvery { archiveDao.latestBefore("ses_1", 1000L, any()) } returns listOf(corruptBucket)

        val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 1000L)

        assertEquals(0, result.size)          // 坏桶被跳过
        coVerify { archiveDao.touch(1L, any()) }  // 仍 touch
    }

    @Test
    fun clearSession_clearsHotAndArchive() = runTest {
        store.clearSession("ses_1")

        coVerify(exactly = 1) { dao.clearSession("ses_1") }
        coVerify(exactly = 1) { archiveDao.clearSession("ses_1") }
    }

    @Test
    fun upsertMessages_archiveSkipsUndecodableMessage_keepsBatch() = runTest {
        // 归档候选含 1 条坏 payload（JSON 无法反序列化为 Message）→ 只跳过该条，好的仍归档 + prune 仍执行。
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_0"
        coEvery { dao.messageCreatedAt("msg_0") } returns 0L
        coEvery { dao.countForSession("ses_1") } returns 1003  // overflow=3
        val good1 = msg("msg_1", 100)
        val good2 = msg("msg_2", 200)
        coEvery { dao.oldestMessages("ses_1", 3) } returns listOf(
            CachedMessageEntity("msg_bad", "ses_1", 50, "assistant", "{not valid json"),
            CachedMessageEntity("msg_1", "ses_1", 100, "user", json.encodeToString(good1.info)),
            CachedMessageEntity("msg_2", "ses_1", 200, "user", json.encodeToString(good2.info)),
        )
        coEvery { dao.partsForMessagesChunked(any()) } returns emptyList()

        store.upsertMessages("ses_1", listOf(msg("msg_3", 300)), persistOldBeyondWindow = false)

        // 好的 2 条被归档（1 个桶），坏的被跳过；prune 仍执行
        coVerify(exactly = 1) { archiveDao.upsertAll(any()) }
        coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
    }

    // ---- buildArchiveBuckets 直测（internal；M9 边界路径覆盖）----

    private fun archivedMsg(id: String, created: Long, text: String = "hello"): ArchivedMessageDto =
        ArchivedMessageDto(
            info = Message.User(id = id, sessionId = "ses_1", time = TimeInfo(created = created)),
            parts = listOf(Part.Text(id = "part_$id", sessionId = "ses_1", messageId = id, text = text)),
        )

    @Test
    fun buildArchiveBuckets_splitsByDayWindow() {
        // 跨 2 个 1 天窗口（86_400_000 ms）→ 2 桶
        val day0 = archivedMsg("msg_1", created = 100L)
        val day1 = archivedMsg("msg_2", created = 100L + MessageStore.ARCHIVE_BUCKET_WINDOW_MS)
        val msgs = listOf(day0, day1)

        val buckets = storeImpl.buildArchiveBuckets("ses_1", msgs)

        assertEquals(2, buckets.size)
        assertEquals(1, buckets[0].messageCount)
        assertEquals(1, buckets[1].messageCount)
    }

    @Test
    fun buildArchiveBuckets_splitsWhenOver512KB() {
        // 同一天窗口、单 200-chunk（8<200）内未压缩 JSON 超 512KB → 触发字节对半切分。
        // 关键安全不变量：每桶未压缩 ≤ ARCHIVE_BUCKET_MAX_BYTES（守 Android 2MB CursorWindow）。
        val big = "x".repeat(70_000)  // 70KB/条 × 8 ≈ 560KB > 512KB
        val msgs = (1..8).map { archivedMsg("msg_$it", created = 100L * it, text = big) }

        val buckets = storeImpl.buildArchiveBuckets("ses_1", msgs)

        assertTrue("expected byte-triggered split into >1 bucket", buckets.size > 1)
        assertTrue(
            "all buckets must be ≤ ${MessageStore.ARCHIVE_BUCKET_MAX_BYTES} bytes",
            buckets.all { it.uncompressedSize <= MessageStore.ARCHIVE_BUCKET_MAX_BYTES },
        )
        assertEquals(8, buckets.sumOf { it.messageCount })
    }

    /**
     * 分块查询回归护栏（2026-08-10）：SQLite IN 子句 999 变量上限——
     * 大会话（>999 条消息）loadRange 时直接 IN 查询抛 SQLiteException
     * （"too many SQL variables"）。partsForMessagesChunked 应将
     * messageIds 切块多次查询并合并，结果与单次查询等价。
     * （#59 后分块逻辑在 DAO default 方法内——用匿名实现真实执行）
     */
    @Test
    fun loadRange_chunksPartsQueryForLargeSession() = runTest {
        // 构造 1500 条消息的会话（> 999 变量上限）
        val ids = (0 until 1500).map { "msg_$it" }
        val entities = ids.mapIndexed { i, id ->
            CachedMessageEntity(id, "ses_1", i.toLong(), "assistant", json.encodeToString(Message.Assistant(id = id, sessionId = "ses_1", time = TimeInfo(i.toLong()), parentId = "p0")))
        }
        // 匿名 DAO：partsForMessages 记录调用并返回 parts（default 分块逻辑真实执行）
        val chunkSizes = mutableListOf<Int>()
        val realDao = object : MessageDao {
            override suspend fun messagesForSession(sessionId: String, limit: Int) = entities
            override suspend fun messagesBefore(sessionId: String, beforeId: String, limit: Int) = emptyList<CachedMessageEntity>()
            override suspend fun messagesAfter(sessionId: String, afterId: String, limit: Int) = emptyList<CachedMessageEntity>()
            override suspend fun userMessages(sessionId: String, limit: Int) = emptyList<CachedMessageEntity>()
            override suspend fun messageById(sessionId: String, messageId: String): CachedMessageEntity? = null
            override suspend fun partsForMessages(messageIds: List<String>): List<CachedPartEntity> {
                chunkSizes.add(messageIds.size)
                return messageIds.map { CachedPartEntity(id = "p_$it", messageId = it, sessionId = "ses_1", type = "text", text = "{}", payload = "{}") }
            }
            override fun observeMessages(sessionId: String) = kotlinx.coroutines.flow.flowOf(emptyList<CachedMessageEntity>())
            override suspend fun appendPartText(partId: String, messageId: String, sessionId: String, type: String, delta: String) {}
            override suspend fun updatePartText(partId: String, text: String) {}
            override suspend fun oldestMessageId(sessionId: String): String? = null
            override suspend fun messageCreatedAt(messageId: String): Long? = null
            override suspend fun countForSession(sessionId: String): Int = entities.size
            override suspend fun oldestMessages(sessionId: String, limit: Int) = emptyList<CachedMessageEntity>()
            override suspend fun pruneToLimit(sessionId: String, limit: Int): Int = 0
            override suspend fun clearSession(sessionId: String) = Unit
            override suspend fun upsertMessages(entities: List<CachedMessageEntity>) = Unit
            override suspend fun upsertParts(entities: List<CachedPartEntity>) = Unit
        }
        val realStore = MessageStore(realDao, archiveDao, json, databaseRecovery, database, clock = { 1_000_000L })

        val result = realStore.loadRange("ses_1", limit = 2000, beforeId = null)

        assertEquals(1500, result.size)
        // 分块：1500/900 → 2 次调用（900 + 600）
        assertEquals(listOf(900, 600), chunkSizes)
    }

    // ---- userMessages / loadRangeNewer / messageById（快速导航全量列表 + loadAround 本地分支） ----

    @Test
    fun userMessages_delegatesToDaoAndMapsToMessageWithParts() = runTest {
        val u1 = msg("msg_1", 100)
        coEvery { dao.userMessages("ses_1", 1000) } returns listOf(
            CachedMessageEntity("msg_1", "ses_1", 100, "user", json.encodeToString(u1.info)),
        )
        coEvery { dao.partsForMessagesChunked(listOf("msg_1")) } returns emptyList()

        val result = store.userMessages("ses_1", 1000)

        assertEquals(1, result.size)
        assertEquals("msg_1", result[0].info.id)
        coVerify(exactly = 1) { dao.userMessages("ses_1", 1000) }
    }

    @Test
    fun userMessages_emptyWhenDaoReturnsEmpty() = runTest {
        coEvery { dao.userMessages("ses_1", any()) } returns emptyList()

        assertEquals(emptyList<MessageWithParts>(), store.userMessages("ses_1", 1000))
    }

    @Test
    fun loadRangeNewer_passesAfterCursorToDao() = runTest {
        store.loadRangeNewer("ses_1", limit = 30, afterId = "msg_5")

        coVerify(exactly = 1) { dao.messagesAfter("ses_1", "msg_5", 30) }
    }

    @Test
    fun loadRangeNewer_emptyWhenDaoReturnsEmpty() = runTest {
        coEvery { dao.messagesAfter("ses_1", "msg_5", 30) } returns emptyList()

        assertEquals(emptyList<MessageWithParts>(), store.loadRangeNewer("ses_1", 30, "msg_5"))
    }

    @Test
    fun messageById_returnsMessageWithPartsWhenPresent() = runTest {
        val u1 = msg("msg_1", 100)
        coEvery { dao.messageById("ses_1", "msg_1") } returns
            CachedMessageEntity("msg_1", "ses_1", 100, "user", json.encodeToString(u1.info))
        coEvery { dao.partsForMessagesChunked(listOf("msg_1")) } returns emptyList()

        assertEquals("msg_1", store.messageById("ses_1", "msg_1")?.info?.id)
    }

    @Test
    fun messageById_returnsNullWhenAbsent() = runTest {
        coEvery { dao.messageById("ses_1", "msg_x") } returns null

        assertNull(store.messageById("ses_1", "msg_x"))
    }

    @Test
    fun loadArchivedRange_filtersBucketMessagesBeyondCursor() = runTest {
        // #72 回归：游标推进到消息级（beforeCreated=300）时，同一桶内更旧消息
        // （created 100/200）必须仍可读出——原实现按 bucketEnd 跳过整桶 → 永久读不出。
        val msgs = listOf(100L, 200L, 300L, 400L, 500L).mapIndexed { i, created ->
            ArchivedMessageDto(msg("m_" + i, created).info, msg("m_" + i, created).parts)
        }
        val jsonBytes = json.encodeToString(msgs).toByteArray(Charsets.UTF_8)
        val realBucket = ArchiveBucketEntity(
            id = 1L, sessionId = "ses_1",
            bucketStart = 100L, bucketEnd = 500L,
            messageCount = 5, uncompressedSize = jsonBytes.size,
            payload = ZstdCodec.compress(jsonBytes),
            createdAt = 1L, lastAccessedAt = 1L,
        )
        // 桶边界相交查询（bucketStart < beforeCreated）→ 该桶返回，桶内过滤
        coEvery { archiveDao.latestBefore("ses_1", 300L, any()) } returns listOf(realBucket)

        val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 300L)

        assertEquals("游标之前的桶内消息必须读出（#72）", 2, result.size)
        assertEquals(listOf(100L, 200L), result.map { it.info.time.created })
    }

    @Test
    fun loadArchivedRange_resumesBucketAfterPartialRead() = runTest {
        // #72 完整场景：首读 takeLast(2) 只取桶尾两条（400/500）→ 游标 400 →
        // 再次读取必须拿到剩余 100/200/300（原实现整桶跳过 → 数据永久丢失）
        val msgs = listOf(100L, 200L, 300L, 400L, 500L).mapIndexed { i, created ->
            ArchivedMessageDto(msg("m_" + i, created).info, msg("m_" + i, created).parts)
        }
        val jsonBytes = json.encodeToString(msgs).toByteArray(Charsets.UTF_8)
        val realBucket = ArchiveBucketEntity(
            id = 1L, sessionId = "ses_1",
            bucketStart = 100L, bucketEnd = 500L,
            messageCount = 5, uncompressedSize = jsonBytes.size,
            payload = ZstdCodec.compress(jsonBytes),
            createdAt = 1L, lastAccessedAt = 1L,
        )
        coEvery { archiveDao.latestBefore("ses_1", 400L, any()) } returns listOf(realBucket)

        val result = store.loadArchivedRange("ses_1", limit = 50, beforeCreated = 400L)

        assertEquals(3, result.size)
        assertEquals(listOf(100L, 200L, 300L), result.map { it.info.time.created })
    }
}