package dev.leonardo.ocbeacon.ui.screens.sessions

import android.util.Log
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.McpRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelSearchTest {

    private val sessionApi: SessionApi = mockk()
    private val fileApi: FileApi = mockk()
    private val systemApi: SystemApi = mockk()
    private val terminalApi: TerminalApi = mockk()
    private val eventDispatcher: EventDispatcher = mockk(relaxed = true)
    private val sessionStateService: SessionStateService = mockk(relaxed = true)
    private val manageSessionUseCase: ManageSessionUseCase = mockk()
    private val deleteSessionUseCase: DeleteSessionUseCase = mockk()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        // Relaxed mock returns default Object for StateFlow<List>.value;
        // set up proper empty collections to avoid ClassCastException
        every { eventDispatcher.sessions.value } returns emptyList()
        every { eventDispatcher.sessionStatuses.value } returns emptyMap<String, SessionStatus>()
        every { eventDispatcher.serverSessions.value } returns emptyMap<String, Set<String>>()
        every { sessionStateService.statusFlow.value } returns emptyMap()
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `searchQuery state is initially empty`() {
        val initialState = SessionListUiState()
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
            eventDispatcher = eventDispatcher,
            sessionStateService = sessionStateService,
            sessionApi = sessionApi,
            fileApi = fileApi,
            systemApi = systemApi,
            terminalApi = terminalApi,
            manageSessionUseCase = manageSessionUseCase,
            deleteSessionUseCase = deleteSessionUseCase,
            draftRepository = mockk(relaxed = true),
            mcpRepository = mockk(relaxed = true),
            getSettingsFlowUseCase = mockk(relaxed = true),
            settingsRepository = mockk(relaxed = true),
            scrollSignal = SessionScrollSignal(),
            serverRepository = mockk(relaxed = true),
        )
    }
}
