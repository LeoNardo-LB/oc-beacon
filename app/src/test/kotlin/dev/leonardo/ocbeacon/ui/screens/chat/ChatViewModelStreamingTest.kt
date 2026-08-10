package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionReadSignal
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStreamingTest {

    private val terminalRegistry: ServerTerminalRegistry = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    private val sendMessageUseCase: SendMessageUseCase = mockk()
    private val manageSessionUseCase: ManageSessionUseCase = mockk(relaxed = true)
    private val managePermissionUseCase: ManagePermissionUseCase = mockk(relaxed = true)
    private val selectModelUseCase: SelectModelUseCase = mockk(relaxed = true)
    private val manageAgentUseCase: ManageAgentUseCase = mockk(relaxed = true)
    private val manageTerminalUseCase: ManageTerminalUseCase = mockk(relaxed = true)
    private val draftRepository: DraftRepository = mockk(relaxed = true)
    private val shareExportUseCase: ShareExportUseCase = mockk(relaxed = true)
    private val undoRedoUseCase: UndoRedoUseCase = mockk(relaxed = true)
    private val messagePaging: MessagePaginationUseCase = mockk(relaxed = true)
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateService: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())
    private val partsFlow = MutableStateFlow<Map<String, List<dev.leonardo.ocbeacon.domain.model.Part>>>(emptyMap())
    private lateinit var chatRepository: ChatRepository
    private lateinit var sessionRepository: SessionRepository

    @After
    fun tearDown() {
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        coEvery { draftRepository.getDraft(any()) } returns null

        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.getSettingsFlow() } returns flowOf(
            AppSettings(
                terminalFontSize = 13f,
                initialMessageCount = 50,
                chatFontSize = "medium",
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

        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // 将 messagePaging.observeMessages 接线到可控 flow
        every { messagePaging.observeMessages(any()) } returns messagesFlow

        chatRepository = mockk<ChatRepository>(relaxed = true).also {
            every { it.getMessagesFlow(any()) } returns messagesFlow
            every { it.getParts(any()) } answers {
                val sid = firstArg<String>()
                partsFlow.map { map: Map<String, List<dev.leonardo.ocbeacon.domain.model.Part>> ->
                    map[sid] ?: emptyList()
                }
            }
            every { it.getAllPartsMap() } returns partsFlow
            every { it.getActiveToolProgressForSession(any()) } returns flowOf(emptyList())
            every { it.setMessages(any(), any()) } answers {
                val sid = firstArg<String>()
                val msgs = secondArg<List<dev.leonardo.ocbeacon.domain.model.MessageWithParts>>()
                messagesFlow.value = msgs.map { m -> m.info }
                partsFlow.value = partsFlow.value + (sid to msgs.flatMap { m -> m.parts })
            }
            every { it.replaceMessages(any(), any()) } answers {
                val sid = firstArg<String>()
                val msgs = secondArg<List<dev.leonardo.ocbeacon.domain.model.MessageWithParts>>()
                messagesFlow.value = msgs.map { m -> m.info }
                partsFlow.value = partsFlow.value + (sid to msgs.flatMap { m -> m.parts })
            }
            every { it.upsertMessages(any(), any(), any()) } answers {
                val sid = firstArg<String>()
                val msgs = secondArg<List<dev.leonardo.ocbeacon.domain.model.MessageWithParts>>()
                messagesFlow.value = msgs.map { m -> m.info }
                partsFlow.value = partsFlow.value + (sid to msgs.flatMap { m -> m.parts })
            }
            every { it.getSessionsSnapshot() } returns emptyList()
            every { it.getPermissionsWithChildren(any(), any()) } returns emptyList()
            every { it.getQuestionsWithChildren(any(), any()) } returns emptyList()
        }

        sessionRepository = mockk<SessionRepository>(relaxed = true).also {
            every { it.getSessionsFlow(any()) } returns flowOf(listOf(createTestSession()))
            every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
            every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
            every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
            coEvery { it.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        }
        every { sessionStateService.statusFlow } returns MutableStateFlow(emptyMap())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createTestSession() = Session(
        id = "test-session",
        title = "Test Session",
        directory = "/test",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createTestUserMessage(id: String = "msg-1") = Message.User(
        id = id,
        sessionId = "test-session",
        role = "user",
        time = TimeInfo(created = System.currentTimeMillis())
    )

    /** 用带文本 part 的用户消息 stub listMessages（可穿越 V1→V2 桥）。 */
    private fun stubUserMessage(id: String = "msg-1") {
        val userMsg = createTestUserMessage(id)
        val textPart = dev.leonardo.ocbeacon.domain.model.Part.Text(
            id = "$id-text",
            sessionId = "test-session",
            messageId = id,
            text = "hello"
        )
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns listOf(
            dev.leonardo.ocbeacon.domain.model.MessageWithParts(info = userMsg, parts = listOf(textPart))
        )
    }

    private fun createViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to "test-server",
            "sessionId"  to "test-session"
        ))
        return ChatViewModel(
            savedStateHandle = savedState,
            sendMessageUseCase = sendMessageUseCase,
            manageSessionUseCase = manageSessionUseCase,
            managePermissionUseCase = managePermissionUseCase,
            selectModelUseCase = selectModelUseCase,
            manageAgentUseCase = manageAgentUseCase,
            manageTerminalUseCase = manageTerminalUseCase,
            draftRepository = draftRepository,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            toolCardResolver = DefaultToolCardResolver(),
            chatRepository = chatRepository,
            sessionRepository = sessionRepository,
            messagePaging = messagePaging,
            messageStore = mockk(relaxed = true),
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),

            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            sessionReadSignal = SessionReadSignal(),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
        )
    }

    /**
     * messageListState 由 stateIn(WhileSubscribed) 支撑，需要活跃订阅者。
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToMessageState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.messageListState.collect {         /* 保持订阅存活 */ }
        }
    }

    // ========== 测试 1：refreshSession 不会将 isLoading 设为 true ==========

    @Test
    fun `refreshSession does not set isLoading to true`() = runTest {
        // 给定：已有消息的 ViewModel（经由 V1→V2 桥）
        stubUserMessage("msg-1")
        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // 验证刷新前消息已存在
        val beforeRefresh = vm.messageListState.value.messages
        assertTrue("Messages should exist before refresh", beforeRefresh.isNotEmpty())

        // 当：调用 refreshSession
        vm.refreshSession()
        advanceUntilIdle()

        // 那么：消息不应被清空（因为 refreshSession 使用 _isRefreshing 而非 _isLoading）
        val afterRefresh = vm.messageListState.value.messages
        assertTrue(
            "Messages should NOT be wiped during refresh, got ${afterRefresh.size} messages",
            afterRefresh.isNotEmpty()
        )

        collectJob.cancel()
    }

    // ========== 测试 2：刷新时 V1 setMessages 替换状态 ==========

    @Test
    fun `messageListState matches refresh result in V1`() = runTest {
        // 给定：通过初始加载获得的消息
        stubUserMessage("msg-1")

        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // 验证初始消息存在
        assertTrue(
            "Initial messages should exist",
            vm.messageListState.value.messages.isNotEmpty()
        )

        // 当：REST 刷新返回空消息（例如服务器延迟）
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        vm.refreshSession()
        advanceUntilIdle()

        // 那么：V1 setMessages 做全量替换 —— 消息被清空
        val state = vm.messageListState.value
        assertTrue(
            "V1 setMessages replaces state, got ${state.messages.size} messages",
            state.messages.isEmpty()
        )

        collectJob.cancel()
    }

    // ========== 测试 3：V1 refreshIfNeeded 始终触发刷新 ==========

    @Test
    fun `refreshIfNeeded triggers refresh in V1`() = runTest {
        // 给定：ViewModel
        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // init 后清除 mock 状态（init 调用 loadMessages → listMessages 一次）
        coVerify(atLeast = 1) { manageSessionUseCase.listMessages(any(), any(), any()) }
        clearMocks(manageSessionUseCase, answers = false)

        // 当：调用 refreshIfNeeded（V1 无冷却 —— 始终刷新）
        vm.refreshIfNeeded()
        advanceUntilIdle()

        // 那么：应调用 listMessages（V1 委托给 refreshSession）
        coVerify(atLeast = 1) { manageSessionUseCase.listMessages(any(), any(), any()) }

        collectJob.cancel()
    }

    // ========== 测试 4：loading 守卫只清空真正为空的消息列表 ==========

    @Test
    fun `loading guard only clears truly empty message lists`() = runTest {
        // 给定：恰好 1 条带文本 part 的消息（可穿越 V1→V2 桥）
        val userMsg = createTestUserMessage("msg-1")
        val textPart = dev.leonardo.ocbeacon.domain.model.Part.Text(
            id = "msg-1-text",
            sessionId = "test-session",
            messageId = "msg-1",
            text = "hello"
        )
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns listOf(
            dev.leonardo.ocbeacon.domain.model.MessageWithParts(info = userMsg, parts = listOf(textPart))
        )

        val vm = createViewModel()
        val collectJob = subscribeToMessageState(vm)
        advanceUntilIdle()

        // 那么：尽管 size < 3，消息也不应被清空
        // loading 守卫使用：loading && sessionMessages.isEmpty()
        // 有 1 条消息时，sessionMessages 不为空，因此消息被保留
        val state = vm.messageListState.value
        assertTrue(
            "Messages should not be cleared when list has 1 message (isEmpty check, not size < 3), got ${state.messages.size}",
            state.messages.isNotEmpty()
        )

        collectJob.cancel()
    }

    // ========== 测试 5：loadSession 应用 settings 中的 initialMessageCount ==========

    @Test
    fun `loadSession applies initialMessageCount from settings as listMessages limit`() = runTest {
        // 给定：settings mock 返回 initialMessageCount = 50（见 setup）
        val vm = createViewModel()
        advanceUntilIdle()

        // 那么：必须以 limit = 50 调用 listMessages（来自 AppSettings.initialMessageCount）。
        // 修复前，loadSession 硬编码 limit=200，loadMessages 使用 currentMessageLimit=20，
        // 因此用户的 "initial message count" 设置从未被使用。
        coVerify(atLeast = 1) { manageSessionUseCase.listMessages(any(), any(), eq(50)) }
    }
}
