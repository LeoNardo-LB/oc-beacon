package dev.leonardo.ocremoteplus.ui.screens.chat

import dev.leonardo.ocremoteplus.domain.repository.ToolSnapshotCache
import android.util.Log
import dev.leonardo.ocremoteplus.data.repository.ServerTerminalRegistry
import io.ktor.client.HttpClient
import dev.leonardo.ocremoteplus.data.api.SseClient
import dev.leonardo.ocremoteplus.domain.model.AppSettings
import dev.leonardo.ocremoteplus.domain.model.ProvidersResponse
import dev.leonardo.ocremoteplus.data.repository.EventDispatcher
import dev.leonardo.ocremoteplus.data.repository.SessionStateService
import dev.leonardo.ocremoteplus.service.SessionFocusHolder
import dev.leonardo.ocremoteplus.service.AppNotificationManager
import dev.leonardo.ocremoteplus.data.repository.handler.*
import dev.leonardo.ocremoteplus.domain.model.*
import dev.leonardo.ocremoteplus.domain.repository.ChatRepository
import dev.leonardo.ocremoteplus.domain.repository.SessionRepository
import dev.leonardo.ocremoteplus.domain.repository.SettingsRepository
import dev.leonardo.ocremoteplus.domain.usecase.*
import dev.leonardo.ocremoteplus.domain.tracker.TokenStatsTracker
import dev.leonardo.ocremoteplus.ui.screens.sessions.SessionScrollSignal
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

