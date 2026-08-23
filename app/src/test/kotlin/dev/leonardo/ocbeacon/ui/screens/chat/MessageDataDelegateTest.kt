package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import dev.leonardo.ocbeacon.data.local.MessageStore
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import dev.leonardo.ocbeacon.domain.usecase.ManageSessionUseCase
import dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 聚焦验证 [MessageDataDelegate.messageListState] 的 combine 管道正确消费
 * 第 10 个源（getActiveToolProgressForSession → args[9]），工具进度输出注入
 * 到 Running 态 tool part 的 output 字段。
 *
 * 回归背景（2026-08-10）：progressList 曾误用 args[8]（statusFlow 位），
 * 导致 progressOutputs 永远为空、ToolProgressOutputInjector.inject 永不生效。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDataDelegateTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val messagesFlow = MutableStateFlow<List<Message>>(emptyList())
    private val partsFlow = MutableStateFlow<Map<String, List<Part>>>(emptyMap())
    private val progressFlow = MutableStateFlow<List<ToolProgressInfo>?>(emptyList())
    private val statusFlow = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    private val sessionIdFlow = MutableStateFlow("sid-1")

    /** delegate 的协程作用域——独立于 TestScope，避免 combine 常驻协程触发
     *  runTest 的 "all coroutines must complete" 检查。测试结束显式 cancel。 */
    private var delegateScope: CoroutineScope? = null

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
    }

    @After
    fun teardown() {
        delegateScope?.cancel()
        delegateScope = null
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun buildDelegate(): MessageDataDelegate {
        val scope = CoroutineScope(testDispatcher + SupervisorJob())
        delegateScope = scope
        val chatRepository = mockk<ChatRepository>(relaxed = true).also {
            every { it.getMessagesFlow(any()) } returns messagesFlow
            every { it.getAllPartsMap() } returns partsFlow
            every { it.getActiveToolProgressForSession(any()) } returns progressFlow
            every { it.getSessionsSnapshot() } returns emptyList()
        }
        val messagePaging = mockk<MessagePaginationUseCase>(relaxed = true).also {
            every { it.observeMessages(any()) } returns messagesFlow
        }
        val sessionRepository = mockk<SessionRepository>(relaxed = true).also {
            every { it.getSessionsFlow(any()) } returns flowOf(listOf(testSession()))
        }
        val sessionStateRepository = mockk<SessionStateRepository>(relaxed = true).also {
            every { it.statusFlow } returns statusFlow
        }
        return MessageDataDelegate(
            manageSessionUseCase = mockk(relaxed = true),
            managePermissionUseCase = mockk(relaxed = true),
            chatRepository = chatRepository,
            messagePaging = messagePaging,
            messageStore = mockk<MessageStore>(relaxed = true),
            sessionStateRepository = sessionStateRepository,
            sessionRepository = sessionRepository,
            settingsRepository = mockk(relaxed = true),
            serverId = "srv",
            sessionIdFlow = sessionIdFlow,
            sessionDirectoryProvider = { null },
            scope = scope,
        )
    }

    private fun testSession() = Session(
        id = "sid-1",
        title = "test",
        directory = "/test",
        time = Session.Time(created = 1L, updated = 2L),
    )

    private fun assistantMessage(id: String = "m-1") = Message.Assistant(
        id = id,
        sessionId = "sid-1",
        time = TimeInfo(created = 100L),
        parentId = "p-0",
    )

    private fun runningToolPart(callId: String, partId: String = "p-1") = Part.Tool(
        id = partId,
        sessionId = "sid-1",
        messageId = "m-1",
        callId = callId,
        tool = "bash",
        state = ToolState.Running(),
    )

    /** messageListState 由 stateIn(WhileSubscribed5s) 支撑，需要活跃订阅者。 */
    private fun CoroutineScope.subscribe(delegate: MessageDataDelegate): Job =
        launch { delegate.messageListState.collect { } }

    @Test
    fun `progress from args 9 injects output into Running tool part`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        // 给定：1 条 assistant 消息，含 Running tool part（callId="c1"）
        messagesFlow.value = listOf(assistantMessage())
        partsFlow.value = mapOf("m-1" to listOf(runningToolPart(callId = "c1")))
        delegate.markLoaded()  // loading=false 让消息进入 visible 分支
        advanceUntilIdle()

        // 当：第 10 个源（getActiveToolProgressForSession）吐出 progress 数据
        progressFlow.value = listOf(
            ToolProgressInfo(callId = "c1", partId = "p-1", tool = "bash", status = "running", output = "running stdout")
        )
        advanceUntilIdle()

        // 那么：messageListState 中 tool part 的 Running.output 被注入
        val state = delegate.messageListState.value
        assertTrue("messages should contain the assistant message", state.messages.isNotEmpty())
        val toolPart = state.messages[0].parts.firstOrNull { it is Part.Tool } as Part.Tool
        val running = toolPart.state as ToolState.Running
        assertEquals("running stdout", running.output)

        collectJob.cancel()
    }

    @Test
    fun `no progress flow leaves Running tool output empty`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        messagesFlow.value = listOf(assistantMessage())
        partsFlow.value = mapOf("m-1" to listOf(runningToolPart(callId = "c1")))
        delegate.markLoaded()
        advanceUntilIdle()

        // 第 10 个源始终为空（默认值）
        val state = delegate.messageListState.value
        val toolPart = state.messages[0].parts.firstOrNull { it is Part.Tool } as Part.Tool
        val running = toolPart.state as ToolState.Running
        // 默认 Running() 的 output 是空串
        assertEquals("", running.output)

        collectJob.cancel()
    }

    @Test
    fun `progress with unmatched callId does not inject`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        messagesFlow.value = listOf(assistantMessage())
        partsFlow.value = mapOf("m-1" to listOf(runningToolPart(callId = "c1")))
        delegate.markLoaded()
        advanceUntilIdle()

        // progress 的 callId 与 part 不匹配
        progressFlow.value = listOf(
            ToolProgressInfo(callId = "other", partId = "p-2", tool = "bash", status = "running", output = "noise")
        )
        advanceUntilIdle()

        val state = delegate.messageListState.value
        val toolPart = state.messages[0].parts.firstOrNull { it is Part.Tool } as Part.Tool
        val running = toolPart.state as ToolState.Running
        assertEquals("", running.output)

        collectJob.cancel()
    }

    // ============ #44：sseJob 投影（messageListState 携带 rawMessages/partsByMessageId） ============

    private fun userMessage(id: String, created: Long = 1L) = Message.User(
        id = id,
        sessionId = "sid-1",
        time = TimeInfo(created = created),
    )

    private fun textPart(messageId: String, partId: String = "pt-1") = Part.Text(
        id = partId,
        sessionId = "sid-1",
        messageId = messageId,
        text = "hello",
    )

    @Test
    fun `messageListState carries rawMessages and partsByMessageId for sseJob projection`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        // 给定：user 消息 + 无 parts 的 assistant（窗口期场景）
        messagesFlow.value = listOf(
            userMessage("u-1", created = 10L),
            assistantMessage("a-1"),
        )
        delegate.markLoaded()
        advanceUntilIdle()

        // 那么：state 携带原始消息与 parts 映射（#44：唯一 combine 管道提供）
        val state = delegate.messageListState.value
        assertEquals(2, state.rawMessages.size)
        assertEquals("u-1", state.rawMessages[0].id)
        assertEquals("a-1", state.rawMessages[1].id)
        assertTrue(state.partsByMessageId.isEmpty())

        // parts 到达后映射随之更新
        partsFlow.value = mapOf("a-1" to listOf(textPart("a-1")))
        advanceUntilIdle()
        assertEquals(1, delegate.messageListState.value.partsByMessageId["a-1"]?.size)

        collectJob.cancel()
    }

    @Test
    fun `sseJob projection filters assistant without parts but keeps user and raw`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        // 给定：1 条 user + 1 条无 parts 的 assistant + 1 条有 parts 的 assistant
        messagesFlow.value = listOf(
            userMessage("u-1", created = 10L),
            assistantMessage("a-1"),
            assistantMessage("a-2"),
        )
        partsFlow.value = mapOf("a-2" to listOf(textPart("a-2")))
        delegate.markLoaded()
        advanceUntilIdle()

        // 当：启动 SSE 观察
        delegate.startObservingMessages()
        advanceUntilIdle()

        // 那么：messagesList 投影过滤无 parts assistant（u-1 + a-2）
        val projected = delegate.messagesList.value
        assertEquals(listOf("u-1", "a-2"), projected.map { it.id })
        // rawMessages 全量（含窗口期消息）
        assertEquals(3, delegate.messageListState.value.rawMessages.size)

        collectJob.cancel()
    }

    @Test
    fun `sseJob projection reveals assistant once its part arrives`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        // 窗口期：assistant 消息先到（无 parts），不应出现在投影
        messagesFlow.value = listOf(assistantMessage("a-1"))
        delegate.markLoaded()
        delegate.startObservingMessages()
        advanceUntilIdle()
        assertTrue(delegate.messagesList.value.isEmpty())

        // 当：第一个 part 到达 → assistant 自然显示（不再永久隐藏）
        partsFlow.value = mapOf("a-1" to listOf(textPart("a-1")))
        advanceUntilIdle()
        assertEquals(listOf("a-1"), delegate.messagesList.value.map { it.id })

        collectJob.cancel()
    }

    @Test
    fun `cancelSseJob freezes snapshot and restart resumes`() = runTest {
        val delegate = buildDelegate()
        val collectJob = delegateScope!!.subscribe(delegate)

        messagesFlow.value = listOf(userMessage("u-1", created = 10L))
        delegate.markLoaded()
        delegate.startObservingMessages()
        advanceUntilIdle()
        assertEquals(1, delegate.messagesList.value.size)

        // 当：取消观察（abort/revert 路径）后数据更新 → 快照冻结
        delegate.cancelSseJob()
        messagesFlow.value = listOf(
            userMessage("u-1", created = 10L),
            userMessage("u-2", created = 20L),
        )
        advanceUntilIdle()
        assertEquals("快照应冻结在取消时", 1, delegate.messagesList.value.size)

        // 当：重启观察 → 快照恢复最新
        delegate.startObservingMessages()
        advanceUntilIdle()
        assertEquals(2, delegate.messagesList.value.size)

        collectJob.cancel()
    }
}
