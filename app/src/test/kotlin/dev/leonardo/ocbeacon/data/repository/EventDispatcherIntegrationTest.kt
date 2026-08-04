package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.repository.handler.*
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * 集成测试，验证完整的 SSE 事件处理管线：
 * SSE Event → EventDispatcher.processEvent() → StateFlows 更新。
 *
 * 所有 handler 都是真实实现（未 mock），以验证实际的集成行为。
 * 这些测试通过深度链路测试补充单元级的 EventDispatcherTest。
 */
class EventDispatcherIntegrationTest {

    private lateinit var dispatcher: EventDispatcher
    // SessionStateService 的 statusFlow 使用 stateIn(scope, SharingStarted.Eagerly, …)；Eagerly
    // 传播需要 UnconfinedTestDispatcher + runCurrent()（关于 runTest 的 StandardTestDispatcher
    // 为何失效，参见 SessionStateServiceTest 的 fixture 注释）。每个测试使用全新的 scope，
    // 以免 init 中的 staleness-guard 协程在测试间泄漏。
    private lateinit var stateServiceScope: TestScope
    private lateinit var sessionStateService: SessionStateService

    @Before
    fun setup() {
        stateServiceScope = TestScope(UnconfinedTestDispatcher())
        val messageStore = MessageEventHandler()
        sessionStateService = SessionStateService(
            appScope = stateServiceScope,
            sessionRepoProvider = Provider { mockk<SessionRepository>(relaxed = true) },
        )
        dispatcher = EventDispatcher(
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
        )
    }

    @After
    fun tearDown() {
        stateServiceScope.cancel()
    }

    private fun testSession(id: String) = Session(
        id = id, title = "Test-$id", time = Session.Time(created = 1000L, updated = 2000L)
    )

    // ============ 场景 1：工具进度完整链路 ============

