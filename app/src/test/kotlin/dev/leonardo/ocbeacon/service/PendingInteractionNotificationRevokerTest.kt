package dev.leonardo.ocbeacon.service

import android.app.NotificationManager
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.PendingInteractionKind
import dev.leonardo.ocbeacon.data.repository.PendingInteractionStore
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #320：PendingInteraction 三清除径 → 同点撤通知矩阵。
 *
 * 清除三径（#311 契约，勿增第四径）：
 * ① 本地应答成功（clearIfKind——removePermission/removeQuestion 委托同点）；
 * ② 轮次结束兜底（store 订阅 statusFlow → Idle 清）；
 * ③ 会话删除级联（clearForSession）。
 * 另有 clearAll（服务器断连清理）——全清同样撤。
 *
 * 撤通知口径：只撤被清除 kind 对应的系统通知（审批=+1000 / 提问=+2000）；
 * turn 完成（+0，信息性发出后不撤）与错误（+3000）不动。
 * 通知发布本身属 SSE 管线（SessionNotificationCoordinator → AppNotificationManager，
 * 既有覆盖）；本组件只负责"等待态终结 → 撤对应通知"。
 */
class PendingInteractionNotificationRevokerTest {

    private val statuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    private val serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val notificationManager: NotificationManager = mockk(relaxed = true)

