package dev.leonardo.ocbeacon.data.local

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageStoreTest {

    private val dao = mockk<MessageDao>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val store = MessageStore(dao, json)

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
}
