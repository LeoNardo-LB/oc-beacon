package dev.leonardo.ocbeacon.data.local

import android.content.Context
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageStoreTest {

    private val dao = mockk<MessageDao>(relaxed = true)
    private val archiveDao = mockk<ArchiveBucketDao>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    // 真实恢复组件（mockk Context 即可）：保证 block 参数被执行，
    // 现有 dao 交互断言依然有效；损坏场景由 DatabaseRecoveryTest 覆盖。
    private val databaseRecovery = DatabaseRecovery(mockk<Context>(relaxed = true))
    // 声明为接口类型：验证 MessageStore 实现满足 MessageCacheRepository 契约，
    // 且接口默认参数值（persistOldBeyondWindow=false / beforeId=null）生效。
    private val store: MessageCacheRepository =
        MessageStore(dao, archiveDao, json, databaseRecovery, clock = { 1_000_000L })

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
    fun upsertMessages_prunesAfterWrite() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns null
        val m = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(m))

        coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
    }

    @Test
    fun loadRange_passesBeforeCursor() = runTest {
        store.loadRange("ses_1", limit = 50, beforeId = "msg_5")

        coVerify(exactly = 1) { dao.messagesForSession("ses_1", 50, "msg_5") }
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
        // parts：让每个 msg 的 part 可查
        coEvery { dao.partsForMessages(listOf("msg_1", "msg_2", "msg_3")) } returns emptyList()

        store.upsertMessages("ses_1", listOf(msg("msg_4", 400)), persistOldBeyondWindow = false)

        // 归档先于 prune：archiveDao.upsert 被调用，且 pruneToLimit 仍执行
        coVerify(exactly = 1) { archiveDao.upsert(any()) }
        coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
    }

    @Test
    fun upsertMessages_noOverflow_doesNotArchive() = runTest {
        coEvery { dao.oldestMessageId("ses_1") } returns null
        coEvery { dao.countForSession("ses_1") } returns 999

        store.upsertMessages("ses_1", listOf(msg("msg_1", 100)), persistOldBeyondWindow = false)

        coVerify(exactly = 0) { archiveDao.upsert(any()) }
        coVerify(exactly = 1) { dao.pruneToLimit("ses_1", 1000) }
    }

    @Test
    fun upsertMessages_windowSkip_noArchiveNoPrune() = runTest {
        // 窗口外消息全部跳过 → 不落库 → 不触发归档
        coEvery { dao.oldestMessageId("ses_1") } returns "msg_9"
        coEvery { dao.messageCreatedAt("msg_9") } returns 900L
        val older = msg("msg_1", 100)

        store.upsertMessages("ses_1", listOf(older), persistOldBeyondWindow = false)

        coVerify(exactly = 0) { archiveDao.upsert(any()) }
        coVerify(exactly = 0) { dao.upsertMessages(any()) }
    }
}
