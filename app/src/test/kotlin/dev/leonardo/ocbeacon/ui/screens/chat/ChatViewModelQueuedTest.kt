package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.SettingsDataStore

import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import android.util.Log
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionReadSignal
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
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
 * 针对 4 个功能的综合测试：
 * A. QUEUED 徽章 —— queuedMessageIds 计算
 * B. 子会话标识 —— sessionParentId
 * C. 从 tool metadata 中提取 subSessionId 的逻辑
 * D. Part.Agent 的 source 提取逻辑
 * E. 结合多个功能的集成场景
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelQueuedTest {

    // === Mock 与基础设施 ===

    private lateinit var eventDispatcher: EventDispatcher
    private val terminalRegistry: ServerTerminalRegistry = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

    // UseCase mock 定义
    private val sendMessageUseCase: SendMessageUseCase = mockk(relaxed = true)
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
    private val pendingPromptRepository = mockk<dev.leonardo.ocbeacon.domain.repository.PendingPromptRepository>(relaxed = true)
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val testSessionId = "test-session-1"
    private val testServerId = "test-server-1"
    private val testDirectory = "/home/test"

    // P5-1：queuedMessageIds 现在由 FSM 状态派生（Idle 强制清空）。
    // 验证 queued 逻辑的测试需要会话处于 Busy。
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
            sessionStateService = sessionStateService,
            settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
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

        // Draft 桩
        every { draftRepository.getDraft(any()) } returns null

        // Settings 桩
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

        // UseCase 桩 —— 默认值
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession()
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingQuestions(any(), any()) } returns emptyList()
        coEvery { managePermissionUseCase.listPendingPermissions(any(), any()) } returns emptyList()
        coEvery { selectModelUseCase.loadProviders(any()) } returns ProvidersResponse(emptyList())
        coEvery { manageAgentUseCase.loadAgents(any()) } returns emptyList()
        coEvery { manageAgentUseCase.loadCommands(any()) } returns emptyList()

        // 将 messagePaging.observeMessages 接线为委托到 eventDispatcher.messages
        every { messagePaging.observeMessages(any()) } answers {
            eventDispatcher.messages.map { msgs -> msgs[firstArg<String>()] ?: emptyList() }
        }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // === 辅助方法 ===

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

    /** 带 text part 的 User 消息 —— 能经受 V1→V2 桥接转换。 */
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

    /** 带 text part 的 Assistant 消息 —— 能经受 V1→V2 桥接转换。 */
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
            draftRepository = draftRepository,
            shareExportUseCase = shareExportUseCase,
            undoRedoUseCase = undoRedoUseCase,
            settingsRepository = settingsRepository,
            terminalRegistry = terminalRegistry,
            toolCardResolver = dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver(),
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

            sessionStateService = sessionStateService,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            sessionReadSignal = SessionReadSignal(),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            pendingPromptRepository = pendingPromptRepository,
            serverRepository = serverRepository,
        )
    }

    /**
     * 模拟初始加载后到达的 SSE 更新。
     */
    private fun pushMessages(messages: List<Pair<Message, List<Part>>>) {
        val messageWithParts = messages.map { (msg, parts) ->
            MessageWithParts(info = msg, parts = parts)
        }
        eventDispatcher.setMessages(testSessionId, messageWithParts)
    }

    /** 将 session 设置到 EventDispatcher 中。 */
    private fun setSession(session: Session) {
        eventDispatcher.setSessions(testServerId, listOf(session))
    }

    /**
     * 配置 manageSessionUseCase.listMessages 返回给定的 MessageWithParts，
     * 以便 VM 的 init loadMessages() 会将它们填充到 EventDispatcher。
     */
    private fun stubMessages(vararg messages: Pair<Message, List<Part>>) {
        val messageWithParts = messages.map { (msg, parts) ->
            MessageWithParts(info = msg, parts = parts)
        }
        coEvery { manageSessionUseCase.listMessages(any(), any(), any()) } returns messageWithParts
    }

    /**
     * 订阅 uiState 以激活 SharingStarted.WhileSubscribed 的上游。
     * 没有订阅者时，uiState.value 返回初始的 ChatUiState()。
     */
    private fun kotlinx.coroutines.test.TestScope.subscribeToState(vm: ChatViewModel): Job {
        return backgroundScope.launch {
            vm.uiState.collect {         /* 保持订阅存活 */ }
        }
    }

    // ==========================================
    // A. QUEUED 徽章 —— queuedMessageIds 计算
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
        // 所有 assistant 消息均已完成
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
        // assistant 未完成 —— 其后的用户消息应被标记
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
        // u1 在待处理 assistant 之前，不应被标记
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
        // 有待处理 assistant，但其后没有用户消息
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
        // V1 在排序（旧→新）列表上使用 indexOfLast：找到 a2（最新的待处理 assistant）。
        // 排序后：[a1(1500), u1(2000), a2(2500), u2(3000)]
        // indexOfLast → a2 在索引 2；drop(3) → [u2]；queued = {"u2"}
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
        // 初始加载：assistant 待处理
        stubMessages(
            createAssistantMessageWithText("a1", completed = null, created = 1500L),
            createUserMessageWithText("u1", created = 2000L),
        )

        val vm = createViewModel()
        val collectJob = subscribeToState(vm)
        advanceUntilIdle()

        // 验证初始 queued 状态
        assertEquals(setOf("u1"), vm.uiState.value.queuedMessageIds)

        // 模拟状态更新：通过重新 stub 并刷新使 assistant 完成
        // （pushMessages 只更新 V1 EventDispatcher，不更新 V2 _sessionState）
        stubMessages(
            createAssistantMessageWithText("a1", completed = 3000L, created = 1500L),
            createUserMessageWithText("u1", created = 2000L),
        )
        vm.refreshSession()
        advanceUntilIdle()

        // queued 应被清空
        assertTrue(vm.uiState.value.queuedMessageIds.isEmpty())
        collectJob.cancel()
    }

    // ==========================================
    // B. 子会话标识 —— sessionParentId
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
    // C. subSessionId 提取逻辑
    // ==========================================

    /**
     * 从已完成的 tool 的 metadata 中提取 subSessionId。
     * 镜像 ChatScreen 中用于子 agent 导航的逻辑。
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
        // jsonPrimitive 会抛异常，runCatching 捕获它
        assertNull(extractSubSessionId(tool))
    }

    // ==========================================
    // D. Part.Agent 的 source 提取逻辑
    // ==========================================

    /**
     * 从 Part.Agent 的 source JsonElement 中提取 source 字符串。
     * 镜像 ChatScreen 中用于 agent part 渲染的逻辑。
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
    // E. 集成场景 —— 多特性验证
    // ==========================================

    @Test
    fun queuedAndParentId_workTogether_inSubSession() = runTest {
        // 子会话场景：会话有 parentId、待处理 assistant + 排队消息
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

        // sessionParentId 正确
        assertEquals("parent-1", state.sessionParentId)

        // queuedMessageIds 正确（u2 在待处理 assistant 之后）
        assertEquals(setOf("u2"), state.queuedMessageIds)

        // 消息不为空
        assertTrue(state.messages.isNotEmpty())
        collectJob.cancel()
    }

    @Test
    fun queuedMessageIds_withMultipleRapidUserMessages() = runTest {
        // 模拟用户快速发送 3 条消息
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
