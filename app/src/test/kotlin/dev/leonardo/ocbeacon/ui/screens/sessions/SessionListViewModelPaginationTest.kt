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
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelPaginationTest {

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
    fun `hasMorePages is initially true`() {
        val vm = createViewModel()
        assertTrue(vm.hasMorePages)
    }

    @Test
    fun `isLoadingMore is initially false`() {
        val vm = createViewModel()
        assertFalse(vm.isLoadingMore)
    }

    @Test
    fun `resetPagination clears cursor state`() {
        val vm = createViewModel()
        vm.resetPagination()
        assertTrue(vm.hasMorePages)
        assertEquals(null, vm.currentCursor)
    }

    @Test
    fun `loadMore holds server cursor and keeps paging when next present`() = runTest {
        // #273 回归：旧代码伪造 sessions.last().id 当游标（V2 服务器 400 InvalidCursorError 静默空页）
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val page = dev.leonardo.ocbeacon.domain.model.SessionPage(
                items = listOf(dev.leonardo.ocbeacon.domain.model.Session(id = "sess_real_1", time = dev.leonardo.ocbeacon.domain.model.Session.Time(created = 1, updated = 2))),
                nextCursor = "b3BhcXVlX2FuY2hvcg=="
            )
            io.mockk.coEvery { listSessionsUseCase.invokePage(any(), any(), any(), any(), any()) } returns page
            val vm = createViewModel()
            vm.loadMore()
            testScheduler.advanceUntilIdle()
            assertEquals("b3BhcXVlX2FuY2hvcg==", vm.currentCursor)
            assertTrue(vm.hasMorePages)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loadMore stops paging when server cursor is null`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val page = dev.leonardo.ocbeacon.domain.model.SessionPage(
                items = listOf(dev.leonardo.ocbeacon.domain.model.Session(id = "sess_last", time = dev.leonardo.ocbeacon.domain.model.Session.Time(created = 1, updated = 2))),
                nextCursor = null
            )
            io.mockk.coEvery { listSessionsUseCase.invokePage(any(), any(), any(), any(), any()) } returns page
            val vm = createViewModel()
            vm.loadMore()
            testScheduler.advanceUntilIdle()
            assertEquals(null, vm.currentCursor)
            assertFalse(vm.hasMorePages)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "serverId" to "srv1"
            )
        )
        return SessionListViewModel(
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
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService> {
                io.mockk.every { mergedReadTimes(any()) } returns kotlinx.coroutines.flow.flowOf(emptyMap<String, Long>())
                io.mockk.every { allReadAt(any()) } returns kotlinx.coroutines.flow.flowOf(0L)
            },
            getSettingsFlowUseCase = mockk(relaxed = true),
            sessionTagRepository = mockk(relaxed = true),
            serverRepository = mockk(relaxed = true),
            chatRepository = mockk(relaxed = true),
            pendingMessageRepository = mockk(relaxed = true),
            pendingMessageDrainController = mockk(relaxed = true),
            messageFtsIndex = mockk(relaxed = true),
            historySyncManager = mockk(relaxed = true),
        )
    }
}
