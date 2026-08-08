package dev.leonardo.ocbeacon.ui.screens.sessions

import android.util.Log
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelSearchTest {

    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val sessionStateService: SessionStateRepository = mockk(relaxed = true)
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
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
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

    private fun createViewModel(): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "serverId" to "srv1"
            )
        )
        return SessionListViewModel(
            savedStateHandle = savedStateHandle,
            sessionRepository = sessionRepository,
            sessionStateService = sessionStateService,
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
            getSettingsFlowUseCase = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true),
            serverRepository = mockk(relaxed = true),
            sessionReadSignal = SessionReadSignal(),
            chatRepository = mockk(relaxed = true),
        )
    }
}
