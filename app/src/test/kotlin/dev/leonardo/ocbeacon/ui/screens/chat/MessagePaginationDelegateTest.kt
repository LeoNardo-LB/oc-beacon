package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.LoadOlderResult
import dev.leonardo.ocbeacon.domain.usecase.LoadOlderSource
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagePaginationDelegateTest {

    private fun mkMessages(n: Int): List<MessageWithParts> = List(n) { i ->
        MessageWithParts(
            info = Message.User(id = "m-$i", sessionId = "sid-1", time = TimeInfo(created = i.toLong())),
            parts = emptyList(),
        )
    }

    @Test
    fun `initial limit is 30 and hasOlderMessages false`() = runTest {
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )
        assertEquals(30, delegate.currentLimitValue)
        assertFalse(delegate.hasOlderMessages.value)
        assertFalse(delegate.isLoadingOlder.value)
    }

    @Test
    fun `loadOlderMessages uses oldestMessageId as cursor and sets hasOlderMessages by boundary`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null) } returns Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK))
        }
        val store = mockk<MessageStore> {
            coEvery { oldestMessageId("sid-1") } returns "m-0"
        }
        val repo = mockk<ChatRepository>(relaxed = true)
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = store,
            chatRepository = repo,
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 游标翻页：limit 不再翻倍
        assertEquals(30, delegate.currentLimitValue)
        assertTrue(delegate.hasOlderMessages.value)
        assertFalse(delegate.isLoadingOlder.value)
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null) }
        verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.APPEND_ONLY) }
    }

    @Test
    fun `loadOlderMessages sets hasOlderMessages false when fewer than limit`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages("srv", "sid-1", 30, any()) } returns Result.success(LoadOlderResult(mkMessages(10), LoadOlderSource.NETWORK))
        }
        val store = mockk<MessageStore> {
            coEvery { oldestMessageId("sid-1") } returns "m-0"
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = store,
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadOlderMessages()
        advanceUntilIdle()

        assertEquals(30, delegate.currentLimitValue)
        assertFalse(delegate.hasOlderMessages.value)
    }

    @Test
    fun `loadOlderMessages keeps limit unchanged on exception`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages("srv", "sid-1", 30, any()) } returns Result.failure(RuntimeException("net err"))
        }
        val store = mockk<MessageStore> {
            coEvery { oldestMessageId("sid-1") } returns "m-0"
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = store,
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 游标翻页：失败时 limit 不变（不再 halve back）
        assertEquals(30, delegate.currentLimitValue)
        assertFalse(delegate.isLoadingOlder.value)
    }

    @Test
    fun `loadOlderMessages archive source only merges memory not store`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null) } returns
                Result.success(LoadOlderResult(mkMessages(10), LoadOlderSource.ARCHIVE))
        }
        val store = mockk<MessageStore> {
            coEvery { oldestMessageId("sid-1") } returns "m-0"
        }
        val repo = mockk<ChatRepository>(relaxed = true)
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = store,
            chatRepository = repo,
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 归档来源只进内存（APPEND_ONLY），不落热表
        verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.APPEND_ONLY) }
        assertTrue(delegate.hasOlderMessages.value)
    }

    @Test
    fun `loadOlderMessages archive source advances cursor for next page`() = runTest {
        // 第一次翻页：beforeCreated=null（初始），返回 30 条归档消息（created 0..29），最老 created=0
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.ARCHIVE))
            // 第二次翻页：beforeCreated=0（游标推进为最老消息 created）
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", 0L) } returns
                Result.success(LoadOlderResult(mkMessages(10), LoadOlderSource.ARCHIVE))
        }
        val store = mockk<MessageStore> {
            coEvery { oldestMessageId("sid-1") } returns "m-0"
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = store,
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadOlderMessages()
        advanceUntilIdle()
        // 第一次：游标推进为 0（mkMessages 的 created 是 0..29，最老 = 0）
        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 第二次翻页必须用推进后的游标（beforeCreated=0），不能重复读同一批
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", 0L) }
    }

    @Test
    fun `loadOlderMessages network source resets archive cursor`() = runTest {
        // 归档翻页推进游标后，网络来源把游标重置（下次从热表边界重新开始）
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.ARCHIVE))
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", 0L) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK))
        }
        val store = mockk<MessageStore> {
            coEvery { oldestMessageId("sid-1") } returns "m-0"
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = store,
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadOlderMessages()
        advanceUntilIdle()
        // 第二次归档翻页（游标 0）
        delegate.loadOlderMessages()
        advanceUntilIdle()
        // 第三次：仍是游标 0（尚未重置）→ 网络来源返回 → 游标重置为 null
        delegate.loadOlderMessages()
        advanceUntilIdle()
        // 第四次：游标已重置 → 用 null 重新从热表边界开始
        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 第一次和第四次用 null；第二、三次用推进后的游标 0
        coVerify(exactly = 2) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null) }
        coVerify(exactly = 2) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", 0L) }
    }

    @Test
    fun `loadMessages success drives loading sink and setMessages`() = runTest {
        val useCase = mockk<ManageSessionUseCase> {
            coEvery { listMessages("srv", "sid-1", 30) } returns mkMessages(5)
        }
        val repo = mockk<ChatRepository>(relaxed = true)
        val loading = mutableListOf<Boolean>()
        val errors = mutableListOf<String?>()
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = useCase,
            messagePaging = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            chatRepository = repo,
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = { loading.add(it) },
            errorSink = { errors.add(it) },
        )

        delegate.loadMessages()
        advanceUntilIdle()

        assertTrue(loading.contains(true))
        assertTrue(loading.contains(false))
        assertTrue(errors.contains(null))
        verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.SSE_PRIORITY) }
    }

    @Test
    fun `loadMessages on non-OOM error pushes message to errorSink`() = runTest {
        val useCase = mockk<ManageSessionUseCase> {
            coEvery { listMessages("srv", "sid-1", 30) } throws RuntimeException("net boom")
        }
        val errors = mutableListOf<String?>()
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = useCase,
            messagePaging = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = { errors.add(it) },
        )

        delegate.loadMessages()
        advanceUntilIdle()

        assertEquals(30, delegate.currentLimitValue)
        assertTrue(errors.contains("net boom"))
    }

    @Test
    fun `loadMessages OOM halves limit and retries then reports retry error on second failure`() = runTest {
        val useCase = mockk<ManageSessionUseCase> {
            coEvery { listMessages("srv", "sid-1", 30) } throws OutOfMemoryError("oom")
            coEvery { listMessages("srv", "sid-1", 15) } throws RuntimeException("retry fail")
        }
        val errors = mutableListOf<String?>()
        val repo = mockk<ChatRepository>(relaxed = true)
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = useCase,
            messagePaging = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            chatRepository = repo,
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = { errors.add(it) },
        )

        delegate.loadMessages()
        advanceUntilIdle()

        assertEquals(15, delegate.currentLimitValue)
        assertTrue(errors.contains("retry fail"))
        verify(exactly = 0) { repo.upsertMessages(any(), any(), any()) }
    }

    @Test
    fun `loadMessages OOM halves limit and retry succeeds via mergeMessages`() = runTest {
        val useCase = mockk<ManageSessionUseCase> {
            coEvery { listMessages("srv", "sid-1", 30) } throws OutOfMemoryError("oom")
            coEvery { listMessages("srv", "sid-1", 15) } returns mkMessages(3)
        }
        val errors = mutableListOf<String?>()
        val repo = mockk<ChatRepository>(relaxed = true)
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = useCase,
            messagePaging = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            chatRepository = repo,
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = { errors.add(it) },
        )

        delegate.loadMessages()
        advanceUntilIdle()

        assertEquals(15, delegate.currentLimitValue)
        verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.APPEND_ONLY) }
        // 重试成功不应再写 OOM 错误到 errorSink
        assertFalse(errors.any { it != null && it.contains("oom") })
    }

    @Test
    fun `loadMessagesForSession applies settings initialMessageCount and sets hasOlderMessages`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadMessagesForSession("srv", "sid-1", 50) } returns Result.success(mkMessages(50))
        }
        val repo = mockk<ChatRepository>(relaxed = true)
        val settings = mockk<SettingsRepository> {
            every { getSettingsFlow() } returns flowOf(AppSettings(initialMessageCount = 50))
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = mockk(relaxed = true),
            chatRepository = repo,
            settingsRepository = settings,
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadMessagesForSession()

        assertEquals(50, delegate.currentLimitValue)
        assertTrue(delegate.hasOlderMessages.value)
        verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.SSE_PRIORITY) }
    }

    @Test
    fun `loadMessagesForSession swallows exception without throwing`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadMessagesForSession("srv", "sid-1", 30) } returns Result.failure(RuntimeException("boom"))
        }
        val settings = mockk<SettingsRepository> {
            every { getSettingsFlow() } returns flowOf(AppSettings(initialMessageCount = 30))
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            settingsRepository = settings,
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadMessagesForSession()

        assertEquals(30, delegate.currentLimitValue)
    }
}
