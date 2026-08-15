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
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_4", 400)), true) }
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
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_1", 100)), true) }
    }

    @Test
    fun loadMessagesForSession_networkFailure_returnsLocalCache() = runTest {
        val local = listOf(msg("msg_2", 200), msg("msg_3", 300))
        coEvery { messageStore.loadRange("ses_1", 50, null) } returns local
        coEvery { messageStore.oldestMessageId("ses_1") } returns "msg_2"
        coEvery { messageStore.messageCreatedAt("msg_2") } returns 200L
        val expectedBefore = CursorCodec.encode("msg_2", 200L)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns
            Result.failure(RuntimeException("network down"))

        val result = useCase.loadMessagesForSession("srv", "ses_1", 50)

        // 网络失败 → 回退本地缓存（缓存优先理念：有缓存不显示空）
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
        coVerify(exactly = 0) { messageStore.upsertMessages(any(), any(), any()) }
    }

    @Test
    fun loadMessagesForSession_noLocalCache_networkFailure_returnsFailure() = runTest {
        coEvery { messageStore.loadRange("ses_1", 50, null) } returns emptyList()
        coEvery { messageStore.oldestMessageId("ses_1") } returns null
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, null) } returns
            Result.failure(RuntimeException("network down"))

        val result = useCase.loadMessagesForSession("srv", "ses_1", 50)

        // 无本地缓存且网络失败 → 保持 failure（UI 显示加载失败态）
        assertTrue(result.isFailure)
    }

    @Test
    fun loadOlderMessages_usesBeforeCursor() = runTest {
        val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
        coEvery { messageStore.messageCreatedAt("msg_1") } returns 100L
        coEvery { messageStore.hasArchivedMessages("ses_1", 100L) } returns false
        val expectedBefore = CursorCodec.encode("msg_1", 100L)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

        val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_1")

        assertTrue(result.isSuccess)
        assertEquals(LoadOlderSource.NETWORK, result.getOrThrow().source)
        assertEquals(1, result.getOrThrow().messages.size)
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_0", 50)), true) }
    }

    @Test
    fun loadOlderMessages_archiveAvailable_returnsArchiveWithoutNetwork() = runTest {
        val archived = listOf(msg("msg_0", 50), msg("msg_1", 100))
        coEvery { messageStore.messageCreatedAt("msg_5") } returns 500L
        coEvery { messageStore.hasArchivedMessages("ses_1", 500L) } returns true
        coEvery { messageStore.loadArchivedRange("ses_1", 50, 500L) } returns archived

        val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_5")

        assertTrue(result.isSuccess)
        val loaded = result.getOrThrow()
        assertEquals(2, loaded.messages.size)
        assertEquals(LoadOlderSource.ARCHIVE, loaded.source)
        // 网络不调用
        coVerify(exactly = 0) { sessionRepository.listMessages(any(), any(), any(), any()) }
        // 不落热表（防死循环）
        coVerify(exactly = 0) { messageStore.upsertMessages(any(), any(), any()) }
    }

    @Test
    fun loadOlderMessages_noArchive_usesNetwork() = runTest {
        coEvery { messageStore.messageCreatedAt("msg_5") } returns 500L
        coEvery { messageStore.hasArchivedMessages("ses_1", 500L) } returns false
        val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
        val expectedBefore = CursorCodec.encode("msg_5", 500L)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

        val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_5")

        assertTrue(result.isSuccess)
        assertEquals(LoadOlderSource.NETWORK, result.getOrThrow().source)
        assertEquals(1, result.getOrThrow().messages.size)
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_0", 50)), true) }
    }

    @Test
    fun loadOlderMessages_archiveReadExhausted_fallsBackToNetwork() = runTest {
        coEvery { messageStore.messageCreatedAt("msg_5") } returns 500L
        coEvery { messageStore.hasArchivedMessages("ses_1", 500L) } returns true
        coEvery { messageStore.loadArchivedRange("ses_1", 50, 500L) } returns emptyList()  // 归档空（坏桶等）
        val page = MessagePage(messages = listOf(msg("msg_0", 50)), nextCursor = null)
        val expectedBefore = CursorCodec.encode("msg_5", 500L)
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

        val result = useCase.loadOlderMessages("srv", "ses_1", 50, "msg_5")

        assertTrue(result.isSuccess)
        assertEquals(LoadOlderSource.NETWORK, result.getOrThrow().source)
    }

    @Test
    fun observeMessages_delegatesToChatRepository() = runTest {
        val msgs = listOf<Message>(msg("msg_1", 100).info)
        coEvery { chatRepository.getMessagesFlow("ses_1") } returns flowOf(msgs)

        val result = useCase.observeMessages("ses_1")

        assertEquals(msgs, result.first())
    }

    /**
     * 回归护栏（2026-08-10）：网络分页游标（networkBeforeCreated）非空时——
     * 跳过归档检查、直接用 CursorCodec.encode(beforeId, networkBeforeCreated) 请求网络。
     * 原实现依赖热表查询 messageCreatedAt(beforeId)——网络游标消息不在热表
     * （窗口外不落库）→ 返回 null → before 不编码 → 服务器返回最新 → 分页死循环
     * （模拟器实证：beforeId 在 A→B 间交替，每 ~100ms 拉同一批消息）。
     */
    @Test
    fun loadOlderMessages_networkCursor_skipsArchiveAndEncodesWithProvidedCreated() = runTest {
        val page = MessagePage(messages = listOf(msg("msg_50", 500)), nextCursor = null)
        val expectedBefore = CursorCodec.encode("msg_0", 100L)
        // 即使热表查不到 created（游标消息不在热表），也应用网络游标时间编码
        coEvery { messageStore.messageCreatedAt("msg_0") } returns null
        // 归档检查不应被触发（网络游标分支直接跳过）
        coEvery { sessionRepository.listMessages("srv", "ses_1", 50, expectedBefore) } returns Result.success(page)

        val result = useCase.loadOlderMessages(
            "srv", "ses_1", 50, "msg_0",
            networkBeforeCreated = 100L,
        )

        assertTrue(result.isSuccess)
        assertEquals(LoadOlderSource.NETWORK, result.getOrThrow().source)
        coVerify(exactly = 0) { messageStore.hasArchivedMessages(any(), any()) }
        coVerify { messageStore.upsertMessages("ses_1", listOf(msg("msg_50", 500)), true) }
    }
}
