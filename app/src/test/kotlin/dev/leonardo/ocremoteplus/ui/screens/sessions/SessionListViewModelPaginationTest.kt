package dev.leonardo.ocremoteplus.ui.screens.sessions

import android.util.Log
import dev.leonardo.ocremoteplus.data.api.file.FileApi
import dev.leonardo.ocremoteplus.data.api.session.SessionApi
import dev.leonardo.ocremoteplus.data.api.system.SystemApi
import dev.leonardo.ocremoteplus.data.api.terminal.TerminalApi
import dev.leonardo.ocremoteplus.data.repository.EventDispatcher
import dev.leonardo.ocremoteplus.data.repository.SessionStateService
import dev.leonardo.ocremoteplus.domain.model.Session
import dev.leonardo.ocremoteplus.domain.model.SessionStatus
import dev.leonardo.ocremoteplus.domain.repository.DraftRepository
import dev.leonardo.ocremoteplus.domain.repository.McpRepository
import dev.leonardo.ocremoteplus.domain.usecase.DeleteSessionUseCase
import dev.leonardo.ocremoteplus.domain.usecase.ManageSessionUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelPaginationTest {

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

    private fun createViewModel(): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf(
                "serverUrl" to "http%3A%2F%2Flocalhost%3A8080",
                "username" to "",
                "password" to "",
                "serverName" to "Test",
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
            scrollSignal = SessionScrollSignal()
        )
    }
}
