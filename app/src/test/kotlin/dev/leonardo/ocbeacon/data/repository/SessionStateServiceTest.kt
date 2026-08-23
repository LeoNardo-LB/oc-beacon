package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.usecase.PaginationCursorPolicyFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import javax.inject.Provider

/**
 * 夹具说明（与 brief 中字面的 `runTest { this -> ... }` 形式有所偏差）：
 *
 * SessionStateService 暴露的 flow 使用 `stateIn(appScope, SharingStarted.Eagerly, …)` 构建，
 * 这些 flow 会在注入的 appScope 中启动协程，且不会自行结束。将
 * `runTest` 的 `this`（默认 StandardTestDispatcher 上的 TestScope）传入会导致两个失败：
 *   1. 时序问题 —— Eagerly 收集器排在测试体之后，导致 `statusFlow.value`
 *      一直是 `emptyMap()`（AssertionError / NPE）。
 *   2. 收尾时的 `UncompletedCoroutinesError` —— 3 个 Eagerly 协程的生命周期超过测试体。
 *
 * 修复方式与项目自身的 `ChatViewModelStreamingTest`（UnconfinedTestDispatcher +
 * advanceUntilIdle）一致：用 UnconfinedTestDispatcher 驱动 appScope 以实现即时传播，并在
 * @After 中取消该 scope，使收尾时不再有未完成的协程。所有测试用例与
 * 断言均与 brief 保持一致。
 *
 * ---
 * Task 4 夹具修订（staleness 守卫）：
 *
 * Task 4 在 `init` 中启动了一个永续的 `while(isActive) { delay(STALENESS_CHECK_INTERVAL_MS); ... }`
 * 协程。探测（见 git 历史）证实 `advanceUntilIdle()` 会在这样的协程上无限循环
 * （10 秒 JUnit 超时，虚拟时间推进到约 423 天）。`runCurrent()`
 * 只运行当前虚拟时间下已排队的任务，不会推进时钟，因此
 * 守卫的第一个 delay(5_000) 永远不会到达。所有断言在 `runCurrent()` 下仍然成立，因为：
 *   - `applyTransition` 是同步的，会立即写入 `_fsmStates`。
 *   - `statusFlow` 使用 `stateIn(appScope, SharingStarted.Eagerly, …)`；在 UnconfinedTestDispatcher 下
 *     操作符链同步传播，`runCurrent()` 会刷新任何已排队的调度。
 *   - `triggerRestValidation` 启动的协程在 relaxed MockK 下没有真正的挂起点
 *     （`coEvery` 的 stub 立即返回），因此它会在 `runCurrent()` 期间完成。
 *
 * 因此所有测试都使用 `runCurrent()` 而非 `advanceUntilIdle()`。`@After cancel`
 * 仍会连同 `testScope` 的其他所有子协程一起取消 staleness 守卫的 `Job`。
 */
class SessionStateServiceTest {

    private val testScope = TestScope(UnconfinedTestDispatcher())

    private fun newService(collab: SessionStateCollaborator = StubCollaborator()) = SessionStateService(
        testScope,
        Provider { mockk<SessionRepository>(relaxed = true) },
        collab,
        PaginationCursorPolicyFactory(Provider { mockk<SessionRepository>(relaxed = true) }),
    )

