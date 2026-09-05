package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.PendingInteractionKind
import dev.leonardo.ocbeacon.data.repository.PendingInteractionStore
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.ServerConfigRepository
import dev.leonardo.ocbeacon.domain.repository.SessionStateRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #336：审批/提问通知退后台补发——纯逻辑矩阵。
 *
 * 补发语义（与 #320 到达时语义互补）：
 * - 前台→后台转换沿（SessionFocusHolder.isAppInForeground true→false）触发扫描；
 * - 扫描 PendingInteractionStore：存在挂起且未通知的会话 → 补发对应 kind 通知；
 * - 无 pending 不发；已发过（已通知槽置位）不重发；
 * - 子会话挂起冒泡到父会话目标（镜像 SessionNotificationCoordinator 发布口径）；
 * - 通知总开关关闭不补发（与到达路径 maybeNotify 同门）。
 *
 * 撤通知路径（PendingInteractionNotificationRevoker）不回归：pending 清除 →
 * 已通知槽随清除复位（下一轮可再补发）——槽生命周期单测见
 * PendingInteractionStoreTest #336 节。
 */
class PendingInteractionBackgroundNotifierTest {

    private val server = ServerConfig(id = "server1", url = "http://10.0.2.2:4199", name = "TestServer")

    private val statuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    private val serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val sessionsFlow = MutableStateFlow<List<Session>>(emptyList())
    private val notificationsEnabledFlow = MutableStateFlow(true)

    private val eventDispatcher = mockk<EventDispatcher>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val serverConfigRepository = mockk<ServerConfigRepository>()
    private val focusHolder = SessionFocusHolder()
    private val port = RecordingPort()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var store: PendingInteractionStore

    @Before
    fun setup() {
        every { eventDispatcher.serverSessions } returns serverSessions
        every { eventDispatcher.sessions } returns sessionsFlow
        every { settingsRepository.notificationsEnabled() } returns notificationsEnabledFlow
        coEvery { serverConfigRepository.getServer("server1") } returns server
        store = PendingInteractionStore(
            mockk<SessionStateRepository> { every { statusFlow } returns statuses },
            CoroutineScope(testDispatcher + SupervisorJob()),
        )
    }

    /** 前台态构造 + 挂起注入 + 退后台沿（每用例共用时序）。 */
    private fun goBackgroundWithPending(vararg pendings: Pair<String, PendingInteractionKind>) {
        focusHolder.setAppInForeground(true)
        serverSessions.value = mapOf("server1" to pendings.map { it.first }.toSet())
        pendings.forEach { (sid, kind) -> store.record(sid, kind) }
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        focusHolder.setAppInForeground(false)
    }

    // ============ 补发主路径：前台→后台转换 + pending 存在 → 发 ============

    @Test
    fun `fg to bg transition with unnotified approval dispatches permission notification`() =
        runTest(testDispatcher) {
            goBackgroundWithPending("s1" to PendingInteractionKind.APPROVAL)
            advanceUntilIdle()

            assertEquals(listOf("showPermissionAsked:s1:"), port.calls)
            // 补发后槽置位：不重发
            assertTrue(store.isNotified("s1"))
        }

    @Test
    fun `fg to bg transition with unnotified question dispatches question notification`() =
        runTest(testDispatcher) {
            goBackgroundWithPending("s1" to PendingInteractionKind.QUESTION)
            advanceUntilIdle()

            assertEquals(listOf("showQuestionAsked:s1:"), port.calls)
            assertTrue(store.isNotified("s1"))
        }

    @Test
    fun `plan-review pending dispatches question family notification`() =
        runTest(testDispatcher) {
            goBackgroundWithPending("s1" to PendingInteractionKind.PLAN_REVIEW)
            advanceUntilIdle()

            assertEquals(listOf("showQuestionAsked:s1:"), port.calls)
        }

    @Test
    fun `multiple pending sessions each dispatch`() = runTest(testDispatcher) {
        goBackgroundWithPending(
            "s1" to PendingInteractionKind.APPROVAL,
            "s2" to PendingInteractionKind.QUESTION,
        )
        advanceUntilIdle()

        assertEquals(
            setOf("showPermissionAsked:s1:", "showQuestionAsked:s2:"),
            port.calls.toSet(),
        )
    }

    // ============ 无 pending 不发 ============

    @Test
    fun `fg to bg transition without pending dispatches nothing`() = runTest(testDispatcher) {
        focusHolder.setAppInForeground(true)
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        focusHolder.setAppInForeground(false)
        advanceUntilIdle()

        assertTrue(port.calls.isEmpty())
    }

    // ============ 已发不重发 ============

