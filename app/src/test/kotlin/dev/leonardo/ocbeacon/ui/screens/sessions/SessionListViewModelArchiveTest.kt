package dev.leonardo.ocbeacon.ui.screens.sessions

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.WorkspaceSnapshot
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
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
import kotlinx.coroutines.flow.flowOf
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
 * #311 Task2：归档动作调用链（viewModel 层 mockk 先例同 Pagination/Search 测试）——
 * archiveSession → ChatRepository.archiveSession（DSH workspace/archiveSession）；
 * 成功吃回执零乐观更新（行去留以 follow 增量为准），失败走 _error 哨兵（snackbar
 * 本地化）；断连 #267 快速失败不发请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelArchiveTest {

    // #267：连接三态真源——显式 Connected 桩（fastFail 守卫放行）
    private val sseConnectionManager = mockk<dev.leonardo.ocbeacon.service.SseConnectionManager>(relaxed = true).also {
        every { it.linkState(any()) } returns dev.leonardo.ocbeacon.service.ServerLinkState.Connected
        every { it.observeLinkState(any()) } returns flowOf(dev.leonardo.ocbeacon.service.ServerLinkState.Connected)
        // #317：TokenNeeded 流（VM 属性初始化即订阅）
        every { it.dshTokenNeededServers } returns MutableStateFlow(emptySet<String>())
    }

    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val sessionStateRepository: SessionStateRepository = mockk(relaxed = true)
    private val listSessionsUseCase: ListSessionsUseCase = mockk()
    private val listProjectsUseCase: ListProjectsUseCase = mockk()
    private val getServerPathsUseCase: GetServerPathsUseCase = mockk()
    private val probeDirectoryUseCase: ProbeDirectoryUseCase = mockk()
    private val searchDirectoriesUseCase: SearchDirectoriesUseCase = mockk()
    private val createDirectoryUseCase: CreateDirectoryUseCase = mockk()
    private val fileRepository: FileRepository = mockk()
    private val manageSessionUseCase: ManageSessionUseCase = mockk()
    private val deleteSessionUseCase: DeleteSessionUseCase = mockk()
    private val chatRepository: ChatRepository = mockk(relaxed = true)

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
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
        // init loadSessions 全链放行（成功路径断言 error 恒空——不可被加载失败噪声污染）
        coEvery { listProjectsUseCase(any()) } returns Result.success(emptyList())
        coEvery { listSessionsUseCase(any(), any(), any(), any(), any()) } returns emptyList()
        // #311：workspace 快照流（VM 归档集合源）——恒空快照桩
        every { chatRepository.getWorkspaceSnapshotFlow(any()) } returns flowOf(WorkspaceSnapshot())
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `archiveSession delegates to repository and keeps error clear on success`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { chatRepository.archiveSession("srv1", "s1") } returns
                Result.success(listOf("s1", "s9"))
            val vm = createViewModel()

            vm.archiveSession("s1")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { chatRepository.archiveSession("srv1", "s1") }
            assertNull(vm.error.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `archiveSession failure surfaces sentinel error for localized snackbar`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { chatRepository.archiveSession("srv1", "s1") } returns
                Result.failure(RuntimeException("archive rpc failed"))
            val vm = createViewModel()

            vm.archiveSession("s1")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { chatRepository.archiveSession("srv1", "s1") }
            assertEquals(SessionListViewModel.ERROR_ARCHIVE_FAILED, vm.error.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `archiveSession fast-fails offline without repository call`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // 断连态：#267 快速失败哨兵，不发 RPC
            every { sseConnectionManager.linkState(any()) } returns
                dev.leonardo.ocbeacon.service.ServerLinkState.Disconnected
            val vm = createViewModel()

            vm.archiveSession("s1")
            testScheduler.advanceUntilIdle()

            assertEquals(SessionListViewModel.ERROR_SERVER_DISCONNECTED, vm.error.value)
            coVerify(exactly = 0) { chatRepository.archiveSession(any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf("serverId" to "srv1")
        )
        return SessionListViewModel(
            sseConnectionManager = sseConnectionManager,
            savedStateHandle = savedStateHandle,
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            listSessionsUseCase = listSessionsUseCase,
            listProjectsUseCase = listProjectsUseCase,
            getServerPathsUseCase = getServerPathsUseCase,
            probeDirectoryUseCase = probeDirectoryUseCase,
            searchDirectoriesUseCase = searchDirectoriesUseCase,
            createDirectoryUseCase = createDirectoryUseCase,
            fileRepository = fileRepository,
            manageSessionUseCase = manageSessionUseCase,
            deleteSessionUseCase = deleteSessionUseCase,
            draftRepository = mockk(relaxed = true),
            mcpRepository = mockk(relaxed = true),
            dshSettingsRepository = mockk(relaxed = true),
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService> {
                every { mergedReadTimes(any()) } returns flowOf(emptyMap<String, Long>())
                every { allReadAt(any()) } returns flowOf(0L)
            },
            getSettingsFlowUseCase = mockk(relaxed = true),
            sessionTagRepository = mockk(relaxed = true),
            serverRepository = mockk(relaxed = true),
            chatRepository = chatRepository,
            eventDispatcher = io.mockk.mockk(relaxed = true),
            messageFtsIndex = mockk(relaxed = true),
            historySyncManager = mockk(relaxed = true),
            pendingInteractionStore = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingInteractionStore> {
                io.mockk.every { pendingBySession } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, dev.leonardo.ocbeacon.data.repository.PendingInteractionEntry>())
            },
            dshConnectionRegistry = mockk(relaxed = true),
        )
    }
}
