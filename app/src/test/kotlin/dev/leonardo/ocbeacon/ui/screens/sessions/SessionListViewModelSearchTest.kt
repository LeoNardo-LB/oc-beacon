package dev.leonardo.ocbeacon.ui.screens.sessions

import android.util.Log
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
import io.mockk.slot
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelSearchTest {

    // #267：连接三态真源——显式 Connected 桩（relaxed 默认产出 mock 实例 != Connected，
    // 删除/重命名用例会被守卫误拦）
    private val sseConnectionManager = io.mockk.mockk<dev.leonardo.ocbeacon.service.SseConnectionManager>(relaxed = true).also {
        io.mockk.every { it.linkState(any()) } returns dev.leonardo.ocbeacon.service.ServerLinkState.Connected
        io.mockk.every { it.observeLinkState(any()) } returns kotlinx.coroutines.flow.flowOf(dev.leonardo.ocbeacon.service.ServerLinkState.Connected)
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

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { sessionRepository.getSessionsFlow(any()) } returns emptyFlow()
        every { sessionRepository.getServerSessionsFlow() } returns emptyFlow()
        every { sessionRepository.getLastUserMessageTimeFlow() } returns emptyFlow()
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `searchQuery state is initially empty`() {
        val initialState = SessionListContentState()
        assertEquals(null, initialState.searchQuery)
    }

    @Test
    fun `setSearchQuery updates the query state`() {
        val vm = createViewModel()
        vm.setSearchQuery("test query")
        assertEquals("test query", vm.searchQuery)
    }

    @Test
    fun `clearSearchQuery resets to null`() {
        val vm = createViewModel()
        vm.setSearchQuery("test")
        vm.clearSearchQuery()
        assertEquals(null, vm.searchQuery)
    }

    // ============ #272/Q6c：内容检索过滤（角色 + 时间范围） ============

    @Test
    fun `search filters are initially null`() {
        val vm = createViewModel()
        assertEquals(null, vm.searchRole.value)
        assertEquals(null, vm.searchTimeRange.value)
    }

    @Test
    fun `setSearchRole updates role state`() {
        val vm = createViewModel()
        vm.setSearchRole("user")
        assertEquals("user", vm.searchRole.value)
        vm.setSearchRole(null)
        assertEquals(null, vm.searchRole.value)
    }

    @Test
    fun `setSearchTimeRange updates time range state`() {
        val vm = createViewModel()
        vm.setSearchTimeRange("7d")
        assertEquals("7d", vm.searchTimeRange.value)
        vm.setSearchTimeRange("30d")
        assertEquals("30d", vm.searchTimeRange.value)
    }

    @Test
    fun `setSearchRole with active query requeries with role filter`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fts = mockk<dev.leonardo.ocbeacon.data.local.MessageFtsIndex>(relaxed = true)
            val filterSlot = slot<dev.leonardo.ocbeacon.data.local.ContentSearchFilter>()
            coEvery { fts.search(any(), capture(filterSlot)) } returns emptyList()
            val vm = createViewModel(fts)
            vm.setSearchQuery("kw")
            testScheduler.advanceUntilIdle()
            vm.setSearchRole("user")
            testScheduler.advanceUntilIdle()
            // 防抖首轮 + 过滤变化立即重查 = 至少 2 次
            coVerify(atLeast = 2) { fts.search("kw", any()) }
            assertEquals("user", filterSlot.captured.role)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `setSearchTimeRange computes timeFrom window`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fts = mockk<dev.leonardo.ocbeacon.data.local.MessageFtsIndex>(relaxed = true)
            val filterSlot = slot<dev.leonardo.ocbeacon.data.local.ContentSearchFilter>()
            coEvery { fts.search(any(), capture(filterSlot)) } returns emptyList()
            val vm = createViewModel(fts)
            vm.setSearchQuery("kw")
            testScheduler.advanceUntilIdle()
            vm.setSearchTimeRange("30d")
            testScheduler.advanceUntilIdle()
            val from = filterSlot.captured.timeFrom
            val expected = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            assertNotNull(from)
            assertTrue("timeFrom within 30d window", from != null && Math.abs(from - expected) < 60_000)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `setSearchRole without query does not search`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val fts = mockk<dev.leonardo.ocbeacon.data.local.MessageFtsIndex>(relaxed = true)
            val vm = createViewModel(fts)
            vm.setSearchRole("assistant")
            testScheduler.advanceUntilIdle()
            coVerify(exactly = 0) { fts.search(any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        ftsIndex: dev.leonardo.ocbeacon.data.local.MessageFtsIndex = mockk(relaxed = true),
    ): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "serverId" to "srv1"
            )
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
            getSettingsFlowUseCase = mockk(relaxed = true),
            sessionTagRepository = mockk(relaxed = true),
            serverRepository = mockk(relaxed = true),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService> {
                io.mockk.every { mergedReadTimes(any()) } returns kotlinx.coroutines.flow.flowOf(emptyMap<String, Long>())
                io.mockk.every { allReadAt(any()) } returns kotlinx.coroutines.flow.flowOf(0L)
            },
            chatRepository = mockk(relaxed = true),
            pendingMessageRepository = mockk(relaxed = true),
            pendingMessageDrainController = mockk(relaxed = true),
            messageFtsIndex = ftsIndex,
            historySyncManager = mockk(relaxed = true),
        )
    }
}