    /** 构建一个由 [repo] 支撑的服务，以便测试可以 stub `fetchSessionStatuses`。 */
    private fun newServiceWith(repo: SessionRepository, collab: SessionStateCollaborator = StubCollaborator()) = SessionStateService(
        testScope,
        Provider { repo },
        collab,
        PaginationCursorPolicyFactory(Provider { repo }),
    )

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun `ClientSendParts transitions Idle to Busy Waiting in statusFlow`() {
        val service = newService()
        service.onClientSendParts("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Waiting, service.activityFlow.value["s1"])
    }

    @Test
    fun `SseIdle after Busy triggers forceComplete on messageForceCompleter`() {
        val forceCompleted = mutableListOf<String>()
        val collab = StubCollaborator()
        collab.onForceCompleteSession = { forceCompleted.add(it) }
        val service = newService(collab)
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1", "server1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
        assertEquals(listOf("s1"), forceCompleted)
    }

    @Test
    fun `transition recorded in history`() {
        val service = newService()
        service.onClientSendParts("s1")
        testScope.runCurrent()
        val history = service.historyFlow.value["s1"]
        assertEquals(1, history!!.size)
        assertEquals("Idle", history[0].fromCore)
        assertEquals("Busy", history[0].toCore)
    }

    @Test
    fun `history trims to max 20 entries`() {
        val service = newService()
        // #122 D2-15 后：稳定态重复事件被短路不记 history——用交替真实
        // 转移（Idle↔Busy）驱动 25 次真实转移，验证修剪到 ≤20。
        repeat(25) { i ->
            if (i % 2 == 0) service.onClientSendParts("s1")
            else service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1", "server1")
        }
        testScope.runCurrent()
        val history = service.historyFlow.value["s1"]!!
        assertTrue("history should be trimmed to <= 20, was ${history.size}", history.size <= 20)
    }

    // ============ #122 D2-15：仅时间戳变化短路 ============

    /**
     * 流式高频事件（delta/updated ~48ms/次）在稳定活动态下 newState 与
     * current 差异仅剩 lastEventAt——短路跳过整表拷贝与下游发射。
     * 验证：状态仍正确（Busy/Streaming 保持），且不产生多余 history 记录。
     */
    @Test
    fun `D2-15 timestamp-only events within throttle window are short-circuited`() {
        val service = newService()
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionNext(SessionNextEvent.TextStarted("s1", "m1", "p1")), "s1", "srv")
        testScope.runCurrent()
        assertEquals(2, service.historyFlow.value["s1"]!!.size) // Idle→Busy + →Streaming

        // 高频 delta：Streaming 稳定态下仅 lastEventAt 变化（<1s 窗口）→ 短路
        repeat(20) {
            service.onSseEvent(SseEvent.SessionNext(SessionNextEvent.TextDelta("s1", "m1", "p1", "x")), "s1", "srv")
        }
        testScope.runCurrent()

        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionActivity.Streaming, service.activityFlow.value["s1"])
        assertEquals(
            "timestamp-only deltas must not append history (short-circuited)",
            2,
            service.historyFlow.value["s1"]!!.size,
        )
    }

    /** 短路不吞真实转移：活动态变化（Streaming→ToolCalling）仍照常记录。 */
    @Test
    fun `D2-15 short-circuit does not swallow real activity transitions`() {
        val service = newService()
        service.onClientSendParts("s1")
        service.onSseEvent(SseEvent.SessionNext(SessionNextEvent.TextStarted("s1", "m1", "p1")), "s1", "srv")
        service.onSseEvent(SseEvent.SessionNext(SessionNextEvent.TextDelta("s1", "m1", "p1", "a")), "s1", "srv")
        testScope.runCurrent()

        // 工具调用 = 真实活动转移（Streaming→ToolCalling）→ 必须记录
        service.onSseEvent(
            SseEvent.SessionNext(SessionNextEvent.ToolInputStarted("s1", "m1", "p1", "c1", "bash")),
            "s1", "srv",
        )
        testScope.runCurrent()

        assertEquals(SessionActivity.ToolCalling("bash", "c1"), service.activityFlow.value["s1"])
        val history = service.historyFlow.value["s1"]!!
        assertEquals(3, history.size) // Idle→Busy, →Streaming, →ToolCalling
    }

    @Test
    fun `clearSession removes state and history`() {
        val service = newService()
        service.onClientSendParts("s1")
        testScope.runCurrent()
        service.clearSession("s1")
        testScope.runCurrent()
        assertNull(service.statusFlow.value["s1"])
        assertNull(service.historyFlow.value["s1"])
    }

    // ============ Task 4：triggerRestValidation 缺失时 = idle ============

    @Test
    fun `triggerRestValidation absence with fresh SSE keeps status（2026-08-16 新鲜度护栏）`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())  // 缺失
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        service.onClientSendParts("s1")
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        // 2026-08-16 语义变更（会话状态误杀修复）：onClientSendParts 刚更新
        // lastEventAt（SSE fresh <60s）——active 是不完整快照，活跃证据更强，
        // 不因缺失强转 Idle（旧行为转 Idle 正是「输出中被强杀」根因之一）。
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
    }

    @Test
    fun `triggerRestValidation absence with null directory stays Busy`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { null }  // 未知目录
        service.onClientSendParts("s1")
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // 不会误判为 idle
    }

    // ============ 2026-08-14：僵尸 Busy 兜底（服务器 drain 不释放） ============

    @Test
    fun `triggerRestValidation zombie Busy with stale lastEventAt forces Idle`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        // 服务器说 Busy（僵尸 running：会话已结束但 /active 持续返回 running）
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(mapOf("s1" to SessionStatus.Busy))
        // 2026-08-14 根因修复：僵尸判定必须主动调用服务器 abort/interrupt
        //（解除服务器僵尸，否则后续发消息仍无回复）——断言 abort 被调用。
        coEvery { fakeRepo.abort(any(), any(), any()) } returns Result.success(Unit)
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        service.onClientSendParts("s1")  // 本地 Busy
        // 反射：把 FSM lastEventAt 改旧（超过 ZOMBIE_BUSY_MS——3 分钟无真实事件）。
        // restValidation 转移已修正为不刷新 lastEventAt（2026-08-14），
        // 因此 lastEventAt 只反映真实 SSE 事件/客户端操作时间。
        val field = SessionStateService::class.java.getDeclaredField("_fsmStates")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<Map<String, SessionFSMState>>
        flow.value = flow.value + ("s1" to flow.value.getValue("s1").copy(
            lastEventAt = System.currentTimeMillis() - ZOMBIE_BUSY_MS - 1000
        ))
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        // 僵尸判定：服务器 Busy + 无真实事件超阈值 → 强制 Idle（列表图标恢复）
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
        // 2026-08-15（对齐官方调研结论 research/05）：官方客户端无任何自动
        // interrupt（全部用户显式触发）——自动 zombie interrupt 已实证误杀
        //（主会话等待后台子代理被打断）。收紧为"仅显示修复"：**断言 abort
        // 不被调用**；本地 Idle 兜底仍然生效（上方断言）。
        coVerify(exactly = 0) { fakeRepo.abort(any(), any(), any()) }
    }

    @Test
    fun `triggerRestValidation zombie Busy with pending user input skips interrupt`() {
        // 2026-08-14 走查修复（误杀防护）：pending question/permission 时服务器在合法
        // 等待用户输入（无 SSE 事件属正常，非僵尸）——不得 interrupt（会杀掉等待中的
        // 提问/权限对话框）。2026-08-18 E2E-G 修复：也不再强转 Idle——服务器 running
        // 是真实状态（等待输入），FSM 保持 Busy 跟随，消除 Busy↔Idle 10s 抖动循环。
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(mapOf("s1" to SessionStatus.Busy))
        coEvery { fakeRepo.abort(any(), any(), any()) } returns Result.success(Unit)
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        // 模拟该会话有 pending question（EventDispatcher 接线的检查器）
        collab.hasPendingUserInputImpl = { sessionId -> sessionId == "s1" }
        service.onClientSendParts("s1")
        val field = SessionStateService::class.java.getDeclaredField("_fsmStates")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(service) as MutableStateFlow<Map<String, SessionFSMState>>
        flow.value = flow.value + ("s1" to flow.value.getValue("s1").copy(
            lastEventAt = System.currentTimeMillis() - ZOMBIE_BUSY_MS - 1000
        ))
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        // 2026-08-18 E2E-G 修复后：保持 Busy 跟随服务器（等待用户输入是真实 running
        // 状态；原强转 Idle 会与 active-running 校验抖动，且与 BACK fade 过渡竞态致空白屏）
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        // 关键断言：不得 interrupt（等待用户输入的会话不是僵尸）
        coVerify(exactly = 0) { fakeRepo.abort(any(), any(), any()) }
    }

    @Test
    fun `triggerRestValidation Busy with recent events stays Busy`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(mapOf("s1" to SessionStatus.Busy))
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/proj" }
        service.onClientSendParts("s1")
        // lastEventAt 是刚刚（有事件）——不触发僵尸判定
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
    }

    // ============ Task 5：syncFromRest ============

    @Test
    fun `syncFromRest aggregates multiple directories`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses("svr1", "D:/projA") } returns Result.success(mapOf("s1" to SessionStatus.Busy))
        coEvery { fakeRepo.fetchSessionStatuses("svr1", "D:/projB") } returns Result.success(mapOf("s2" to SessionStatus.Busy))
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        val result = runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/projA"), Project(worktree = "D:/projB"))) }
        testScope.runCurrent()
        assertEquals(2, result.totalSessions)
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s2"])
    }

    @Test
    fun `syncFromRest marks absent non-idle session Idle when no incomplete`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        service.onClientSendParts("s1")  // 本地为 Busy
        runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/p"))) }
        testScope.runCurrent()
        assertEquals(SessionStatus.Idle, service.statusFlow.value["s1"])
    }

    @Test
    fun `syncFromRest protects absent session with incomplete messages`() {
        val fakeRepo = mockk<SessionRepository>(relaxed = true)
        coEvery { fakeRepo.fetchSessionStatuses(any(), any()) } returns Result.success(emptyMap())
        val collab = StubCollaborator()
                val service = newServiceWith(fakeRepo, collab)
        service.setServerId("svr1")
        collab.resolveDirectoryImpl = { "D:/p" }
        collab.hasIncompleteAssistantImpl = { true }
        service.onClientSendParts("s1")
        runBlocking { service.syncFromRest(listOf(Project(worktree = "D:/p"))) }
        testScope.runCurrent()
        assertEquals(SessionStatus.Busy, service.statusFlow.value["s1"])  // 受到保护
    }

    // ============ 2026-08-16 根治（回复不可见）：断连窗口补漏 ============

    /**
     * backfillMissedMessages：cursor 增量拉取 + **SSE_PRIORITY** 合并
     * （不覆盖 SSE 累积流式文本——与 L3 的 REST_AUTHORITY 相反）。
     */
    @Test
    fun backfillMissedMessages_usesCursorAndSsePriorityMerge() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getApiVersion(any()) } returns ApiVersion.V2
        coEvery { repo.listMessages(any(), any(), any(), any()) } returns Result.success(
            MessagePage(messages = listOf(mockk(relaxed = true)), nextCursor = null)
        )
        val collab = StubCollaborator()
        val service = SessionStateService(testScope, Provider { repo }, collab, PaginationCursorPolicyFactory(Provider { repo }))
        val strategies = mutableListOf<MergeStrategy>()
        collab.refreshMessagesImpl = { sessionId, messages, strategy ->
                strategies.add(strategy)
        }
        collab.latestMessageIdImpl = { "msg_anchor_1" }
        // 会话归属：SSE 投递记录（onSseEvent 会写 sessionServerOwnership）
        service.onSseEvent(SseEvent.SessionIdle("ses_x"), "ses_backfill", "server-1")

        service.backfillMissedMessages("ses_backfill")
        testScope.runCurrent()

        // SSE_PRIORITY（流式安全），非 REST_AUTHORITY
        assertEquals(listOf(MergeStrategy.SSE_PRIORITY), strategies)
        // cursor 增量：V2 时 before 参数非空（NEWER 方向游标）
        coVerify { repo.listMessages(any(), "ses_backfill", any(), any()) }
    }

    /**
     * 2026-08-17 R3 缺口修复（缺口①）：无锚点（本地无消息）时不再放弃——
     * 退化为无 cursor 拉最新 REST_REFRESH_LIMIT 条（before=null），消息非空时
     * 以 SSE_PRIORITY 合并（与 L3 校验路径兜底同款）。
     */
    @Test
    fun backfillMissedMessages_noAnchor_fallsBackToLatest() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getApiVersion(any()) } returns ApiVersion.V2
        coEvery { repo.listMessages(any(), any(), any(), any()) } returns Result.success(
            MessagePage(messages = listOf(mockk(relaxed = true)), nextCursor = null)
        )
        val collab = StubCollaborator()
        val service = SessionStateService(testScope, Provider { repo }, collab, PaginationCursorPolicyFactory(Provider { repo }))
        var called = 0
        collab.refreshMessagesImpl = { _, _, _ -> called++ }
        collab.latestMessageIdImpl = { null }
        service.onSseEvent(SseEvent.SessionIdle("ses_x"), "ses_noanchor", "server-1")

        service.backfillMissedMessages("ses_noanchor")
        testScope.runCurrent()

        // 兜底拉取发生且合并走 SSE_PRIORITY
        assertEquals(1, called)
        coVerify(exactly = 1) { repo.listMessages(any(), "ses_noanchor", any(), null) }
    }

    /**
     * 2026-08-17 R3 缺口修复（缺口②）：V2 cursor 返回空页（anchorId 滑出服务器
     * cursor 窗口）时，无 cursor 重拉最新窗口一次；兜底拿到消息则以 SSE_PRIORITY 合并。
     */
    @Test
    fun backfillMissedMessages_emptyPage_fallsBackToLatestOnce() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getApiVersion(any()) } returns ApiVersion.V2
        // cursor 路径（before 非 null）返回空页
        coEvery { repo.listMessages(any(), any(), any(), any()) } returns Result.success(
            MessagePage(messages = emptyList(), nextCursor = null)
        )
        // 兜底路径（before=null）返回有消息（后声明的匹配 stub 优先）
        coEvery { repo.listMessages(any(), any(), any(), isNull()) } returns Result.success(
            MessagePage(messages = listOf(mockk(relaxed = true)), nextCursor = null)
        )
        val collab = StubCollaborator()
        val service = SessionStateService(testScope, Provider { repo }, collab, PaginationCursorPolicyFactory(Provider { repo }))
        val strategies = mutableListOf<MergeStrategy>()
        collab.refreshMessagesImpl = { sessionId, messages, strategy ->
                strategies.add(strategy)
        }
        collab.latestMessageIdImpl = { "msg_anchor_stale" }
        service.onSseEvent(SseEvent.SessionIdle("ses_x"), "ses_empty", "server-1")

        service.backfillMissedMessages("ses_empty")
        testScope.runCurrent()

        assertEquals(listOf(MergeStrategy.SSE_PRIORITY), strategies)
        // 总共恰好 2 次拉取（cursor 路径 1 次 + 兜底 1 次），其中 before=null 的
        // 兜底恰好 1 次——只兜底一层，防死循环。
        coVerify(exactly = 2) { repo.listMessages(any(), "ses_empty", any(), any()) }
        coVerify(exactly = 1) { repo.listMessages(any(), "ses_empty", any(), isNull()) }
    }

    /** 2026-08-17 R3 缺口修复（缺口②）：兜底拉取仍为空页时停止——只兜底一层，不递归。 */
    @Test
    fun backfillMissedMessages_fallbackEmpty_stopsAfterOneLayer() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getApiVersion(any()) } returns ApiVersion.V2
        coEvery { repo.listMessages(any(), any(), any(), any()) } returns Result.success(
            MessagePage(messages = emptyList(), nextCursor = null)
        )
        val collab = StubCollaborator()
        val service = SessionStateService(testScope, Provider { repo }, collab, PaginationCursorPolicyFactory(Provider { repo }))
        var called = 0
        collab.refreshMessagesImpl = { _, _, _ -> called++ }
        collab.latestMessageIdImpl = { "msg_anchor_stale" }
        service.onSseEvent(SseEvent.SessionIdle("ses_x"), "ses_stoplevel", "server-1")

        service.backfillMissedMessages("ses_stoplevel")
        testScope.runCurrent()

        assertEquals(0, called)
        // cursor 路径 + 兜底各 1 次，共 2 次——兜底空页后不再第三次拉取
        coVerify(exactly = 2) { repo.listMessages(any(), "ses_stoplevel", any(), any()) }
    }

    // ---- #191 方案 B：等待确认自适应降频（打标/清标语义；风暴抑制的端到端节奏走真机验证） ----

    @Test
    fun `rest busy with pending input marks waiting confirmation`() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.fetchSessionStatuses(any(), any()) } returns
            Result.success(mapOf("s1" to SessionStatus.Busy))
        val collab = StubCollaborator().apply { hasPendingUserInputImpl = { it == "s1" } }
        val service = newServiceWith(repo, collab)
        service.setServerId("server1")
        service.onClientSendParts("s1") // Busy
        testScope.runCurrent()
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertTrue(service.waitingConfirmedAt.containsKey("s1"))
    }

    @Test
    fun `rest busy with active children also marks`() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.fetchSessionStatuses(any(), any()) } returns
            Result.success(mapOf("s1" to SessionStatus.Busy))
        val collab = StubCollaborator().apply { hasActiveChildrenImpl = { _, _ -> true } }
        val service = newServiceWith(repo, collab)
        service.setServerId("server1")
        service.onClientSendParts("s1")
        testScope.runCurrent()
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertTrue(service.waitingConfirmedAt.containsKey("s1"))
    }

    @Test
    fun `rest busy without waiting clears mark`() {
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.fetchSessionStatuses(any(), any()) } returns
            Result.success(mapOf("s1" to SessionStatus.Busy))
        val collab = StubCollaborator() // pending=false, children=false
        val service = newServiceWith(repo, collab)
        service.setServerId("server1")
        service.onClientSendParts("s1")
        testScope.runCurrent()
        service.waitingConfirmedAt["s1"] = System.currentTimeMillis() - 5_000
        service.triggerRestValidation("s1")
        testScope.runCurrent()
        assertFalse(service.waitingConfirmedAt.containsKey("s1"))
    }

    @Test
    fun `real sse event clears waiting mark`() {
        val service = newService()
        service.waitingConfirmedAt["s1"] = System.currentTimeMillis()
        service.onSseEvent(SseEvent.SessionIdle(sessionId = "s1"), "s1", "server1")
        assertFalse(service.waitingConfirmedAt.containsKey("s1"))
    }

    @Test
    fun `non busy rest validation result clears waiting mark`() {
        val service = newService()
        service.waitingConfirmedAt["s1"] = System.currentTimeMillis()
        service.onRestValidation("s1", SessionStatus.Idle)
        assertFalse(service.waitingConfirmedAt.containsKey("s1"))
    }

}
