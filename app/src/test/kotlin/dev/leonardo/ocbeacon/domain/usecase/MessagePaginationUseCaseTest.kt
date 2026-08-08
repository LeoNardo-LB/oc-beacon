package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.util.CursorCodec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePaginationUseCaseTest {

    private val chatRepository = mockk<ChatRepository>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository>(relaxed = true)
    private val messageStore = mockk<MessageCacheRepository>(relaxed = true)
    private val useCase = MessagePaginationUseCase(chatRepository, sessionRepository, messageStore)

    private fun msg(id: String, created: Long): MessageWithParts = MessageWithParts(
        info = Message.User(id = id, sessionId = "ses_1", time = TimeInfo(created = created)),
        parts = emptyList(),
    )

    @Test
    fun loadMessagesForSession_localCacheExists_returnsLocalAndIncremental() = runTest {
        val local = listOf(msg("msg_2", 200), msg("msg_3", 300))
        coEvery { messageStore.loadRange("ses_1", 50, null) } returns local
        coEvery { messageStore.oldestMessageId("ses_1") } returns "msg_2"
        coEvery { messageStore.messageCreatedAt("msg_2") } returns 200L
        val page = MessagePage(messages = listOf(msg("msg_4", 400)), nextCursor = null)
        val expectedBefore = CursorCodec.encode("msg_2", 200L)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

        val result = useCase.loadMessagesForSession("srv", "ses_1", 50)

        assertTrue(result.isSuccess)
        // 返回本地 + 增量合并
        assertEquals(3, result.getOrThrow().size)
        // 增量落库
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_4", 400)), false) }
    }

    @Test
    fun loadMessagesForSession_noLocalCache_fetchesFull() = runTest {
        coEvery { messageStore.loadRange("ses_1", 50, null) } returns emptyList()
        coEvery { messageStore.oldestMessageId("ses_1") } returns null
        val page = MessagePage(messages = listOf(msg("msg_1", 100)), nextCursor = null)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, null) } returns Result.success(page)

        val result = useCase.loadMessagesForSession("srv", "ses_1", 50)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_1", 100)), false) }
    }

    @Test
    fun loadOlderMessages_usesBeforeCursor() = runTest {
        val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
        coEvery { messageStore.messageCreatedAt("msg_1") } returns 100L
        val expectedBefore = CursorCodec.encode("msg_1", 100L)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

        val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_1")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_0", 50)), false) }
    }

    @Test
    fun observeMessages_delegatesToChatRepository() = runTest {
        val msgs = listOf<Message>(msg("msg_1", 100).info)
        coEvery { chatRepository.getMessagesFlow("ses_1") } returns flowOf(msgs)

        val result = useCase.observeMessages("ses_1")

        assertEquals(msgs, result.first())
    }
}