    @Test
    fun `already notified pending is not re-dispatched`() = runTest(testDispatcher) {
        focusHolder.setAppInForeground(true)
        serverSessions.value = mapOf("server1" to setOf("s1"))
        store.record("s1", PendingInteractionKind.APPROVAL)
        // 到达路径已发布并标记（SessionNotificationCoordinator 同点标记）
        store.markNotified("s1", PendingInteractionKind.APPROVAL)
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        focusHolder.setAppInForeground(false)
        advanceUntilIdle()

        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `dispatch marks slot so repeated bg transitions do not re-send`() =
        runTest(testDispatcher) {
            goBackgroundWithPending("s1" to PendingInteractionKind.APPROVAL)
            advanceUntilIdle()
            assertEquals(listOf("showPermissionAsked:s1:"), port.calls)

            // 再回前台 → 再退后台：不重发
            focusHolder.setAppInForeground(true)
            focusHolder.setAppInForeground(false)
            advanceUntilIdle()

            assertEquals(listOf("showPermissionAsked:s1:"), port.calls)
        }

    @Test
    fun `answered pending clears slot and next round can dispatch again`() =
        runTest(testDispatcher) {
            goBackgroundWithPending("s1" to PendingInteractionKind.APPROVAL)
            advanceUntilIdle()
            assertEquals(listOf("showPermissionAsked:s1:"), port.calls)

            // 本地应答成功 → pending 与已通知槽同点复位
            store.clearIfKind("s1", PendingInteractionKind.APPROVAL)
            focusHolder.setAppInForeground(true)
            focusHolder.setAppInForeground(false)
            advanceUntilIdle()
            // 无 pending：不再发（新到达事件的发布归 SSE 管线）
            assertEquals(listOf("showPermissionAsked:s1:"), port.calls)

            // 新一轮挂起（未通知）→ 可再补发
            store.record("s1", PendingInteractionKind.APPROVAL)
            focusHolder.setAppInForeground(true)
            focusHolder.setAppInForeground(false)
            advanceUntilIdle()
            assertEquals(2, port.calls.size)
        }

    // ============ 转换沿方向性 ============

    @Test
    fun `staying foreground never dispatches`() = runTest(testDispatcher) {
        focusHolder.setAppInForeground(true)
        serverSessions.value = mapOf("server1" to setOf("s1"))
        store.record("s1", PendingInteractionKind.APPROVAL)
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        advanceUntilIdle()

        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `bg to fg transition does not dispatch`() = runTest(testDispatcher) {
        serverSessions.value = mapOf("server1" to setOf("s1"))
        store.record("s1", PendingInteractionKind.APPROVAL)
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        focusHolder.setAppInForeground(true)
        advanceUntilIdle()

        assertTrue(port.calls.isEmpty())
    }

    // ============ 门控与归属 ============

    @Test
    fun `notifications disabled does not dispatch`() = runTest(testDispatcher) {
        notificationsEnabledFlow.value = false
        goBackgroundWithPending("s1" to PendingInteractionKind.APPROVAL)
        advanceUntilIdle()

        assertTrue(port.calls.isEmpty())
        // 未发 → 槽不置位
        assertTrue(!store.isNotified("s1"))
    }

    @Test
    fun `child session pending dispatches to parent target`() = runTest(testDispatcher) {
        sessionsFlow.value = listOf(
            Session(
                id = "s_child",
                parentId = "s_parent",
                directory = "",
                time = Session.Time(created = 0, updated = 0),
            )
        )
        goBackgroundWithPending("s_child" to PendingInteractionKind.APPROVAL)
        advanceUntilIdle()

        // 冒泡口径镜像 SessionNotificationCoordinator（发布目标=父会话）
        assertEquals(listOf("showPermissionAsked:s_parent:"), port.calls)
        // 槽按 store 键（原始事件 sessionId）置位
        assertTrue(store.isNotified("s_child"))
    }

    @Test
    fun `unknown server ownership skips dispatch`() = runTest(testDispatcher) {
        focusHolder.setAppInForeground(true)
        store.record("orphan", PendingInteractionKind.APPROVAL)
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        focusHolder.setAppInForeground(false)
        advanceUntilIdle()

        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun `same session id owned by two servers dispatches for both`() = runTest(testDispatcher) {
        focusHolder.setAppInForeground(true)
        coEvery { serverConfigRepository.getServer("server2") } returns
            server.copy(id = "server2")
        serverSessions.value = mapOf("server1" to setOf("s1"), "server2" to setOf("s1"))
        store.record("s1", PendingInteractionKind.QUESTION)
        PendingInteractionBackgroundNotifier(
            store = store,
            sessionFocusHolder = focusHolder,
            actions = port,
            eventDispatcher = eventDispatcher,
            settingsRepository = settingsRepository,
            serverConfigRepository = serverConfigRepository,
            appScope = CoroutineScope(testDispatcher + SupervisorJob()),
        )
        focusHolder.setAppInForeground(false)
        advanceUntilIdle()

        assertEquals(2, port.calls.count { it.startsWith("showQuestionAsked:s1:") })
    }

    // ============ fake 端口 ============

    /** 记录补发动作的 fake（补发只走 permission/question 两径）。 */
    private class RecordingPort : NotificationActionPort {
        val calls = mutableListOf<String>()

        override suspend fun showTurnComplete(server: ServerConfig, sessionId: String) {
            calls += "showTurnComplete:$sessionId"
        }

        override suspend fun showPermissionAsked(server: ServerConfig, sessionId: String, permission: String) {
            calls += "showPermissionAsked:$sessionId:$permission"
        }

        override suspend fun showQuestionAsked(server: ServerConfig, sessionId: String, questionText: String) {
            calls += "showQuestionAsked:$sessionId:$questionText"
        }

        override suspend fun showSessionError(server: ServerConfig, sessionId: String?, error: String) {
            calls += "showSessionError:$sessionId:$error"
        }

        override suspend fun playInSessionSound(
            serverId: String,
            sessionId: String,
            type: FeedbackType,
            dedupKey: String,
        ) {
            calls += "sound:$type:$sessionId:$dedupKey"
        }

        override fun onTurnCompleted(serverId: String, sessionId: String) = Unit

        override fun onUserMessage(serverId: String, sessionId: String) = Unit

        override fun consumeNotificationErrorStreak(serverId: String, sessionId: String): Boolean = true

        override fun fallbackQuestionText(): String = "<<fallback>>"
    }
}
