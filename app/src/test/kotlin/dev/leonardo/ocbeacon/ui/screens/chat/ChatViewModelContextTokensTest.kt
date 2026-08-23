package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.data.repository.ShellJobsStore
import dev.leonardo.ocbeacon.data.repository.UnreadBadgeService
import dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
import android.util.Log
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import io.ktor.client.HttpClient
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.StreamingOwnershipRegistry
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.service.AppNotificationManager
import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.*
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.ui.screens.chat.tools.DefaultToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionScrollSignal
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.SavedStateHandle

/**
 * 2026-08-17 上下文占用口径修正（ACP：input+cache.read）的回归测试。
 *
 * lastContextTokens 唯一写入源 = 消息级快照（最后一条 output>0 的 assistant
 * 消息），口径 = input + cache.read。三个 session 级累计兜底（冷启动
 * bootstrap / V2 usage.updated / 压缩后 maxOf）已全部删除——它们把 SQL
 * 累计 tokens（每轮累加、压缩不下降）当「当前上下文占用」，导致指示器
 * 显示超 100%（如 104%）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelContextTokensTest {

    // === Mock 与基础设施（复用 ChatViewModelQueuedTest 模式） ===

    private lateinit var eventDispatcher: EventDispatcher
    private val terminalRegistry: ServerTerminalRegistry = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk()
    private val testDispatcher = UnconfinedTestDispatcher()

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
    private val sessionStateRepository: SessionStateService = mockk(relaxed = true)
    private val sessionFocusHolder = mockk<SessionFocusHolder>(relaxed = true)
    private val appNotificationManager = mockk<AppNotificationManager>(relaxed = true)
    private val toolSnapshotCache = ToolSnapshotCache()
    private val serverRepository = mockk<ServerRepository>(relaxed = true)

    private val testSessionId = "test-session-1"
    private val testServerId = "test-server-1"
    private val testDirectory = "/home/test"

    private val testStatusFlow = MutableStateFlow<Map<String, SessionStatus>>(
        mapOf(testSessionId to SessionStatus.Idle)
    )

    // VM 侧 eventDispatcher mock 的可控 flow（2026-08-17 后 sessionUsage 不再被消费）
    private val vmEventDispatcher: EventDispatcher = mockk(relaxed = true)
    private val usageFlow = MutableStateFlow<Map<String, SessionNextEvent.UsageUpdated>>(emptyMap())
    private val compactedFlow = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val messageStore = MessageEventHandler()
        val settingsDataStore = mockk<SettingsDataStore>(relaxed = true)
        eventDispatcher = EventDispatcher(
            sessionHandler = SessionEventHandler(),
            messageHandler = messageStore,
            permissionHandler = PermissionEventHandler(),
            questionHandler = QuestionEventHandler(),
            miscHandler = MiscEventHandler(),
            sessionNextHandler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker()),
            sessionStateRepository = sessionStateRepository,
            settingsDataStore = settingsDataStore,
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
            shellJobsHandler = ShellJobsHandler(ShellJobsStore()),
            ownershipRegistry = StreamingOwnershipRegistry(),
            sessionRepoProvider = object : javax.inject.Provider<dev.leonardo.ocbeacon.domain.repository.SessionRepository> {
                override fun get() = io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.SessionRepository>(relaxed = true)
            },
            // #122 接线新增：自动批准（relaxed mock——既有用例不受影响）
            permissionAutoApprover = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PermissionAutoApprover>(relaxed = true),
            chatRepoProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.domain.repository.ChatRepository>(relaxed = true) },
            // 堆积消息管线（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessagePipelineProvider = javax.inject.Provider { io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true) },
            pendingMessageRepository = io.mockk.mockk(relaxed = true),
        )
        every { sessionStateRepository.statusFlow } returns testStatusFlow
        every { sessionStateRepository.activityFlow } returns MutableStateFlow(emptyMap())

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // Draft 桩
        coEvery { draftRepository.getDraft(any()) } returns null

        // Settings 桩
        every { settingsRepository.hiddenModels(any()) } returns flowOf(emptySet())
        every { settingsRepository.defaultModel(any()) } returns flowOf(null)
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

        // VM 侧 mock eventDispatcher 的可控 flow
        every { vmEventDispatcher.sessionUsage } returns usageFlow
        every { vmEventDispatcher.compactedSessions } returns compactedFlow
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // === 辅助方法 ===

    private fun createTestSession(
        id: String = testSessionId,
        tokens: Session.SessionTokens? = null,
    ): Session = Session(
        id = id,
        title = "Test Session",
        directory = testDirectory,
        time = Session.Time(created = 1000L, updated = 2000L),
        tokens = tokens,
    )

    /** 带 tokens + text part 的 Assistant 消息（能经受 V1→V2 桥接转换）。 */
    private fun assistantWithTokens(
        id: String,
        input: Int,
        output: Int,
        reasoning: Int = 0,
        cacheRead: Int = 0,
        cacheWrite: Int = 0,
        created: Long = 1000L,
    ): Pair<Message.Assistant, List<Part>> = Message.Assistant(
        id = id,
        sessionId = testSessionId,
        time = TimeInfo(created = created, completed = created + 1000L),
        parentId = "",
        tokens = Message.Assistant.Tokens(
            input = input,
            output = output,
            reasoning = reasoning,
            cache = Message.Assistant.Tokens.Cache(read = cacheRead, write = cacheWrite),
        ),
    ) to listOf(
        Part.Text(id = "$id-text", sessionId = testSessionId, messageId = id, text = "resp")
    )

    private fun createViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(
            "serverUrl"  to "http://localhost:8080",
            "username"   to "testuser",
            "password"   to "testpass",
            "serverName" to "TestServer",
            "serverId"   to testServerId,
            "sessionId"  to testSessionId
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
            chatRepository = mockk<ChatRepository>(relaxed = true).also { chatRepo ->
                coEvery { chatRepo.listActiveSessions(any()) } returns kotlin.Result.success(emptyMap())
                every { chatRepo.getMessagesFlow(any()) } answers { eventDispatcher.messages.map { it[firstArg<String>()] ?: emptyList() } }
                every { chatRepo.getParts(any()) } answers { eventDispatcher.parts.map { it[firstArg<String>()] ?: emptyList() } }
                every { chatRepo.getAllPartsMap() } returns eventDispatcher.parts
                every { chatRepo.getActiveToolProgressForSession(any()) } returns flowOf(emptyList())
                every { chatRepo.upsertMessages(any(), any(), any()) } answers { eventDispatcher.upsertMessages(firstArg(), secondArg(), thirdArg()) }
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
            messageStore = mockk(relaxed = true),
            tokenStatsTracker = tokenStatsTracker,
            httpClient = mockk<HttpClient>(relaxed = true),

            sessionStateRepository = sessionStateRepository,
            sessionFocusHolder = sessionFocusHolder,
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService>(relaxed = true),
            appNotificationManager = appNotificationManager,
            toolSnapshotCache = toolSnapshotCache,
            serverRepository = serverRepository,
            shellJobsStore = ShellJobsStore(),
            eventDispatcher = vmEventDispatcher,
            // 堆积消息（2026-08-20 构造新增）：relaxed mock——既有用例不受影响
            pendingMessageRepository = mockk(relaxed = true),
            pendingMessagePipeline = mockk<dev.leonardo.ocbeacon.data.repository.PendingMessagePipeline>(relaxed = true).also { mk ->
                // drainingSessions 暴露真实空 StateFlow——relaxed mock 的属性 getter
                // 会返回无 value 的 mock flow，VM init 链上任何收集都可能挂起
                every { mk.drainingSessions } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
            },
        )
    }

    private fun pushMessages(messages: List<Pair<Message, List<Part>>>) {
        eventDispatcher.upsertMessages(
            testSessionId,
            messages.map { (msg, parts) -> MessageWithParts(info = msg, parts = parts) },
            dev.leonardo.ocbeacon.domain.model.MergeStrategy.SSE_PRIORITY,
        )
    }

    // === 用例 ===

    /**
     * #186 根因修复：VM init 的 serverRepository.getServer 跑在真实 Dispatchers.IO，
     * 其完成时刻相对虚拟时间不确定——下游 collect 链（messageListState → tracker）
     * 的装配与 advanceUntilIdle 竞态，断言偶发读到中间态。
     * 本助手在虚拟时间推进与真实时间让出间轮询，直到值收敛或超时（2s）后硬断言。
     */
    private fun kotlinx.coroutines.test.TestScope.awaitTrackerValue(expected: Int, actual: () -> Int) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            if (actual() == expected) return
            Thread.sleep(10) // 让真实 IO 线程完成并向 Main 测试队列投递
        }
        assertEquals(expected, actual())
    }

    @Test
    fun `message snapshot context tokens equals input plus cache read`() = runTest(testDispatcher) {
        createViewModel()
        advanceUntilIdle()

        // 五项俱全的快照：占用只算 input + cache.read（= 1000 + 5000 = 6000），
        // 不含 output/reasoning/cache.write（旧口径五项相加 = 6900 → 超 100% 根因）
        pushMessages(listOf(assistantWithTokens(
            id = "a1", input = 1000, output = 200, reasoning = 300, cacheRead = 5000, cacheWrite = 400
        )))
        advanceUntilIdle()

        awaitTrackerValue(6000) { tokenStatsTracker.stats.value.lastContextTokens }
        // 消耗统计字段仍为完整快照（不受口径修正影响）
        assertEquals(1000, tokenStatsTracker.stats.value.totalInputTokens)
        assertEquals(200, tokenStatsTracker.stats.value.totalOutputTokens)
        assertEquals(300, tokenStatsTracker.stats.value.totalReasoningTokens)
        assertEquals(5000, tokenStatsTracker.stats.value.totalCacheReadTokens)
        assertEquals(400, tokenStatsTracker.stats.value.totalCacheWriteTokens)
    }

    @Test
    fun `usage updated event does not write lastContextTokens`() = runTest(testDispatcher) {
        // 先有消息级快照（6000），再收到远超它的 session 累计 usage（500000）——
        // 旧逻辑在 lastContextTokens==0 时写入累计值；新口径一律不写
        createViewModel()
        advanceUntilIdle()
        pushMessages(listOf(assistantWithTokens(
            id = "a1", input = 1000, output = 200, cacheRead = 5000
        )))
        advanceUntilIdle()

        usageFlow.value = mapOf(
            testSessionId to SessionNextEvent.UsageUpdated(
                sessionId = testSessionId,
                cost = 1.5,
                tokens = SessionNextEvent.SessionUsageTokens(
                    input = 400000, output = 90000, reasoning = 10000
                ),
            )
        )
        advanceUntilIdle()

        awaitTrackerValue(6000) { tokenStatsTracker.stats.value.lastContextTokens }
    }

    @Test
    fun `cold start session tokens do not seed lastContextTokens`() = runTest(testDispatcher) {
        // session 级 SQL 累计 tokens（每轮累加、压缩不下降）不再是冷启动初值
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession(
            tokens = Session.SessionTokens(input = 400000, output = 90000, reasoning = 10000)
        )

        createViewModel()
        advanceUntilIdle()

        assertEquals(0, tokenStatsTracker.stats.value.lastContextTokens)
    }

    @Test
    fun `compaction does not raise lastContextTokens and snapshot falls back`() = runTest(testDispatcher) {
        // 压缩后 session 累计 tokens（500000）不抬高占用；消息级快照变小后自然回落
        coEvery { manageSessionUseCase.getSession(any(), any()) } returns createTestSession(
            tokens = Session.SessionTokens(input = 400000, output = 90000, reasoning = 10000)
        )
        createViewModel()
        advanceUntilIdle()

        // 压缩前的大快照：8000 + 40000 = 48000
        pushMessages(listOf(assistantWithTokens(
            id = "a1", input = 8000, output = 100, cacheRead = 40000
        )))
        advanceUntilIdle()
        awaitTrackerValue(48000) { tokenStatsTracker.stats.value.lastContextTokens }

        // session.compacted 到达（原 maxOf 兜底已删——不得用累计值抬高）
        compactedFlow.value = setOf(testSessionId)
        advanceUntilIdle()
        awaitTrackerValue(48000) { tokenStatsTracker.stats.value.lastContextTokens }

        // 压缩后消息刷新为小快照（压缩消息，时间戳更晚 → lastOrNull 命中）→ 自然回落：500 + 2000 = 2500
        pushMessages(listOf(assistantWithTokens(
            id = "a2", input = 500, output = 50, cacheRead = 2000, created = 5000L
        )))
        advanceUntilIdle()
        awaitTrackerValue(2500) { tokenStatsTracker.stats.value.lastContextTokens }
    }
}
