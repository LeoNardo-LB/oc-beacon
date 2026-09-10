package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.testing.FakeServerAdapterResolver
import android.util.Log
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.Workspace
import dev.leonardo.ocbeacon.domain.model.WorkspaceSnapshot
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
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
import dev.leonardo.ocbeacon.ui.screens.sessions.components.WorkspaceDialogEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #311 Task3：新建会话对话框连接语义 + 快照→条目映射（viewModel 层）——
 * 连接语义对齐 web connectWorkspace（mod29:46-58）：复用组内 blank ∧ cwd===path ∧
 * 未归档会话（跳转不新建），否则 createSession(workspaceId)；stray/回退条目维持
 * 目录导航懒建；断连 #267 快速失败。条目流：V012 快照权威，空快照回退
 * （DSH=listProjects 投影 / 非 DSH=最近目录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelWorkspaceConnectTest {

    private val sseConnectionManager = mockk<dev.leonardo.ocbeacon.service.SseConnectionManager>(relaxed = true).also {
        every { it.linkState(any()) } returns dev.leonardo.ocbeacon.service.ServerLinkState.Connected
        every { it.observeLinkState(any()) } returns flowOf(dev.leonardo.ocbeacon.service.ServerLinkState.Connected)
        every { it.dshTokenNeededServers } returns MutableStateFlow(emptySet<String>())
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
    private val chatRepository: ChatRepository = mockk(relaxed = true)
    private val eventDispatcher: dev.leonardo.ocbeacon.data.repository.EventDispatcher = mockk(relaxed = true)
    private val serverRepository: ServerRepository = mockk(relaxed = true)
    private val getSettingsFlowUseCase: GetSettingsFlowUseCase = mockk()

    private val workspaceFlow = MutableStateFlow(WorkspaceSnapshot())
    private val sessionsFlow = MutableStateFlow<List<Session>>(emptyList())

    private fun session(
        id: String,
        directory: String,
        updated: Long = 1000L,
        blank: Boolean = false,
    ): Session = Session(
        id = id,
        directory = directory,
        time = Session.Time(created = 0L, updated = updated),
        blank = blank,
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { sessionRepository.getSessionsFlow(any()) } returns sessionsFlow
        every { sessionRepository.getServerSessionsFlow() } returns emptyFlow()
        every { sessionRepository.getLastUserMessageTimeFlow() } returns emptyFlow()
        every { sessionRepository.getLastCompletedReplyTimeFlow() } returns emptyFlow()
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
        coEvery { listProjectsUseCase(any()) } returns Result.success(emptyList())
        coEvery { listSessionsUseCase(any(), any(), any(), any(), any()) } returns emptyList()
        every { chatRepository.getWorkspaceSnapshotFlow(any()) } returns workspaceFlow
        every { getSettingsFlowUseCase() } returns flowOf(AppSettings())
        coEvery { serverRepository.getServer(any()) } returns null
        // init 能力位内会拉 Agent 预设 roster（DSH 配置下）——显式空表防 relaxed Result 形状
        coEvery { chatRepository.listAgentPresets(any()) } returns Result.success(emptyList())
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    private fun workspaceEntry(
        workspaceId: String?,
        path: String,
        title: String = path.substringAfterLast('/'),
        count: Int = 0,
    ) = WorkspaceDialogEntry(
        workspaceId = workspaceId,
        title = title,
        path = path,
        sessionCount = count,
        lastUsed = 0L,
    )

    // ============ connectWorkspaceEntry：连接语义（web mod29:46-58 对齐） ============

    @Test
    fun `connect reuses blank in-group unarchived session without creating`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            workspaceFlow.value = WorkspaceSnapshot(
                workspaces = listOf(Workspace("ws-1", "/w", "W", sessionIds = listOf("s-1", "s-2"))),
            )
            coEvery { chatRepository.listSessionsIncludingBlank("srv1") } returns Result.success(
                listOf(session("s-2", "/w", blank = true)),
            )
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry("ws-1", "/w"))
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToSession("s-2")),
                nav,
            )
            coVerify(exactly = 0) { manageSessionUseCase.createSession(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connect creates session with workspaceId when no reusable blank`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            workspaceFlow.value = WorkspaceSnapshot(
                workspaces = listOf(Workspace("ws-1", "/w", "W", sessionIds = listOf("s-1"))),
                archivedSessionIds = listOf("s-9"),
            )
            // 候选不含可复用（blank 但 cwd 不符 / 归档）
            coEvery { chatRepository.listSessionsIncludingBlank("srv1") } returns Result.success(
                listOf(
                    session("s-1", "/w", blank = false),
                    session("s-9", "/w", blank = true),
                ),
            )
            coEvery { manageSessionUseCase.createSession("srv1", null, "ws-1") } returns
                session("s-new", "/w")
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry("ws-1", "/w"))
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToSession("s-new")),
                nav,
            )
            // 新建会话注入仓库（列表立即可见——不等 list 刷新）
            coVerify(exactly = 1) {
                sessionRepository.setSessions("srv1", match { it.size == 1 && it[0].id == "s-new" })
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ============ 批 3（§三-3）：对话框内联预设选择的应用腿 ============

    @Test
    fun `connect carries dialog preset into create and skips select rpc`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            workspaceFlow.value = WorkspaceSnapshot(
                workspaces = listOf(Workspace("ws-1", "/w", "W", sessionIds = listOf("s-1"))),
            )
            coEvery { chatRepository.listSessionsIncludingBlank("srv1") } returns Result.success(
                listOf(session("s-1", "/w", blank = false)),
            )
            // #354：预设创建即带（SessionCreateRequest.agentPreset）——回显入槽，
            // create-then-select 竞态（事件竞丢+list 基线不回带）整体绕开。
            coEvery { manageSessionUseCase.createSession("srv1", null, "ws-1", "preset-1") } returns
                session("s-new", "/w")
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry("ws-1", "/w"), presetId = "preset-1")
            testScheduler.advanceUntilIdle()

            // create 直传预设；select RPC 不再发出
            coVerify(exactly = 1) { manageSessionUseCase.createSession("srv1", null, "ws-1", "preset-1") }
            coVerify(exactly = 0) { chatRepository.selectAgentPreset(any(), any(), any()) }
            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToSession("s-new")),
                nav,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connect applies dialog preset on reused blank session too`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            workspaceFlow.value = WorkspaceSnapshot(
                workspaces = listOf(Workspace("ws-1", "/w", "W", sessionIds = listOf("s-2"))),
            )
            coEvery { chatRepository.listSessionsIncludingBlank("srv1") } returns Result.success(
                listOf(session("s-2", "/w", blank = true)),
            )
            coEvery { chatRepository.selectAgentPreset("srv1", "s-2", "preset-1") } returns
                Result.success(true)
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry("ws-1", "/w"), presetId = "preset-1")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { chatRepository.selectAgentPreset("srv1", "s-2", "preset-1") }
            // #354：blank 复用会话先注入 store（filterByDirectory 滤 blank——
            // 不注入则事件折叠恒 no-op，agentPreset 永不落槽）
            coVerify(atLeast = 1) { sessionRepository.setSessions("srv1", any()) }
            // #354：select 成功即乐观回显（session.list 基线不回带 agentPreset，
            // 真事件竞丢后无补齐来源——合成事件走既有折叠管线）
            coVerify(exactly = 1) {
                eventDispatcher.processEvent(
                    dev.leonardo.ocbeacon.domain.model.SseEvent.SessionAgentPresetChanged("s-2", "preset-1"),
                    "srv1",
                )
            }
            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToSession("s-2")),
                nav,
            )
            coVerify(exactly = 0) { manageSessionUseCase.createSession(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `preset select failure on reuse is soft and navigation proceeds`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            workspaceFlow.value = WorkspaceSnapshot(
                workspaces = listOf(Workspace("ws-1", "/w", "W", sessionIds = listOf("s-2"))),
            )
            coEvery { chatRepository.listSessionsIncludingBlank("srv1") } returns Result.success(
                listOf(session("s-2", "/w", blank = true)),
            )
            coEvery { chatRepository.selectAgentPreset("srv1", "s-2", "preset-1") } returns
                Result.failure(IllegalStateException("locked"))
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry("ws-1", "/w"), presetId = "preset-1")
            testScheduler.advanceUntilIdle()

            // 软失败：预设被拒仍导航（会话内空态预设卡是改选通道）；
            // 失败路径不注入乐观回显事件
            coVerify(exactly = 1) { chatRepository.selectAgentPreset("srv1", "s-2", "preset-1") }
            coVerify(exactly = 0) {
                eventDispatcher.processEvent(
                    dev.leonardo.ocbeacon.domain.model.SseEvent.SessionAgentPresetChanged("s-2", "preset-1"),
                    "srv1",
                )
            }
            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToSession("s-2")),
                nav,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connect falls back to directory navigation when workspace vanished from snapshot`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // 快照已空（remove 未消费/竞态）——目录回退，不阻断入口
            workspaceFlow.value = WorkspaceSnapshot()
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry("ws-gone", "/w"))
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToDirectory("/w")),
                nav,
            )
            coVerify(exactly = 0) { manageSessionUseCase.createSession(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `stray directory entry navigates by directory without any rpc`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = createViewModel()
            val nav = mutableListOf<SessionListViewModel.NewSessionNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.newSessionNavigation.collect { nav.add(it) } }

            vm.connectWorkspaceEntry(workspaceEntry(null, "/legacy"))
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(SessionListViewModel.NewSessionNavigation.ToDirectory("/legacy")),
                nav,
            )
            coVerify(exactly = 0) { chatRepository.listSessionsIncludingBlank(any()) }
            coVerify(exactly = 0) { manageSessionUseCase.createSession(any(), any(), any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `connect fast-fails offline without rpc`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            every { sseConnectionManager.linkState(any()) } returns
                dev.leonardo.ocbeacon.service.ServerLinkState.Disconnected
            val vm = createViewModel()

            vm.connectWorkspaceEntry(workspaceEntry("ws-1", "/w"))
            testScheduler.advanceUntilIdle()

            assertEquals(SessionListViewModel.ERROR_SERVER_DISCONNECTED, vm.error.value)
            coVerify(exactly = 0) { chatRepository.listSessionsIncludingBlank(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ============ newSessionDialogEntries：快照→对话框条目映射 ============

    @Test
    fun `entries map workspace snapshot with counts and stray fallback`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            workspaceFlow.value = WorkspaceSnapshot(
                workspaces = listOf(
                    Workspace("ws-1", "/w/beacon", "Beacon", sessionIds = listOf("s-1", "s-2")),
                ),
            )
            sessionsFlow.value = listOf(
                session("s-1", "/w/beacon", updated = 2000L),
                session("s-2", "/w/beacon", updated = 1000L),
                session("s-3", "/legacy", updated = 500L),
            )
            val vm = createViewModel()
            val collected = mutableListOf<List<WorkspaceDialogEntry>>()
            val subscribe = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.newSessionDialogEntries.collect { collected.add(it) }
            }
            testScheduler.advanceUntilIdle()

            val entries = collected.last()
            assertEquals(listOf("/w/beacon", "/legacy"), entries.map { it.path })
            assertEquals("Beacon", entries[0].title)
            assertEquals(2, entries[0].sessionCount)
            assertEquals(null, entries[1].workspaceId)

            // WhileSubscribed5s stop 定时器在 resetMain 前收敛（防陈旧 Main 派发）
            subscribe.cancel()
            testScheduler.advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `entries fall back to projects for dsh server with empty snapshot`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { serverRepository.getServer("srv1") } returns ServerConfig(
                id = "srv1",
                url = "http://dsh",
                serverType = ServerType.Dsh,
            )
            coEvery { listProjectsUseCase("srv1") } returns Result.success(
                listOf(Project(id = "p-1", worktree = "/w/registry", name = "Registry")),
            )
            sessionsFlow.value = listOf(session("s-1", "/w/registry", updated = 700L))
            val vm = createViewModel()
            awaitConfigLoad()
            val collected = mutableListOf<List<WorkspaceDialogEntry>>()
            val subscribe = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.newSessionDialogEntries.collect { collected.add(it) }
            }
            testScheduler.advanceUntilIdle()

            val entries = collected.last()
            assertEquals(listOf("/w/registry"), entries.map { it.path })
            assertEquals("Registry", entries[0].title)
            assertEquals(1, entries[0].sessionCount)

            subscribe.cancel()
            testScheduler.advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `entries keep recent directories for non-dsh server`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            coEvery { serverRepository.getServer("srv1") } returns ServerConfig(
                id = "srv1",
                url = "http://v1",
                serverType = ServerType.OpenCode,
            )
            coEvery { listProjectsUseCase("srv1") } returns Result.success(
                listOf(Project(id = "p-1", worktree = "/w/registry", name = "Registry")),
            )
            sessionsFlow.value = listOf(session("s-1", "/plain/dir", updated = 700L))
            val vm = createViewModel()
            awaitConfigLoad()
            val collected = mutableListOf<List<WorkspaceDialogEntry>>()
            val subscribe = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.newSessionDialogEntries.collect { collected.add(it) }
            }
            testScheduler.advanceUntilIdle()

            // 非 DSH：维持既有最近目录行为（不切 listProjects——零回归裁决）
            val entries = collected.last()
            assertEquals(listOf("/plain/dir"), entries.map { it.path })
            assertEquals("dir", entries[0].title)

            subscribe.cancel()
            testScheduler.advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * init 配置加载跑在真实 Dispatchers.IO（backlog #38 形态）——serverType 投影
     * （_isDshServer）在 IO 线程落地。真实等待片刻使回退分支判定确定化（MockK
     * 桩即回，微秒级完成）。
     */
    private fun awaitConfigLoad() {
        Thread.sleep(100)
    }

    private fun createViewModel(): SessionListViewModel {
        val savedStateHandle = androidx.lifecycle.SavedStateHandle(
            mapOf("serverId" to "srv1")
        )
        return SessionListViewModel(
            serverAdapters = FakeServerAdapterResolver(),
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
            serverSettingsRepository = mockk(relaxed = true),
            scrollSignal = SessionScrollSignal(),
            unreadBadgeService = mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService> {
                every { mergedReadTimes(any()) } returns flowOf(emptyMap<String, Long>())
                every { allReadAt(any()) } returns flowOf(0L)
            },
            getSettingsFlowUseCase = getSettingsFlowUseCase,
            sessionTagRepository = mockk(relaxed = true),
            serverRepository = serverRepository,
            chatRepository = chatRepository,
            eventDispatcher = eventDispatcher,
            messageFtsIndex = mockk(relaxed = true),
            historySyncManager = mockk(relaxed = true),
            pendingInteractionStore = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.PendingInteractionStore> {
                io.mockk.every { pendingBySession } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, dev.leonardo.ocbeacon.data.repository.PendingInteractionEntry>())
            },
            dshConnectionRegistry = mockk(relaxed = true),
        )
    }
}
