package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.testing.FakeServerAdapterResolver
import android.util.Log
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.SessionSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchResult
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SessionTagRepository
import dev.leonardo.ocbeacon.domain.usecase.CreateDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetServerPathsUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetSettingsFlowUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListProjectsUseCase
import dev.leonardo.ocbeacon.domain.usecase.ListSessionsUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ProbeDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.SearchDirectoriesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #322：DSH 服务端内容搜索 ViewModel 接线测试。
 *
 * 呈现关系（裁决）：DSH 下 setSearchQuery 在本地 FTS 之外并行触发 session/search
 * （全历史会话命中，防抖同窗）；非 DSH 零调用零状态外溢（既有路径不动）。
 * 角色/时间 chips 仅作用本地 FTS——服务器无对应过滤参数，不重发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelServerSearchTest {

    private val sseConnectionManager = io.mockk.mockk<dev.leonardo.ocbeacon.service.SseConnectionManager>(relaxed = true).also {
        io.mockk.every { it.linkState(any()) } returns dev.leonardo.ocbeacon.service.ServerLinkState.Connected
        io.mockk.every { it.observeLinkState(any()) } returns kotlinx.coroutines.flow.flowOf(dev.leonardo.ocbeacon.service.ServerLinkState.Connected)
        io.mockk.every { it.dshTokenNeededServers } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
    }

    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val serverRepository: ServerRepository = mockk(relaxed = true)
    private val chatRepository: dev.leonardo.ocbeacon.domain.repository.ChatRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { sessionRepository.getSessionsFlow(any()) } returns emptyFlow()
        every { sessionRepository.getServerSessionsFlow() } returns emptyFlow()
        every { sessionRepository.getLastUserMessageTimeFlow() } returns emptyFlow()
        every { sessionRepository.getLastCompletedReplyTimeFlow() } returns emptyFlow()
        every { chatRepository.getWorkspaceSnapshotFlow(any()) } returns kotlinx.coroutines.flow.flowOf<dev.leonardo.ocbeacon.domain.model.WorkspaceSnapshot>()
        coEvery { chatRepository.listAgentPresets(any()) } returns Result.success(emptyList())
        // 默认无服务器配置（OpenCode 语义）；用例内按需覆盖 DSH/非 DSH
        coEvery { serverRepository.getServer(any()) } returns null
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun dshConfig() = ServerConfig(
        id = "srv1",
        url = "http://dsh.local",
        serverType = ServerType.Dsh,
    )

    @Test
    fun `dsh server query triggers server search and publishes hits`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { serverRepository.getServer("srv1") } returns dshConfig()
            val expected = SessionSearchResult(
                items = listOf(SessionSearchHit("s-1", "history snippet"), SessionSearchHit("s-9", "unloaded session")),
                hasMore = true,
            )
            coEvery { sessionRepository.searchSessions("srv1", "kw") } returns Result.success(expected)
            val vm = createViewModel()
            testScheduler.advanceUntilIdle() // init 配置加载完成（_serverIsDsh 置位）

            vm.setSearchQuery("kw")
            testScheduler.advanceUntilIdle()

            coVerify(atLeast = 1) { sessionRepository.searchSessions("srv1", "kw") }
            assertEquals(expected, vm.serverSearch.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `non dsh server never calls server search`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { serverRepository.getServer("srv1") } returns ServerConfig(id = "srv1", url = "http://oc.local")
            val vm = createViewModel()
            testScheduler.advanceUntilIdle()

            vm.setSearchQuery("kw")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionRepository.searchSessions(any(), any()) }
            assertNull(vm.serverSearch.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `server search failure degrades to null hits`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { serverRepository.getServer("srv1") } returns dshConfig()
            coEvery { sessionRepository.searchSessions("srv1", "kw") } returns
                Result.failure(IllegalStateException("v011 unsupported"))
            val vm = createViewModel()
            testScheduler.advanceUntilIdle()

            vm.setSearchQuery("kw")
            testScheduler.advanceUntilIdle()

            assertNull(vm.serverSearch.value) // 失败软降级——不外溢错误面（本地 FTS 照常）
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clearSearchQuery clears server hits`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { serverRepository.getServer("srv1") } returns dshConfig()
            coEvery { sessionRepository.searchSessions("srv1", "kw") } returns Result.success(
                SessionSearchResult(listOf(SessionSearchHit("s-1", "snip")), hasMore = false),
            )
            val vm = createViewModel()
            testScheduler.advanceUntilIdle()

            vm.setSearchQuery("kw")
            testScheduler.advanceUntilIdle()
            assertEquals(1, vm.serverSearch.value?.items?.size)

            vm.clearSearchQuery()
            assertNull(vm.serverSearch.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        ftsIndex: dev.leonardo.ocbeacon.data.local.MessageFtsIndex = mockk(relaxed = true),
    ): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf("serverId" to "srv1")
        )
        return SessionListViewModel(
            serverAdapters = FakeServerAdapterResolver(),
            sseConnectionManager = sseConnectionManager,
            savedStateHandle = savedStateHandle,
            sessionRepository = sessionRepository,
            sessionStateRepository = mockk<dev.leonardo.ocbeacon.domain.repository.SessionStateRepository>(relaxed = true).also {
                every { it.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
            },
            listSessionsUseCase = mockk(),
            listProjectsUseCase = mockk<dev.leonardo.ocbeacon.domain.usecase.ListProjectsUseCase>().also {
                coEvery { it(any()) } returns Result.success(emptyList())
            },
            getServerPathsUseCase = mockk(),
            probeDirectoryUseCase = mockk(),
            searchDirectoriesUseCase = mockk(),
            createDirectoryUseCase = mockk(),
            fileRepository = mockk<FileRepository>(),
            manageSessionUseCase = mockk<ManageSessionUseCase>(),
            deleteSessionUseCase = mockk<DeleteSessionUseCase>(),
            draftRepository = mockk(relaxed = true),
            mcpRepository = mockk<McpRepository>(relaxed = true),
            serverSettingsRepository = mockk(relaxed = true),
            scrollSignal = SessionScrollSignal(),
            getSettingsFlowUseCase = mockk<GetSettingsFlowUseCase>(relaxed = true),
            sessionTagRepository = mockk<SessionTagRepository>(relaxed = true),
            serverRepository = serverRepository,
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService> {
                io.mockk.every { mergedReadTimes(any()) } returns kotlinx.coroutines.flow.flowOf(emptyMap<String, Long>())
                io.mockk.every { allReadAt(any()) } returns kotlinx.coroutines.flow.flowOf(0L)
            },
            chatRepository = chatRepository,
            eventDispatcher = io.mockk.mockk(relaxed = true),
            messageFtsIndex = ftsIndex,
            historySyncManager = mockk(relaxed = true),
            pendingInteractionStore = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingInteractionStore> {
                io.mockk.every { pendingBySession } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, dev.leonardo.ocbeacon.data.repository.PendingInteractionEntry>())
            },
            dshConnectionRegistry = io.mockk.mockk(relaxed = true),
        )
    }
}