    private lateinit var store: PendingInteractionStore
    private lateinit var manager: AppNotificationManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        val stateRepo = mockk<SessionStateRepository> { every { statusFlow } returns statuses }
        val eventDispatcher = mockk<EventDispatcher>()
        every { eventDispatcher.messages } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.parts } returns MutableStateFlow(emptyMap())
        every { eventDispatcher.sessions } returns MutableStateFlow<List<Session>>(emptyList())
        every { eventDispatcher.serverSessions } returns serverSessions

        val appContext = mockk<android.content.Context>()
        every {
            appContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
        } returns notificationManager

        store = PendingInteractionStore(stateRepo, CoroutineScope(testDispatcher + SupervisorJob()))
        manager = AppNotificationManager(
            eventDispatcher,
            mockk<SettingsRepository>(),
            SessionFocusHolder(),
            mockk(relaxed = true),
            CoroutineScope(testDispatcher + SupervisorJob()),
            appContext,
        )
        PendingInteractionNotificationRevoker(
            store = store,
            appNotificationManager = manager,
            eventDispatcher = eventDispatcher,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
    }

    private fun baseId(serverId: String = "server1", sessionId: String = "s1"): Int =
        SessionNotificationIds.of(serverId, sessionId, SessionNotificationKind.TURN_COMPLETE)

    // ============ 前置：记录本身不撤（发布归 SSE 管线）============

    @Test
    fun `recording pending interaction does not cancel anything`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.APPROVAL)
        advanceUntilIdle()

        verify(exactly = 0) { notificationManager.cancel(any(), any()) }
        verify(exactly = 0) { notificationManager.cancel(any<Int>()) }
    }

    // ============ 清除① 本地应答成功（clearIfKind）============

    @Test
    fun `local approval reply cancels permission notification only`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("s1", PendingInteractionKind.APPROVAL)
        advanceUntilIdle()

        val base = baseId()
        verify(exactly = 1) { notificationManager.cancel(base + 1000) }
        verify(exactly = 0) { notificationManager.cancel(base + 0) }
        verify(exactly = 0) { notificationManager.cancel(base + 2000) }
        verify(exactly = 0) { notificationManager.cancel(base + 3000) }
    }

    @Test
    fun `local question reply cancels question notification only`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.QUESTION)
        store.clearIfKind("s1", PendingInteractionKind.QUESTION)
        advanceUntilIdle()

        val base = baseId()
        verify(exactly = 1) { notificationManager.cancel(base + 2000) }
        verify(exactly = 0) { notificationManager.cancel(base + 1000) }
    }

    @Test
    fun `plan-review cleared via question family cancels question notification`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.PLAN_REVIEW)
        store.clearIfKind("s1", PendingInteractionKind.QUESTION)
        advanceUntilIdle()

        verify(exactly = 1) { notificationManager.cancel(baseId() + 2000) }
    }

    // ============ 清除② 轮次结束兜底（statusFlow → Idle）============

    @Test
    fun `busy to idle transition cancels approval notification`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.APPROVAL)
        statuses.value = mapOf("s1" to SessionStatus.Busy)
        advanceUntilIdle()
        verify(exactly = 0) { notificationManager.cancel(any<Int>()) }

        statuses.value = mapOf("s1" to SessionStatus.Idle)
        advanceUntilIdle()
        verify(exactly = 1) { notificationManager.cancel(baseId() + 1000) }
    }

    // ============ 清除③ 会话删除级联（clearForSession）============

    @Test
    fun `session deletion cascade cancels pending kind notification`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.QUESTION)
        // 仿真级联时序：SessionDeleted 分发先清 serverSessions 注册表（handler.handle
        // 在前）再清 pending store——撤通知必须仍能定位归属（快照）。
        serverSessions.value = emptyMap()
        store.clearForSession("s1")
        advanceUntilIdle()

        verify(exactly = 1) { notificationManager.cancel(baseId() + 2000) }
    }

    @Test
    fun `clearAll on disconnect cancels all pending notifications`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1", "s2"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.record("s2", PendingInteractionKind.QUESTION)
        store.clearAll()
        advanceUntilIdle()

        verify(exactly = 1) { notificationManager.cancel(baseId(sessionId = "s1") + 1000) }
        verify(exactly = 1) { notificationManager.cancel(baseId(sessionId = "s2") + 2000) }
    }

    // ============ kind 切换：撤旧 kind 槽位 ============

    @Test
    fun `kind switch from approval to question cancels permission slot only`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.APPROVAL)
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.QUESTION)
        advanceUntilIdle()

        verify(exactly = 1) { notificationManager.cancel(baseId() + 1000) }
        verify(exactly = 0) { notificationManager.cancel(baseId() + 2000) }
    }

    // ============ 多服务器同 sessionId ============

    @Test
    fun `same session id on two servers cancels both servers notifications`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"), "server2" to setOf("s1"))
        advanceUntilIdle()
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("s1", PendingInteractionKind.APPROVAL)
        advanceUntilIdle()

        verify(exactly = 1) { notificationManager.cancel(baseId(serverId = "server1") + 1000) }
        verify(exactly = 1) { notificationManager.cancel(baseId(serverId = "server2") + 1000) }
    }

    // ============ 撤后去重重置（下一轮同类事件可再通知）============

    @Test
    fun `cancelling permission notification resets dedup for next round`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        manager.markPermissionNotified("server1", "s1", "fs.write")
        store.record("s1", PendingInteractionKind.APPROVAL)
        store.clearIfKind("s1", PendingInteractionKind.APPROVAL)
        advanceUntilIdle()

        assertTrue(manager.shouldNotifyPermission("server1", "s1", "fs.write"))
    }

    @Test
    fun `cancelling question notification resets dedup for next round`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        advanceUntilIdle()
        manager.markQuestionNotified("server1", "s1", "What animal?")
        store.record("s1", PendingInteractionKind.QUESTION)
        store.clearIfKind("s1", PendingInteractionKind.QUESTION)
        advanceUntilIdle()

        assertTrue(manager.shouldNotifyQuestion("server1", "s1", "What animal?"))
    }

    // ============ 未知归属：不崩、不撤 ============

    @Test
    fun `clearing session with unknown server ownership is a no-op`() = runTest(testDispatcher) {
        advanceUntilIdle()
        store.record("unknown", PendingInteractionKind.APPROVAL)
        store.clearForSession("unknown")
        advanceUntilIdle()

        verify(exactly = 0) { notificationManager.cancel(any<Int>()) }
    }
}