/**
 * Comprehensive tests for 4 features:
 * A. QUEUED badge — queuedMessageIds computation
 * B. Sub-session identification — sessionParentId
 * C. subSessionId extraction logic from tool metadata
 * D. Part.Agent source extraction logic
 * E. Integration scenarios combining multiple features
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQueuedTest {

    // === Mock and infrastructure ===

    private lateinit var eventDispatcher: EventDispatcher
    private val terminalRegistry: ServerTerminalRegistry = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    // UseCase mocks
    private val sendMessageUseCase: SendMessageUseCase = mockk(relaxed = true)
    private val manageSessionUseCase: ManageSessionUseCase = mockk(relaxed = true)
    private val managePermissionUseCase: ManagePermissionUseCase = mockk(relaxed = true)
    private val selectModelUseCase: SelectModelUseCase = mockk(relaxed = true)
    private val manageAgentUseCase: ManageAgentUseCase = mockk(relaxed = true)
    private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
    private val draftUseCase: DraftUseCase = mockk(relaxed = true)
    private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
    private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
    private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateService: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val pendingPromptRepository = mockk<dev.leonardo.ocremoteplus.data.repository.PendingPromptRepository>(relaxed = true)

    private val testSessionId = "test-session-1"
    private val testServerId = "test-server-1"
    private val testDirectory = "/home/test"

    // P5-1: queuedMessageIds now derives from FSM status (Idle forces clear).
    // Tests that verify queued logic need the session to be Busy.
    private val testStatusFlow = MutableStateFlow<Map<String, SessionStatus>>(
        mapOf(testSessionId to SessionStatus.Busy)
    )

    @After
    fun tearDown() {
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val messageStore = MessageEventHandler()
        eventDispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            messagePartHandler = MessagePartHandler(messageStore),
            messageUpdatedHandler = MessageUpdatedHandler(messageStore),
            messageRemovedHandler = MessageRemovedHandler(messageStore),
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(),
            sessionStateService = sessionStateService
        )
        every { sessionStateService.statusFlow } returns testStatusFlow
        every { sessionStateService.activityFlow } returns MutableStateFlow(emptyMap())

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // Draft stubs
        every { draftUseCase.getDraft(any()) } returns null

        // Settings stubs
        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.getSettingsFlow() } returns flowOf(
            AppSettings(
                terminalFontSize = 13f,
                initialMessageCount = 50,
                chatFontSize = "medium",
                codeWordWrap = false,
                confirmBeforeSend = false,
                compactMessages = false,
                collapseTools = false,
                expandReasoning = false,
                hapticFeedback = true,
                keepScreenOn = false,
                compressImageAttachments = true,
                imageAttachmentMaxLongSide = 1440,
                imageAttachmentWebpQuality = 60,
            )
        )

        // UseCase stubs — defaults
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // Wire messagePaging.observeMessages to delegate to eventDispatcher.messages
        every { messagePaging.observeMessages(any()) } answers {
            eventDispatcher.messages.map { msgs -> msgs[firstArg<String>()] ?: emptyList() }
        }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // === Helper methods ===

    private fun createTestSession(
        id: String = testSessionId,
        parentId: String? = null
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = testDirectory,
        parentId = parentId,
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createUserMessage(
        id: String,
        sessionId: String = testSessionId,
        created: Long = System.currentTimeMillis()
    ): Message.User = Message.User(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = created)
    )

    /** User message with a text part — survives V1→V2 bridge conversion. */
    private fun createUserMessageWithText(
        id: String,
        text: String = "test message",
        sessionId: String = testSessionId,
        created: Long = System.currentTimeMillis()
    ): Pair<Message.User, List<Part>> = createUserMessage(id, sessionId, created) to listOf(
        Part.Text(id = "$id-text", sessionId = sessionId, messageId = id, text = text)
    )

    private fun createAssistantMessage(
        id: String,
        sessionId: String = testSessionId,
        completed: Long? = null,
        created: Long = System.currentTimeMillis()
    ): Message.Assistant = Message.Assistant(
        id = id,
        sessionId = sessionId,
        time = TimeInfo(created = created, completed = completed),
        parentId = ""
    )

    /** Assistant message with a text part — survives V1→V2 bridge conversion. */
    private fun createAssistantMessageWithText(
        id: String,
        text: String = "response",
        sessionId: String = testSessionId,
        completed: Long? = null,
        created: Long = System.currentTimeMillis()
    ): Pair<Message.Assistant, List<Part>> = createAssistantMessage(id, sessionId, completed, created) to listOf(
        Part.Text(id = "$id-text", sessionId = sessionId, messageId = id, text = text)
    )

    private fun createToolPart(
        id: String = "tool-1",
        toolName: String = "task",
        state: ToolState = ToolState.Running(),
        metadata: Map<String, JsonElement>? = null
    ): Part.Tool = Part.Tool(
        id = id,
        sessionId = testSessionId,
        messageId = "msg-2",
        callId = "call-$id",
        tool = toolName,
        state = state,
        metadata = metadata
    )

    private fun createViewModel(
        sessionId: String = testSessionId
    ): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to testServerId,
            "sessionId"  to sessionId
        ))
        return ChatViewModel(
            savedStateHandle = savedState,
            sendMessageUseCase = sendMessageUseCase,
            manageSessionUseCase = manageSessionUseCase,
            managePermissionUseCase = managePermissionUseCase,
            selectModelUseCase = selectModelUseCase,
            manageAgentUseCase = manageAgentUseCase,
            manageTerminalUseCase = manageTerminalUseCase,
            draftUseCase = draftUseCase,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            toolCardResolver = dev.leonardo.ocremoteplus.ui.screens.chat.tools.DefaultToolCardResolver(),
            chatRepository = mockk<ChatRepository>(relaxed = true).also { chatRepo ->
                every { chatRepo.getMessagesFlow(any()) } answers { eventDispatcher.messages.map { it[firstArg<String>()] ?: emptyList() } }
                every { chatRepo.getParts(any()) } answers { eventDispatcher.parts.map { it[firstArg<String>()] ?: emptyList() } }
                every { chatRepo.getAllPartsMap() } returns eventDispatcher.parts
                every { chatRepo.getActiveToolProgressForSession(any()) } returns flowOf(emptyList())
                every { chatRepo.setMessages(any(), any()) } answers { eventDispatcher.setMessages(firstArg(), secondArg()) }
                every { chatRepo.mergeMessages(any(), any()) } answers { eventDispatcher.mergeMessages(firstArg(), secondArg()) }
                every { chatRepo.replaceMessages(any(), any()) } answers { eventDispatcher.replaceMessages(firstArg(), secondArg()) }
                every { chatRepo.getPermissionsSnapshot() } answers { eventDispatcher.permissions.value }
                every { chatRepo.getQuestionsSnapshot() } answers { eventDispatcher.questions.value }
                every { chatRepo.getSessionsSnapshot() } answers { eventDispatcher.sessions.value }
                every { chatRepo.getPermissionsWithChildren(any(), any()) } answers { eventDispatcher.getPermissionsWithChildren(firstArg(), secondArg()) }
                every { chatRepo.getQuestionsWithChildren(any(), any()) } answers { eventDispatcher.getQuestionsWithChildren(firstArg(), secondArg()) }
            },
            sessionRepository = mockk<SessionRepository>(relaxed = true).also { sessRepo ->
                every { sessRepo.getSessionsFlow(any()) } returns eventDispatcher.sessions
                every { sessRepo.getSessionStatusesFlow(any()) } returns eventDispatcher.sessionStatuses
                every { sessRepo.getCurrentAgentFlow(any()) } returns eventDispatcher.currentAgent
                every { sessRepo.getCurrentModelFlow(any()) } returns eventDispatcher.currentModel
                every { sessRepo.setSessions(any(), any()) } answers { eventDispatcher.setSessions(firstArg(), secondArg()) }
                coEvery { sessRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
            },
            messagePaging = messagePaging,
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),
            sseClient = mockk(relaxed = true),
            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            pendingPromptRepository = pendingPromptRepository
        )
    }

    /**
     * This simulates SSE updates arriving after initial load.
     */
    private fun pushMessages(messages: List<Pair<Message, List<Part>>>) {
        val messageWithParts = messages.map { (msg, parts) ->
            MessageWithParts(info = msg, parts = parts)
        }
        eventDispatcher.setMessages(testSessionId, messageWithParts)
    }

    /** Set session into EventDispatcher. */
    private fun setSession(session: Session) {
        eventDispatcher.setSessions(testServerId, listOf(session))
    }

    /**
     * Configure manageSessionUseCase.listMessages to return the given messages as MessageWithParts,
     * so that the VM's init loadMessages() will populate them into EventDispatcher.
     */
    private fun stubMessages(vararg messages: Pair<Message, List<Part>>) {
        val messageWithParts = messages.map { (msg, parts) ->
            MessageWithParts(info = msg, parts = parts)
        }
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns messageWithParts
    }

    /**
     * Subscribe to uiState to activate the SharingStarted.WhileSubscribed upstream.
     * Without a subscriber, uiState.value returns the initial ChatUiState().
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.uiState.collect { /* keep subscription alive */ }
        }
    }

    // ==========================================
    // A. QUEUED badge — queuedMessageIds computation
    // ==========================================

    @Test
    fun queuedMessageIds_empty_whenNoMessages() = runTest {
        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_empty_whenNoPendingAssistant() = runTest {
        // All assistant messages are completed
        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createAssistantMessage("a1", completed = 2000L, created = 1500L) to emptyList(),
            createUserMessage("u2", created = 3000L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_containsUserMessages_afterPendingAssistant() = runTest {
        // Assistant not completed — user messages after it should be marked
        stubMessages(
            createUserMessageWithText("u1", created = 1000L),
            createAssistantMessageWithText("a1", completed = null, created = 1500L),
            createUserMessageWithText("u2", created = 2000L),
            createUserMessageWithText("u3", created = 2500L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u2", "u3"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_excludesMessages_beforePendingAssistant() = runTest {
        // u1 is before pending assistant, should NOT be marked
        stubMessages(
            createUserMessageWithText("u1", created = 1000L),
            createUserMessageWithText("u2", created = 1200L),
            createAssistantMessageWithText("a1", completed = null, created = 1500L),
            createUserMessageWithText("u3", created = 2000L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u3"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_empty_whenNoUserAfterPendingAssistant() = runTest {
        // Pending assistant but no user messages after it
        stubMessages(
            createUserMessage("u1", created = 1000L) to emptyList(),
            createAssistantMessage("a1", completed = null, created = 1500L) to emptyList(),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_usesLastPendingAssistant() = runTest {
        // V1 uses indexOfLast on sorted (oldest-first) list: finds a2 (newest pending assistant).
        // Sorted: [a1(1500), u1(2000), a2(2500), u2(3000)]
        // indexOfLast → a2 at index 2; drop(3) → [u2]; queued = {"u2"}
        stubMessages(
            createAssistantMessageWithText("a1", completed = null, created = 1500L),
            createUserMessageWithText("u1", created = 2000L),
            createAssistantMessageWithText("a2", completed = null, created = 2500L),
            createUserMessageWithText("u2", created = 3000L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u2"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_cleared_whenAssistantCompletes() = runTest {
        // Initial load: assistant pending
        stubMessages(
            createAssistantMessageWithText("a1", completed = null, created = 1500L),
            createUserMessageWithText("u1", created = 2000L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        // Verify queued initially
        assertEquals(setOf("u1"), vm.uiState.value.queuedMessageIds)

        // Simulate state update: assistant completes by re-stubbing and refreshing
        // (pushMessages only updates V1 EventDispatcher, not V2 _sessionState)
        stubMessages(
            createAssistantMessageWithText("a1", completed = 3000L, created = 1500L),
            createUserMessageWithText("u1", created = 2000L),
        )
        vm.refreshSession()
        advanceUntilIdle()

        // Queued should be cleared
        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    // ==========================================
    // B. Sub-session identification — sessionParentId
    // ==========================================

    @Test
    fun sessionParentId_null_whenSessionHasNoParent() = runTest {
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession(parentId = null)
        setSession(createTestSession(parentId = null))

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertNull(vm.uiState.value.sessionParentId)
        collectJob.cancel()
    }

    @Test
    fun sessionParentId_set_whenSessionHasParent() = runTest {
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession(parentId = "parent-session-1")
        setSession(createTestSession(parentId = "parent-session-1"))

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals("parent-session-1", vm.uiState.value.sessionParentId)
        collectJob.cancel()
    }

    // ==========================================
    // C. subSessionId extraction logic
    // ==========================================

    /**
     * Extract subSessionId from a completed tool's metadata.
     * Mirrors the logic used in ChatScreen for sub-agent navigation.
     */
    private fun extractSubSessionId(tool: Part.Tool): String? {
        val state = tool.state
        if (state !is ToolState.Completed) return null
        val metadata = state.metadata ?: return null
        val element = metadata["sessionId"] ?: return null
        val value = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
        return value?.takeIf { it.isNotBlank() }
    }

    @Test
    fun subSessionId_null_whenStateIsRunning() {
        val tool = createToolPart(state = ToolState.Running())
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenNoMetadata() {
        val tool = createToolPart(
            state = ToolState.Completed(output = "done", metadata = null)
        )
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenNoSessionIdInMetadata() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("otherKey" to JsonPrimitive("value"))
            )
        )
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_returnsValue_whenPresent() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("sessionId" to JsonPrimitive("child-session-1"))
            )
        )
        assertEquals("child-session-1", extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenBlankValue() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("sessionId" to JsonPrimitive(""))
            )
        )
        assertNull(extractSubSessionId(tool))
    }

    @Test
    fun subSessionId_null_whenValueIsNotPrimitive() {
        val tool = createToolPart(
            state = ToolState.Completed(
                output = "done",
                metadata = mapOf("sessionId" to buildJsonObject { put("nested", JsonPrimitive("value")) })
            )
        )
        // jsonPrimitive would throw, runCatching catches it
        assertNull(extractSubSessionId(tool))
    }

    // ==========================================
    // D. Part.Agent source extraction logic
    // ==========================================

    /**
     * Extract the source string from a Part.Agent's source JsonElement.
     * Mirrors the logic used in ChatScreen for agent part rendering.
     */
    private fun extractAgentSource(source: JsonElement?): String {
        return runCatching { source?.jsonPrimitive?.contentOrNull }.getOrNull() ?: ""
    }

    @Test
    fun agentSource_empty_whenNull() {
        assertEquals("", extractAgentSource(null))
    }

    @Test
    fun agentSource_returnsValue_whenPrimitive() {
        assertEquals("mcp-server", extractAgentSource(JsonPrimitive("mcp-server")))
    }

    @Test
    fun agentSource_empty_whenNotPrimitive() {
        assertEquals("", extractAgentSource(buildJsonObject { put("key", JsonPrimitive("val")) }))
    }

    // ==========================================
    // E. Integration scenarios — multi-feature verification
    // ==========================================

    @Test
    fun queuedAndParentId_workTogether_inSubSession() = runTest {
        // Sub-session scenario: session has parentId, pending assistant + queued messages
        val session = createTestSession(parentId = "parent-1")
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns session
        setSession(session)

        stubMessages(
            createUserMessageWithText("u1", created = 1000L),
            createAssistantMessage("a1", completed = null, created = 1500L) to listOf(
                createToolPart(
                    id = "tool-1",
                    state = ToolState.Completed(
                        output = "Task completed",
                        metadata = mapOf("sessionId" to JsonPrimitive("grandchild-1"))
                    )
                )
            ),
            createUserMessageWithText("u2", created = 2000L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        val state = vm.uiState.value

        // sessionParentId correct
        assertEquals("parent-1", state.sessionParentId)

        // queuedMessageIds correct (u2 after pending assistant)
        assertEquals(setOf("u2"), state.queuedMessageIds)

        // Messages not empty
        assertTrue(state.messages.isNotEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_withMultipleRapidUserMessages() = runTest {
        // Simulate user rapidly sending 3 messages
        stubMessages(
            createAssistantMessageWithText("a1", completed = null, created = 1500L),
            createUserMessageWithText("u1", created = 2000L),
            createUserMessageWithText("u2", created = 2100L),
            createUserMessageWithText("u3", created = 2200L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        assertEquals(setOf("u1", "u2", "u3"), vm.uiState.value.queuedMessageIds)
        collectJob.cancel()
    }
}
