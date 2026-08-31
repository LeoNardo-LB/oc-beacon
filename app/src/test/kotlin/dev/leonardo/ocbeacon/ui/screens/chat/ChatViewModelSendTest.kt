package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendTest {

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
    private val chatRepository: ChatRepository = mockk(relaxed = true)
    private val tokenStatsTracker = TokenStatsTracker()
    private val sessionStateRepository: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val eventDispatcher = mockk<dev.leonardo.ocbeacon.data.repository.EventDispatcher>(relaxed = true)

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
        every { settingsRepository.defaultModel(any()) } returns flowOf(null)
        every { settingsRepository.getSettingsFlow() } returns flowOf(
            AppSettings(
                terminalFontSize = 13f,
                initialMessageCount = 50,
                chatFontSize = "medium",
                confirmBeforeSend = false,
                compactMessages = false,
                autoExpandTools = false,
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

        // 将 messagePaging.observeMessages 接线为返回空消息列表
        every { messagePaging.observeMessages(any()) } returns flowOf(emptyList())

        every { chatRepository.getAllPartsMap() } returns MutableStateFlow(emptyMap<String, List<dev.leonardo.ocbeacon.domain.model.Part>>())
        coEvery { chatRepository.listActiveSessions(any()) } returns kotlin.Result.success(emptyMap())
        every { chatRepository.getMessagesFlow(any()) } returns flowOf(emptyList())
        every { chatRepository.getActiveToolProgressForSession(any()) } returns flowOf(emptyList())
        // interactionState combine 依赖这三个源发射 —— relaxed mock 的 Flow 不发射会导致
        // stateIn(WhileSubscribed) 永不产生首发射，.value 恒为初始值
        every { chatRepository.getAllQuestionsFlow() } returns flowOf(emptyMap())
        every { chatRepository.getAllPermissionsFlow() } returns flowOf(emptyMap())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createTestSession() = dev.leonardo.ocbeacon.domain.model.Session(
        id = "test-session",
        title = "Test Session",
        directory = "/test",
        time = dev.leonardo.ocbeacon.domain.model.Session.Time(created = 1000L, updated = 2000L)
    )

    private fun createViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to "test-server",
            "sessionId"  to "test-session"
        ))
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap())
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
            toolCardResolver = dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver(),
            chatRepository = chatRepository,
            sessionRepository = mockk<SessionRepository>(relaxed = true).also {
                every { it.getSessionsFlow(any()) } returns flowOf(emptyList())
                every { it.getSessionStatusesFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentAgentFlow(any()) } returns flowOf(emptyMap())
                every { it.getCurrentModelFlow(any()) } returns flowOf(emptyMap())
            },
            messagePaging = messagePaging,
            messageStore = mockk(relaxed = true),
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk(relaxed = true),

            sessionStateRepository = sessionStateRepository,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
            shellJobsStore = ShellJobsStore(),
            eventDispatcher = eventDispatcher,
            // 堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessageRepository = mockk(relaxed = true),
            pendingMessagePipeline = mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true).also { mk ->
                // drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter
                // 会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起
                every { mk.drainingSessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
            },
            // #271（2026-08-30 构造新增）：relaxed mock——首开自动 drain 触发不破坏既有用例
            historySyncManager = mockk(relaxed = true),
            dshJobsStore = dev.leonardo.ocbeacon.data.repository.DshJobsStore(),
            dshQueueStore = dev.leonardo.ocbeacon.data.repository.DshQueueStore(),

        )
    }

    /**
     * restoredDraftState 由 stateIn 支撑，需要活跃订阅者才能发出更新。
     * 没有订阅者时，value 返回初始值。
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.composer.restoredDraftState.collect {         /* 保持订阅存活 */ }
        }
    }

    /** interactionState 是 stateIn(WhileSubscribed)，无订阅者时 value 恒为初始值。 */
    private fun kotlinx.coroutines.test.TestScope.subscribeToInteractionState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.interactionState.collect {         /* 保持订阅存活 */ }
        }
    }

    // ========== 悲观消息语义（Task 7） ==========

    @Test
    fun `isSending flips during send and clears after REST accepted`() = runTest {
        // coAnswers + delay 模拟 POST 受理中的网络窗口：isSending 保持 true 直到响应返回
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(1_000)
        }
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        val interactionJob = subscribeToInteractionState(viewModel)
        advanceUntilIdle()
        viewModel.sendMessage("Hello world")
        runCurrent()
        assertTrue(viewModel.interactionState.value.isSending)  // 发送中（POST 受理前）
        advanceUntilIdle()
        assertFalse(viewModel.interactionState.value.isSending) // 204 后恢复（可连续发送）
        collectJob.cancel()
        interactionJob.cancel()
    }

    @Test
    fun `send failure emits sendFailure alert and clears isSending`() = runTest {
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        val interactionJob = subscribeToInteractionState(viewModel)
        advanceUntilIdle()
        viewModel.sendMessage("Hello world")
        advanceUntilIdle()
        // 2026-08-11 用户要求：失败 → AlertDialog（sendFailure 信号），不再回填草稿
        //（输入框内容在发送期间保留，无需 restoredDraft 恢复）
        assertNotNull(viewModel.sendFailure.value) // AlertDialog 信号
        assertNull(
            "输入框保留语义下不应再设置 restoredDraft",
            viewModel.composer.restoredDraftState.value
        )
        assertFalse(viewModel.interactionState.value.isSending) // finally 复位
        collectJob.cancel()
        interactionJob.cancel()
    }

    @Test
    fun `double send is ignored while sending`() = runTest {
        // sendPrompt 挂起期间 isSending=true，第二次 sendMessage 应被 isSendingValue 守卫拦截
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            delay(1_000)
        }
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()
        viewModel.sendMessage("first")
        viewModel.sendMessage("second") // isSending 期间应被忽略
        advanceUntilIdle()
        coVerify(exactly = 1) { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) }
        collectJob.cancel()
    }

    // ========== 保留：草稿恢复与消费（悲观语义下） ==========

    @Test
    fun `send failure emits sendFailure in V1`() = runTest {
        // V1 sendParts() 捕获异常后：输入框内容保留（发送期间不清空），
        // 通过 sendFailureSink 触发 AlertDialog。restoredDraft 不再设置。
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("Network error")

        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        val interactionJob = subscribeToInteractionState(viewModel)
        advanceUntilIdle()

        viewModel.sendMessage("Hello world")
        advanceUntilIdle()

        // 2026-08-11 用户要求：失败 → AlertDialog（sendFailure），输入框保留消息
        assertNotNull("sendFailure 应携带错误信息", viewModel.sendFailure.value)
        assertNull("输入框保留语义下不应设置 restoredDraft", viewModel.composer.restoredDraftState.value)
        assertFalse(viewModel.interactionState.value.isSending)
        collectJob.cancel()
        interactionJob.cancel()
    }

    @Test
    fun `consumeRestoredDraft is safe when already null`() = runTest {
        val viewModel = createViewModel()
        val collectJob = subscribeToState(viewModel)
        advanceUntilIdle()

        // restoredDraft 初始为 null（未发生撤销/还原）
        assertNull(viewModel.composer.restoredDraftState.value)

        // 调用 consume 不应崩溃，且保持为 null
        viewModel.composer.consumeRestoredDraft()
        advanceUntilIdle()

        assertNull(
            "restoredDraft should remain null after consume when already null",
            viewModel.composer.restoredDraftState.value
        )
        collectJob.cancel()
    }

    // ========== D1③/B1：sendMessage 清卡 + 会话错误弹窗 ==========

    @Test
    fun `send success clears persistent session errors`() = runTest {
        clearMocks(eventDispatcher)
        coEvery { sendMessageUseCase.sendPrompt(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.sendMessage("Hello world")
        advanceUntilIdle()
        // D1③：sendMessage 成功 → 清空该会话持久错误卡（会话已恢复健康）
        verify { eventDispatcher.clearSessionErrors("test-session") }
    }

    @Test
    fun `session error event feeds one-time snackbar toast not dialog`() = runTest {
        clearMocks(eventDispatcher)
        val errorFlow = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
        every { eventDispatcher.sessionErrorEvents } returns errorFlow
        val viewModel = createViewModel()
        advanceUntilIdle()
        errorFlow.tryEmit("test-session" to "provider rejected request: insufficient balance")
        advanceUntilIdle()
        // DSH toast 对位（Web 无 dialog）：事件 → 一次性 snackbar 状态
        assertEquals("provider rejected request: insufficient balance", viewModel.sessionErrorToast.value)
        // 会话运行错误不得再复用发送失败 AlertDialog（DSH 无此弹窗）
        assertNull("session errors must not feed the sendFailure dialog", viewModel.sendFailure.value)
        // 一次性：消费后清空（同 snackbar auto-dismiss 语义）
        viewModel.consumeSessionErrorToast()
        assertNull(viewModel.sessionErrorToast.value)
    }
}