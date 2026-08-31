package dev.leonardo.ocbeacon.ui.screens.sessions

import android.util.Log
import app.cash.turbine.test
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListShellStateTest {

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
    private val sessionTagRepository: SessionTagRepository = mockk(relaxed = true)
    private val chatRepository: ChatRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        // contentState 输入流：用 MutableStateFlow（发射初始值）而非 emptyFlow（永不发射），
        // 否则 combine 因有空源永不产生值——测试将永远 pass 但无护栏意义。
        every { sessionRepository.getSessionsFlow(any()) } returns MutableStateFlow(emptyList<Session>())
        every { sessionRepository.getServerSessionsFlow() } returns MutableStateFlow(emptyMap<String, Set<String>>())
        every { sessionRepository.getLastUserMessageTimeFlow() } returns MutableStateFlow(emptyMap<String, Long>())
        every { sessionRepository.getLastCompletedReplyTimeFlow() } returns MutableStateFlow(emptyMap<String, Long>())
        every { sessionStateRepository.statusFlow } returns MutableStateFlow(emptyMap<String, SessionStatus>())
        every { chatRepository.getAllQuestionsFlow() } returns MutableStateFlow(emptyMap<String, List<SseEvent.QuestionAsked>>())
        // C5 拆分：标签流经 SessionTagRepository；未读流（sessionReadTimes/allReadAt）
        // 由下方 unreadBadgeService mock 提供（mergedReadTimes/allReadAt stub）
        every { sessionTagRepository.sessionTagAssignments(any()) } returns MutableStateFlow(emptyMap<String, List<String>>())
        every { sessionTagRepository.sessionTags(any()) } returns MutableStateFlow(emptyList<Tag>())
        // loadSessions/refreshSessions 走成功路径，不写 _error（保持 shellState 初始 error=null）
        coEvery { listProjectsUseCase(any()) } returns Result.success(emptyList())
        coEvery { listSessionsUseCase(any(), any(), any(), any(), any()) } returns emptyList()
        // 让 viewModelScope 协程可执行（UnconfinedTestDispatcher：launch 同步执行）
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `shellState 暴露默认外壳字段`() = runTest {
        val vm = createViewModel()
        vm.shellState.test {
            val initial = awaitItem()
            // loadSessions 成功路径不写 _error；_isRefreshing 始终为 false
            assertEquals(false, initial.isRefreshing)
            assertNull(initial.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `contentState 暴露默认内容字段`() = runTest {
        val vm = createViewModel()
        vm.contentState.test {
            val initial = awaitItem()
            // 空数据下 buildContentState 产出空列表、无选中、无搜索
            assertEquals(emptyList<Any>(), initial.treeNodes)
            assertEquals(emptySet<String>(), initial.selectedIds)
            assertEquals(null, initial.searchQuery)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * #23 核心收益护栏：外壳状态翻转（_isRefreshing/_error）不应触发 contentState 重算。
     *
     * contentState 输入流 = combine(dataFlow, uiFlow)，源为 sessions/statuses/expandedPaths 等，
     * 完全不含 _isLoading/_isRefreshing/_error。refreshSessions 写后三者（shellState 源），
     * 因此 contentState 不应发射新帧——若泄漏则说明切片边界被破坏。
     *
     * 驱动路径：refreshSessions 成功执行写 _isRefreshing=true→false（全程在 shellState 输入流）。
     * UnconfinedTestDispatcher 下 launch 同步执行，refreshSessions 返回时 shell 翻转已完成。
     */
    @Test
    fun `shellState 翻转不触发 contentState 重发`() = runTest {
        val vm = createViewModel()
        vm.contentState.test {
            awaitItem() // 消费首帧（stateIn 当前值，上游 combine 已稳定）
            // 驱动 shell 翻转：refreshSessions 写 _isRefreshing（shellState 源），不触碰 contentState 输入流
            vm.refreshSessions()
            // 核心断言：shell 字段翻转不应触发 content 重算
            expectNoEvents()
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
            dshSettingsRepository = mockk(relaxed = true),
            scrollSignal = SessionScrollSignal(),
            getSettingsFlowUseCase = mockk(relaxed = true),
            sessionTagRepository = sessionTagRepository,
            serverRepository = mockk(relaxed = true),
            unreadBadgeService = io.mockk.mockk<dev.leonardo.ocbeacon.data.repository.UnreadBadgeService> {
                io.mockk.every { mergedReadTimes(any()) } returns kotlinx.coroutines.flow.flowOf(emptyMap<String, Long>())
                io.mockk.every { allReadAt(any()) } returns kotlinx.coroutines.flow.flowOf(0L)
            },
            chatRepository = chatRepository,
            pendingMessageRepository = mockk(relaxed = true),
            pendingMessageDrainController = mockk(relaxed = true),
            messageFtsIndex = mockk(relaxed = true),
            historySyncManager = mockk(relaxed = true),
        )
    }
}
