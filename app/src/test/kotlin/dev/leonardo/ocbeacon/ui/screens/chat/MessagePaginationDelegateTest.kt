package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.LoadAroundResult
import dev.leonardo.ocbeacon.domain.usecase.LoadNewerResult
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

    /** 构造指定 id + created 的消息（loadAround/loadNewer 测试用）。 */
    private fun mkMsg(id: String, created: Long): MessageWithParts = MessageWithParts(
        info = Message.User(id = id, sessionId = "sid-1", time = TimeInfo(created = created)),
        parts = emptyList(),
    )

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
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) } returns Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK))
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
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) }
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
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) } returns
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
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.ARCHIVE))
            // 第二次翻页：beforeCreated=0（游标推进为最老消息 created）
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", 0L, null, null) } returns
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
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", 0L, null, null) }
    }

    @Test
    fun `loadOlderMessages network source resets archive cursor`() = runTest {
        // 归档翻页推进游标后，网络来源把游标重置（下次从热表边界重新开始）
        val paging = mockk<MessagePaginationUseCase> {
            // 第一次：无游标（null）
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.ARCHIVE))
            // 第二、三次：归档游标 0（第 6 参为 null——无网络游标）
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", 0L, null, null) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK))
            // 第四次：网络游标建立后（beforeCreated=null 归档回落，networkBeforeCreated=0）
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, 0L, null) } returns
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

        // 调用序列：1) null/null（首翻）→ 2) 0L/null（归档游标）→ 3)+4) null/0L（网络游标建立后）
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) }
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", 0L, null, null) }
        coVerify(exactly = 2) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null, 0L, null) }
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

    // ============ loadAround / loadNewer（快速导航双向加载） ============

    @Test
    fun `loadAround sets both cursors and hasNewerMessages`() = runTest {
        val older = (0..29).map { mkMsg("o-$it", it.toLong()) }       // created 0..29
        val newer = (31..60).map { mkMsg("n-$it", it.toLong()) }      // created 31..60
        val target = mkMsg("target", 30L)
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadAround("srv", "sid-1", "target", 30) } returns Result.success(
                LoadAroundResult(
                    target = target,
                    olderMessages = older,
                    newerMessages = newer,
                    olderNextCursor = "older-cursor",
                    newerPreviousCursor = "newer-cursor",
                ),
            )
        }
        val repo = mockk<ChatRepository>(relaxed = true)
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = mockk(relaxed = true),
            chatRepository = repo,
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadAround("target")
        advanceUntilIdle()

        assertFalse(delegate.isLoadingAround.value)
        assertTrue(delegate.hasOlderMessages.value)
        assertTrue(delegate.hasNewerMessages.value)
        // 单次 upsert（target + older + newer 合并）
        verify(exactly = 1) { repo.upsertMessages("sid-1", any(), MergeStrategy.APPEND_ONLY) }
    }

    @Test
    fun `loadAround V1 fallback - no newer cursor sets hasNewer false`() = runTest {
        val older = (0..29).map { mkMsg("o-$it", it.toLong()) }
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadAround("srv", "sid-1", "target", 30) } returns Result.success(
                LoadAroundResult(
                    target = mkMsg("target", 30L),
                    olderMessages = older,
                    newerMessages = emptyList(),
                    olderNextCursor = null,       // V1 无服务器游标
                    newerPreviousCursor = null,   // V1 无更新方向
                ),
            )
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadAround("target")
        advanceUntilIdle()

        // older 满页 → hasOlder=true（V1 仍可向旧加载）
        assertTrue(delegate.hasOlderMessages.value)
        // V1 无更新方向 → hasNewer=false
        assertFalse(delegate.hasNewerMessages.value)
    }

    @Test
    fun `loadNewerMessages uses newerCursor from loadAround and advances`() = runTest {
        // 先 loadAround 建立 newer 游标，再 loadNewer 推进
        val older = (0..29).map { mkMsg("o-$it", it.toLong()) }
        val newer = (31..60).map { mkMsg("n-$it", it.toLong()) }
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadAround("srv", "sid-1", "target", 30) } returns Result.success(
                LoadAroundResult(
                    target = mkMsg("target", 30L),
                    olderMessages = older,
                    newerMessages = newer,
                    olderNextCursor = "older-cursor",
                    newerPreviousCursor = "newer-cursor-1",
                ),
            )
            // loadNewer 用 newer-cursor-1，返回下一批 + 推进游标
            coEvery { loadNewerMessages("srv", "sid-1", 30, "newer-cursor-1") } returns Result.success(
                LoadNewerResult(
                    messages = (61..90).map { mkMsg("n-$it", it.toLong()) },
                    previousCursor = "newer-cursor-2",
                ),
            )
        }
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadAround("target")
        advanceUntilIdle()
        delegate.loadNewerMessages()
        advanceUntilIdle()

        // loadNewer 用 loadAround 建立的游标调用一次
        coVerify(exactly = 1) { paging.loadNewerMessages("srv", "sid-1", 30, "newer-cursor-1") }
        assertTrue(delegate.hasNewerMessages.value)
        assertFalse(delegate.isLoadingNewer.value)
    }

    @Test
    fun `loadNewerMessages no-op when newerCursor is null`() = runTest {
        // 无 loadAround → newerCursor=null → loadNewer 不应调用 useCase
        val paging = mockk<MessagePaginationUseCase>(relaxed = true)
        val delegate = MessagePaginationDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            messagePaging = paging,
            messageStore = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            settingsRepository = mockk(),
            serverId = "srv",
            scope = this,
            sessionIdProvider = { "sid-1" },
            loadingSink = {},
            errorSink = {},
        )

        delegate.loadNewerMessages()
        advanceUntilIdle()

        coVerify(exactly = 0) { paging.loadNewerMessages(any(), any(), any(), any()) }
    }

    // ============ 自动续载防风暴（退避/暂停/恢复） ============

    private fun failingDelegate(
        scope: kotlinx.coroutines.CoroutineScope,
        paging: MessagePaginationUseCase,
    ): MessagePaginationDelegate = MessagePaginationDelegate(
        manageSessionUseCase = mockk(relaxed = true),
        messagePaging = paging,
        messageStore = mockk(relaxed = true),
        chatRepository = mockk(relaxed = true),
        settingsRepository = mockk(),
        serverId = "srv",
        scope = scope,
        sessionIdProvider = { "sid-1" },
        loadingSink = {},
        errorSink = {},
    )

    @Test
    fun `auto load failure sets backoff wait and does not pause on first failure`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages(any(), any(), any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("network down"))
        }
        val delegate = failingDelegate(this, paging)

        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 第 1 次失败：退避 500ms 生效，但未达上限不暂停
        val wait = delegate.autoLoadWaitMillis()
        assertTrue("expected backoff wait > 0, got $wait", wait > 0)
        assertTrue("expected backoff <= 500ms", wait <= 500)
        assertFalse(delegate.autoLoadPaused.value)
    }

    @Test
    fun `auto load pauses after max consecutive failures`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages(any(), any(), any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("network down"))
        }
        val delegate = failingDelegate(this, paging)

        repeat(3) { delegate.loadOlderMessages(); advanceUntilIdle() }

        assertTrue("autoLoadPaused should be true after 3 failures", delegate.autoLoadPaused.value)
    }

    @Test
    fun `auto load success resets failures and unpauses`() = runTest {
        // 先连续失败 3 次 → 暂停
        val failPaging = mockk<MessagePaginationUseCase> {
            coEvery { loadOlderMessages(any(), any(), any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("network down"))
        }
        val delegate = failingDelegate(this, failPaging)
        repeat(3) { delegate.loadOlderMessages(); advanceUntilIdle() }
        assertTrue(delegate.autoLoadPaused.value)

        // 换成功：mockk 的 coEvery 后定义覆盖前定义（同一 mock 实例）
        coEvery { failPaging.loadOlderMessages(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK))

        delegate.loadOlderMessages()
        advanceUntilIdle()

        assertFalse("success should unpause", delegate.autoLoadPaused.value)
        assertEquals(0L, delegate.autoLoadWaitMillis())
    }

    /**
     * 回归护栏（2026-08-10）：NETWORK 分页的 before 游标必须前进。
     * 原实现每次用热表最老作 before——NETWORK 加载的窗口外消息不落热表
     * （persistOldBeyondWindow=false）→ 热表最老不变 → 每次拉同一批更早消息
     * → 死循环重复加载（模拟器实证：beforeId 恒不变，每 ~100ms 拉同一批 30 条，
     * UI 无新内容 → 用户"看似滑到顶但有更多内容"）。
     * 修复：维护 networkCursorBeforeId（最近 NETWORK 返回的最老 ID），第二次翻页用它。
     */
    @Test
    fun `network pagination advances before cursor across pages`() = runTest {
        // 热表最老 m-0。第一页：返回 m-30..m-59（最老 m-30 ≠ 热表最老 → 可区分游标是否前进）
        val page1 = List(30) { MessageWithParts(Message.User(id = "m-${30 + it}", sessionId = "sid-1", time = TimeInfo(created = (30 + it).toLong())), emptyList()) }
        // 第二页：返回 m-60..m-89（最老 m-60）
        val page2 = List(30) { MessageWithParts(Message.User(id = "m-${60 + it}", sessionId = "sid-1", time = TimeInfo(created = (60 + it).toLong())), emptyList()) }
        val paging = mockk<MessagePaginationUseCase> {
            // 第一次：before=热表最老 m-0，无网络游标
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) } returns
                Result.success(LoadOlderResult(page1, LoadOlderSource.NETWORK))
            // 第二次：before=网络游标 m-30，networkBeforeCreated=30（第一页最老 created=30）
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-30", null, 30L, null) } returns
                Result.success(LoadOlderResult(page2, LoadOlderSource.NETWORK))
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
        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 第二次翻页 before 前进到 m-30（第一页最老）且带 networkBeforeCreated=30，不再用热表最老 m-0
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-30", null, 30L, null) }
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) }
    }

    /**
     * 回归护栏（2026-08-11 模拟器实测死循环根因）：V2 网络翻页必须透传服务器
     * cursor.next。原实现只记 ID+created（CursorCodec 格式 V2 不兼容）→ 服务器
     * 每次返回最新数据 → 游标永不前进 → 无限自动续载（实测 90s 250 次请求）。
     */
    @Test
    fun `v2 network pagination passes server cursor to next page`() = runTest {
        val paging = mockk<MessagePaginationUseCase> {
            // 第一次：无 serverCursor（首次翻页）→ 返回带 nextCursor 的结果
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK, nextCursor = "cursor-A"))
            // 第二次：必须把 cursor-A 作为 networkCursor 透传（防循环关键）；
            // mkMessages 的 created 0..29 → 最老 created=0 → networkBeforeCreated=0
            coEvery { loadOlderMessages("srv", "sid-1", 30, "m-0", null, 0, "cursor-A") } returns
                Result.success(LoadOlderResult(mkMessages(30), LoadOlderSource.NETWORK, nextCursor = "cursor-B"))
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
        delegate.loadOlderMessages()
        advanceUntilIdle()

        // 第二次翻页必须带 cursor-A（服务器游标透传），否则服务器返回重复数据 → 死循环
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null, 0, "cursor-A") }
        coVerify(exactly = 1) { paging.loadOlderMessages("srv", "sid-1", 30, "m-0", null, null, null) }
        assertTrue(delegate.hasOlderMessages.value)
    }
}