    @Test
    fun `tool progress full chain - started to progress to success`() = runTest {
        // 步骤 1：ToolInputStarted
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )), "svr1"
        )

        val afterStart = dispatcher.activeToolProgress.value["s1"]
        assertNotNull("Tool progress should exist after ToolInputStarted", afterStart)
        assertEquals(1, afterStart!!.size)
        assertEquals("bash", afterStart[0].tool)
        assertEquals("started", afterStart[0].status)
        assertNull("No progress yet", afterStart[0].progress)
        assertNull("No title yet", afterStart[0].title)

        // 步骤 2：ToolProgress
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "50%", title = "Running"
            )), "svr1"
        )

        val afterProgress = dispatcher.activeToolProgress.value["s1"]
        assertNotNull(afterProgress)
        assertEquals(1, afterProgress!!.size)
        assertEquals("running", afterProgress[0].status)
        assertEquals("50%", afterProgress[0].progress)
        assertEquals("Running", afterProgress[0].title)

        // 步骤 3：ToolSuccess
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", output = "done"
            )), "svr1"
        )

        val afterSuccess = dispatcher.activeToolProgress.value["s1"]
        assertTrue("Tool progress should be empty after ToolSuccess", afterSuccess?.isEmpty() ?: true)
    }

    @Test
    fun `tool progress full chain - failed also clears`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "write"
            )), "svr1"
        )

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolFailed(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", error = "Permission denied"
            )), "svr1"
        )

        assertTrue(dispatcher.activeToolProgress.value["s1"]?.isEmpty() ?: true)
    }

    @Test
    fun `multiple concurrent tool progress tracked independently`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p2",
                callId = "c2", tool = "write"
            )), "svr1"
        )

        val tools = dispatcher.activeToolProgress.value["s1"]
        assertEquals(2, tools!!.size)
        assertEquals(setOf("bash", "write"), tools.map { it.tool }.toSet())

        // 只完成 c1
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1"
            )), "svr1"
        )

        val remaining = dispatcher.activeToolProgress.value["s1"]
        assertEquals(1, remaining!!.size)
        assertEquals("write", remaining[0].tool)
        assertEquals("c2", remaining[0].callId)
    }

    // ============ 场景 2：步骤进度完整链路 ============

    @Test
    fun `step progress full chain - started then ended`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "build", model = "gpt-4"
            )), "svr1"
        )

        val afterStarted = dispatcher.stepProgress.value["s1"]
        assertNotNull(afterStarted)
        assertEquals(1, afterStarted!!.step)
        assertEquals("build", afterStarted.agent)
        assertEquals("gpt-4", afterStarted.model)

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepEnded(
                sessionId = "s1", messageId = "m1", step = 1
            )), "svr1"
        )

        assertNull("Step progress should be null after StepEnded", dispatcher.stepProgress.value["s1"])
    }

    @Test
    fun `step failed also clears step progress`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1", step = 2
            )), "svr1"
        )

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepFailed(
                sessionId = "s1", messageId = "m1", step = 2,
                error = "Build failed"
            )), "svr1"
        )

        assertNull(dispatcher.stepProgress.value["s1"])
    }

    @Test
    fun `step progress overwrites with latest step`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1", step = 1, agent = "explore"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m2", step = 2, agent = "code"
            )), "svr1"
        )

        val info = dispatcher.stepProgress.value["s1"]
        assertEquals(2, info!!.step)
        assertEquals("code", info.agent)
    }

    // ============ 场景 3：压缩完整链路 ============

    @Test
    fun `compaction full chain - started then ended`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionStarted(
                sessionId = "s1", messageId = "m1", reason = "context limit"
            )), "svr1"
        )

        val afterStarted = dispatcher.compactionState.value["s1"]
        assertNotNull(afterStarted)
        assertTrue(afterStarted!!.isActive)
        assertEquals("context limit", afterStarted.reason)

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionEnded(
                sessionId = "s1", messageId = "m1"
            )), "svr1"
        )

        assertFalse("Compaction state should not contain s1 after ended",
            dispatcher.compactionState.value.containsKey("s1"))
    }

    // ============ 场景 4：Agent/Model 切换链路 ============

    @Test
    fun `agent and model switch chain`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(
                sessionId = "s1", agent = "explore"
            )), "svr1"
        )
        assertEquals("explore", dispatcher.currentAgent.value["s1"])

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ModelSwitched(
                sessionId = "s1", providerId = "anthropic", modelId = "claude-4-sonnet"
            )), "svr1"
        )
        val model = dispatcher.currentModel.value["s1"]
        assertNotNull(model)
        assertEquals("anthropic", model!!.first)
        assertEquals("claude-4-sonnet", model.second)

        // 再次切换 —— 应当覆盖
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(
                sessionId = "s1", agent = "code"
            )), "svr1"
        )
        assertEquals("code", dispatcher.currentAgent.value["s1"])
        // Model 仍应是最后设置的值
        assertEquals("anthropic", dispatcher.currentModel.value["s1"]!!.first)
    }

    // ============ 场景 5：多会话独立性 ============

    @Test
    fun `multi-session independence - clearing one session does not affect others`() = runTest {
        // 为 s1 和 s2 设置工具进度
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s2", messageId = "m2", partId = "p2",
                callId = "c2", tool = "write"
            )), "svr1"
        )

        // 创建会话，以便 clearForServer 能找到它们
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s2")), "svr2")

        // 通过 SessionDeleted 清除 s1
        dispatcher.processEvent(SseEvent.SessionDeleted(testSession("s1")), "svr1")

        // s1 应被清除
        assertTrue(dispatcher.activeToolProgress.value["s1"]?.isEmpty() ?: true)

        // s2 应仍保留工具进度
        val s2Tools = dispatcher.activeToolProgress.value["s2"]
        assertNotNull(s2Tools)
        assertEquals(1, s2Tools!!.size)
        assertEquals("write", s2Tools[0].tool)
    }

    // ============ 场景 6：SessionDeleted 级联 ============

    @Test
    fun `SessionDeleted cascade clears ALL handler state for session`() = runTest {
        val session = testSession("s1")

        // 为会话 s1 在所有 handler 中设置状态
        dispatcher.processEvent(SseEvent.SessionCreated(session), "svr1")
        dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
        stateServiceScope.runCurrent()

        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        ), "svr1")

        dispatcher.processEvent(SseEvent.PermissionAsked(
            id = "p1", sessionId = "s1", permission = "bash"
        ), "svr1")

        dispatcher.processEvent(SseEvent.QuestionAsked(
            id = "q1", sessionId = "s1",
            questions = listOf(SseEvent.QuestionAsked.Question("H", "Q?", options = emptyList()))
        ), "svr1")

        dispatcher.processEvent(SseEvent.TodoUpdated("s1",
            listOf(SseEvent.TodoUpdated.Todo("Task", "pending", "high"))
        ), "svr1")

        // SessionNext 状态
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")),
            "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1", step = 1
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionStarted(
                sessionId = "s1", messageId = "m1", reason = "auto"
            )), "svr1"
        )

        // 删除前验证所有状态都存在
        assertFalse(dispatcher.sessionStatuses.value.isEmpty())
        assertTrue(dispatcher.messages.value.containsKey("s1"))
        assertTrue(dispatcher.permissions.value.containsKey("s1"))
        assertTrue(dispatcher.questions.value.containsKey("s1"))
        assertTrue(dispatcher.todos.value.containsKey("s1"))
        assertTrue(dispatcher.currentAgent.value.containsKey("s1"))
        assertTrue(dispatcher.activeToolProgress.value.containsKey("s1"))
        assertTrue(dispatcher.stepProgress.value.containsKey("s1"))
        assertTrue(dispatcher.compactionState.value.containsKey("s1"))

        // 发送 SessionDeleted
        dispatcher.processEvent(SseEvent.SessionDeleted(session), "svr1")

        // 断言 s1 的所有状态都被清除
        assertFalse("Session statuses should not contain s1", dispatcher.sessionStatuses.value.containsKey("s1"))
        assertFalse("Messages should not contain s1", dispatcher.messages.value.containsKey("s1"))
        assertFalse("Permissions should not contain s1", dispatcher.permissions.value.containsKey("s1"))
        assertFalse("Questions should not contain s1", dispatcher.questions.value.containsKey("s1"))
        assertFalse("Todos should not contain s1", dispatcher.todos.value.containsKey("s1"))
        assertNull("Agent should be cleared for s1", dispatcher.currentAgent.value["s1"])
        assertTrue("Tool progress should be cleared for s1", dispatcher.activeToolProgress.value["s1"]?.isEmpty() ?: true)
        assertNull("Step progress should be cleared for s1", dispatcher.stepProgress.value["s1"])
        assertFalse("Compaction should be cleared for s1", dispatcher.compactionState.value.containsKey("s1"))
        assertTrue("Session removed from sessions list", dispatcher.sessions.value.none { it.id == "s1" })
    }

    @Test
    fun `SessionDeleted cascade does not affect other sessions`() = runTest {
        val s1 = testSession("s1")
        val s2 = testSession("s2")

        dispatcher.processEvent(SseEvent.SessionCreated(s1), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(s2), "svr1")

        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        ), "svr1")
        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m2", sessionId = "s2", time = TimeInfo(1000L))
        ), "svr1")

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")),
            "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "explore")),
            "svr1"
        )

        // 删除 s1
        dispatcher.processEvent(SseEvent.SessionDeleted(s1), "svr1")

        // s1 已清除
        assertFalse(dispatcher.messages.value.containsKey("s1"))
        assertNull(dispatcher.currentAgent.value["s1"])

        // s2 不受影响
        assertTrue(dispatcher.messages.value.containsKey("s2"))
        assertEquals("explore", dispatcher.currentAgent.value["s2"])
        assertEquals(1, dispatcher.sessions.value.size)
        assertEquals("s2", dispatcher.sessions.value[0].id)
    }

    // ============ 场景 7：CommandExecuted 将消息标记为完成 ============

    @Test
    fun `CommandExecuted marks incomplete assistant messages as completed`() = runTest {
        // 设置一条未完成的 assistant 消息（time.completed = null）
        val assistant = Message.Assistant(
            id = "m1", sessionId = "s1", time = TimeInfo(created = 1000L, completed = null),
            parentId = "p0"
        )
        dispatcher.processEvent(SseEvent.MessageUpdated(assistant), "svr1")

        // 验证消息正在流式输出（无 completed 时间）
        val before = dispatcher.messages.value["s1"]!!.first() as Message.Assistant
        assertNull("Before CommandExecuted, completed should be null", before.time.completed)

        // 发送 CommandExecuted
        dispatcher.processEvent(
            SseEvent.CommandExecuted(name = "bash", sessionId = "s1"), "svr1"
        )

        // 验证消息现已被标记为完成
        val after = dispatcher.messages.value["s1"]!!.first() as Message.Assistant
        assertNotNull("After CommandExecuted, completed should be set", after.time.completed)
    }

    @Test
    fun `CommandExecuted does not modify already-completed messages`() = runTest {
        val completedTime = 5000L
        val assistant = Message.Assistant(
            id = "m1", sessionId = "s1", time = TimeInfo(created = 1000L, completed = completedTime),
            parentId = "p0"
        )
        dispatcher.processEvent(SseEvent.MessageUpdated(assistant), "svr1")

        dispatcher.processEvent(
            SseEvent.CommandExecuted(name = "bash", sessionId = "s1"), "svr1"
        )

        val after = dispatcher.messages.value["s1"]!!.first() as Message.Assistant
        assertEquals("Already-completed messages should not change", completedTime, after.time.completed)
    }

    @Test
    fun `CommandExecuted does not modify user messages`() = runTest {
        val user = Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        dispatcher.processEvent(SseEvent.MessageUpdated(user), "svr1")

        dispatcher.processEvent(
            SseEvent.CommandExecuted(name = "bash", sessionId = "s1"), "svr1"
        )

        val after = dispatcher.messages.value["s1"]!!.first() as Message.User
        assertNull("User messages should not get completed time", after.time.completed)
    }

    // ============ 场景 8：clearForServer ============

    @Test
    fun `clearForServer clears all state for target server sessions only`() = runTest {
        // 为 svr1 设置 s1、s2
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s2")), "svr1")

        // 为 svr2 设置 s3
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s3")), "svr2")

        // 为每个会话添加 messages、permissions、SessionNext 状态
        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        ), "svr1")
        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m2", sessionId = "s2", time = TimeInfo(1000L))
        ), "svr1")
        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m3", sessionId = "s3", time = TimeInfo(1000L))
        ), "svr2")

        dispatcher.processEvent(SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash"), "svr1")
        dispatcher.processEvent(SseEvent.PermissionAsked(id = "p2", sessionId = "s2", permission = "read"), "svr1")
        dispatcher.processEvent(SseEvent.PermissionAsked(id = "p3", sessionId = "s3", permission = "write"), "svr2")

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s2", agent = "explore")), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s3", agent = "build")), "svr2"
        )

        // 清除 svr1
        dispatcher.clearForServer("svr1")

        // svr1 的会话已清除
        assertFalse(dispatcher.messages.value.containsKey("s1"))
        assertFalse(dispatcher.messages.value.containsKey("s2"))
        assertFalse(dispatcher.permissions.value.containsKey("s1"))
        assertFalse(dispatcher.permissions.value.containsKey("s2"))
        assertNull(dispatcher.currentAgent.value["s1"])
        assertNull(dispatcher.currentAgent.value["s2"])
        assertTrue(dispatcher.sessions.value.none { it.id == "s1" || it.id == "s2" })

        // svr2 的会话不受影响
        assertTrue(dispatcher.messages.value.containsKey("s3"))
        assertTrue(dispatcher.permissions.value.containsKey("s3"))
        assertEquals("build", dispatcher.currentAgent.value["s3"])
        assertEquals(1, dispatcher.sessions.value.size)
        assertEquals("s3", dispatcher.sessions.value[0].id)
    }

    @Test
    fun `clearForServer clears SessionNextHandler state for server sessions`() = runTest {
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s2")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s3")), "svr2")

        // 设置各种 SessionNext 状态
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1", callId = "c1", tool = "bash"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s2", messageId = "m1", step = 1, agent = "build"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionStarted(
                sessionId = "s1", messageId = "m1", reason = "auto"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ModelSwitched(
                sessionId = "s2", providerId = "openai", modelId = "gpt-4"
            )), "svr1"
        )
        // svr2 的 s3 状态
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s3", messageId = "m2", partId = "p2", callId = "c3", tool = "read"
            )), "svr2"
        )

        dispatcher.clearForServer("svr1")

        // svr1 的所有会话状态已清除
        assertTrue(dispatcher.activeToolProgress.value["s1"]?.isEmpty() ?: true)
        assertNull(dispatcher.stepProgress.value["s2"])
        assertFalse(dispatcher.compactionState.value.containsKey("s1"))
        assertNull(dispatcher.currentModel.value["s2"])

        // s3 不受影响
        assertNotNull(dispatcher.activeToolProgress.value["s3"])
        assertEquals("read", dispatcher.activeToolProgress.value["s3"]!![0].tool)
    }

    // ============ 场景 9：clearAll ============

    @Test
    fun `clearAll resets every StateFlow`() = runTest {
        // 在多个会话和服务器间设置大量状态
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s2")), "svr2")
        dispatcher.processEvent(SseEvent.VcsBranchUpdated("main"), "svr1")
        dispatcher.processEvent(SseEvent.ProjectUpdated(Project(id = "p1", name = "Test")), "svr1")

        dispatcher.processEvent(SseEvent.MessageUpdated(
            Message.User(id = "m1", sessionId = "s1", time = TimeInfo(1000L))
        ), "svr1")
        dispatcher.processEvent(SseEvent.MessagePartUpdated(
            Part.Text(id = "p1", sessionId = "s1", messageId = "m1", text = "hello")
        ), "svr1")

        dispatcher.processEvent(SseEvent.PermissionAsked(id = "p1", sessionId = "s1", permission = "bash"), "svr1")
        dispatcher.processEvent(SseEvent.QuestionAsked(
            id = "q1", sessionId = "s1",
            questions = listOf(SseEvent.QuestionAsked.Question("H", "Q?", options = emptyList()))
        ), "svr1")
        dispatcher.processEvent(SseEvent.TodoUpdated("s1",
            listOf(SseEvent.TodoUpdated.Todo("Task", "pending", "high"))
        ), "svr1")
        dispatcher.processEvent(SseEvent.SessionDiff("s1", listOf(FileDiff(file = "a.kt"))), "svr1")

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(sessionId = "s1", agent = "code")), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ModelSwitched(
                sessionId = "s2", providerId = "anthropic", modelId = "claude-4-sonnet"
            )), "svr2"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1", callId = "c1", tool = "bash"
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1", step = 1
            )), "svr1"
        )
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionStarted(
                sessionId = "s2", messageId = "m1", reason = "auto"
            )), "svr2"
        )

        // 清除全部
        dispatcher.clearAll()

        // 验证所有 StateFlows 都为空
        assertTrue(dispatcher.sessions.value.isEmpty())
        assertTrue(dispatcher.sessionStatuses.value.isEmpty())
        assertTrue(dispatcher.serverSessions.value.isEmpty())
        assertTrue(dispatcher.messages.value.isEmpty())
        assertTrue(dispatcher.parts.value.isEmpty())
        assertTrue(dispatcher.permissions.value.isEmpty())
        assertTrue(dispatcher.questions.value.isEmpty())
        assertTrue(dispatcher.todos.value.isEmpty())
        assertTrue(dispatcher.sessionDiffs.value.isEmpty())
        assertNull(dispatcher.vcsBranch.value)
        assertNull(dispatcher.projectInfo.value)

        assertTrue(dispatcher.currentAgent.value.isEmpty())
        assertTrue(dispatcher.currentModel.value.isEmpty())
        assertTrue(dispatcher.activeToolProgress.value.isEmpty())
        assertTrue(dispatcher.stepProgress.value.isEmpty())
        assertTrue(dispatcher.compactionState.value.isEmpty())
        assertTrue(dispatcher.shellState.value.isEmpty())
        assertTrue(dispatcher.retryState.value.isEmpty())
        assertTrue(dispatcher.gapDetected.value.isEmpty())
    }

    // ============ 场景 10：混合事件序列（真实流程） ============

    @Test
    fun `mixed event sequence - realistic agent execution flow`() = runTest {
        // 1. AgentSwitched
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.AgentSwitched(
                sessionId = "s1", agent = "code"
            )), "svr1"
        )
        assertEquals("code", dispatcher.currentAgent.value["s1"])

        // 2. StepStarted
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepStarted(
                sessionId = "s1", messageId = "m1",
                step = 1, agent = "code", model = "claude-4-sonnet"
            )), "svr1"
        )
        val step1 = dispatcher.stepProgress.value["s1"]!!
        assertEquals(1, step1.step)
        assertEquals("code", step1.agent)

        // 3. ToolInputStarted
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", tool = "bash"
            )), "svr1"
        )
        assertEquals(1, dispatcher.activeToolProgress.value["s1"]!!.size)
        assertEquals("started", dispatcher.activeToolProgress.value["s1"]!![0].status)

        // 4. ToolProgress
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolProgress(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", progress = "75%", title = "Installing deps"
            )), "svr1"
        )
        assertEquals("running", dispatcher.activeToolProgress.value["s1"]!![0].status)
        assertEquals("75%", dispatcher.activeToolProgress.value["s1"]!![0].progress)

        // 5. ToolSuccess
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolSuccess(
                sessionId = "s1", messageId = "m1", partId = "p1",
                callId = "c1", output = "installed"
            )), "svr1"
        )
        assertTrue(dispatcher.activeToolProgress.value["s1"]!!.isEmpty())
        // 步骤仍活跃
        assertNotNull(dispatcher.stepProgress.value["s1"])

        // 6. StepEnded
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.StepEnded(
                sessionId = "s1", messageId = "m1", step = 1
            )), "svr1"
        )
        assertNull(dispatcher.stepProgress.value["s1"])

        // 7. CompactionStarted
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionStarted(
                sessionId = "s1", messageId = "m1", reason = "context limit reached"
            )), "svr1"
        )
        assertTrue(dispatcher.compactionState.value["s1"]!!.isActive)
        assertEquals("context limit reached", dispatcher.compactionState.value["s1"]!!.reason)

        // 8. CompactionEnded
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.CompactionEnded(
                sessionId = "s1", messageId = "m1"
            )), "svr1"
        )
        assertFalse(dispatcher.compactionState.value.containsKey("s1"))

        // 整个流程结束后 Agent 应仍保留
        assertEquals("code", dispatcher.currentAgent.value["s1"])
    }

    // ============ 附加：Shell 状态链路 ============

    @Test
    fun `shell state chain - started then ended`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ShellStarted(
                sessionId = "s1", messageId = "m1", partId = "p1",
                command = "gradle build"
            )), "svr1"
        )

        val shell = dispatcher.shellState.value["s1"]
        assertNotNull(shell)
        assertEquals("gradle build", shell!!.command)

        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.ShellEnded(
                sessionId = "s1", messageId = "m1", partId = "p1", exitCode = 0
            )), "svr1"
        )

        assertFalse(dispatcher.shellState.value.containsKey("s1"))
    }

    // ============ 附加：重试状态 ============

    @Test
    fun `retry state tracked`() = runTest {
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.Retried(
                sessionId = "s1", attempt = 2, error = "Rate limited"
            )), "svr1"
        )

        assertEquals(2, dispatcher.retryState.value["s1"])

        // 用新的尝试覆盖
        dispatcher.processEvent(
            SseEvent.SessionNext(SessionNextEvent.Retried(
                sessionId = "s1", attempt = 3
            )), "svr1"
        )

        assertEquals(3, dispatcher.retryState.value["s1"])
    }

    // ============ 附加：无会话时 clearForServer 移除服务器条目 ============

    @Test
    fun `clearForServer with multiple servers preserves unaffected server mapping`() = runTest {
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s1")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s2")), "svr1")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s3")), "svr2")
        dispatcher.processEvent(SseEvent.SessionCreated(testSession("s4")), "svr2")

        dispatcher.clearForServer("svr1")

        assertFalse(dispatcher.serverSessions.value.containsKey("svr1"))
        assertTrue(dispatcher.serverSessions.value.containsKey("svr2"))
        assertEquals(setOf("s3", "s4"), dispatcher.serverSessions.value["svr2"])
    }

    // ============ 场景 11：SSE 双写到 SessionStateService ============

    @Test
    fun `SSE SessionStatus dual-writes to SessionStateService`() = runTest {
        dispatcher.processEvent(SseEvent.SessionStatus(sessionId = "s1", status = SessionStatus.Busy), "svr1")
        stateServiceScope.runCurrent()
        assertEquals(SessionStatus.Busy, sessionStateService.statusFlow.value["s1"])
    }
}
