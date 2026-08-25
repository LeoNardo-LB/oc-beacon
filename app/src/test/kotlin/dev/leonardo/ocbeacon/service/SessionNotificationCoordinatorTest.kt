package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.usecase.ManagePermissionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C9 路由矩阵 JVM 直测（走查指出的零覆盖缺口——when 分发此前无任何测试）：
 * 同一事件在「前台活跃会话（抑制→提示音）/ 后台（系统通知）/ 其他会话聚焦
 * （不抑制，正常通知）」三态下的路由正确性；外加子会话冒泡/轮次完成门控/
 * streak 门控/auto-allow 开关/收敛等待（D2-L30）与通知总开关门控。
 *
 * [SessionNotificationCoordinator] 为纯 Kotlin：端口注入 fake（动作序列断言），
 * AppNotificationManager/SettingsRepository/EventDispatcher/UseCase 注入 mock。
 */
class SessionNotificationCoordinatorTest {

    private val server = ServerConfig(id = "server1", url = "http://10.0.2.2:4199", name = "TestServer")

    private val appNotificationManager = mockk<AppNotificationManager>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val eventDispatcher = mockk<EventDispatcher>()
    private val managePermissionUseCase = mockk<ManagePermissionUseCase>()
    private val focusHolder = SessionFocusHolder()
    private val port = RecordingPort()

    private lateinit var coordinator: SessionNotificationCoordinator

    private val sessionsFlow = MutableStateFlow<List<Session>>(emptyList())
    private val notificationsEnabledFlow = MutableStateFlow(true)
    private val silentNotificationsFlow = MutableStateFlow(false)
    private val autoAllowFlow = MutableStateFlow(false)

    @Before
    fun setup() {
        every { eventDispatcher.sessions } returns sessionsFlow
        every { settingsRepository.notificationsEnabled() } returns notificationsEnabledFlow
        every { settingsRepository.silentNotifications() } returns silentNotificationsFlow
        every { settingsRepository.autoAllowPermissions() } returns autoAllowFlow
        coordinator = SessionNotificationCoordinator(
            actions = port,
            appNotificationManager = appNotificationManager,
            sessionFocusHolder = focusHolder,
            settingsRepository = settingsRepository,
            eventDispatcher = eventDispatcher,
            managePermissionUseCase = managePermissionUseCase,
        )
    }

    // ============ 状态与事件构造 ============

    private fun session(id: String, parentId: String? = null, directory: String = "") =
        Session(id = id, parentId = parentId, directory = directory, time = Session.Time(created = 0, updated = 0))

    private fun userMessage(role: String = "user") = Message.User(
        id = "msg_u1", sessionId = "sess1", role = role, time = TimeInfo(created = 0),
    )

    private fun focusOn(sessionId: String) {
        focusHolder.setAppInForeground(true)
        focusHolder.setActiveFocus("server1", sessionId)
    }

    /** 三态：后台（无前台/无焦点）。 */
    private fun background() {
        focusHolder.setAppInForeground(false)
        focusHolder.setActiveFocus(null, null)
    }

    private fun idle(sid: String = "sess1") = SseEvent.SessionIdle(sessionId = sid)
    private fun permission(sid: String = "sess1") = SseEvent.PermissionAsked(
        id = "perm_1", sessionId = sid, permission = "fs.write",
    )
    private fun question(sid: String = "sess1", text: String? = "Favorite animal?") = SseEvent.QuestionAsked(
        id = "que_1", sessionId = sid,
        questions = text?.let {
            listOf(SseEvent.QuestionAsked.Question(header = "h", question = it, options = emptyList()))
        } ?: emptyList(),
    )
    private fun error(sid: String? = "sess1") = SseEvent.SessionError(sessionId = sid, error = "boom")

    // ============ SessionIdle：轮次完成三态路由 ============

    @Test
    fun idleBackgroundWithAssistantOutputPostsNotification() = runTest {
        background()
        every { appNotificationManager.checkNewAssistantMessage("server1", "sess1") } returns "msg_a1"

        coordinator.processEvent(server, idle())

        assertEquals(listOf("onTurnCompleted:server1:sess1", "showTurnComplete:sess1"), port.calls)
    }

    @Test
    fun idleForegroundActiveSessionPlaysSoundInsteadOfNotification() = runTest {
        focusOn("sess1")
        every { appNotificationManager.computeNewAssistantMessageId("sess1") } returns "msg_a1"

        coordinator.processEvent(server, idle())

        // #155：提示音路径纯查询（compute，不写通知去重 map）+ 不发系统通知
        assertEquals(
            listOf("onTurnCompleted:server1:sess1", "sound:TURN_COMPLETE:sess1:msg_a1"),
            port.calls,
        )
    }

    @Test
    fun idleForegroundButOtherSessionFocusedStillNotifies() = runTest {
        focusOn("other-session")
        every { appNotificationManager.checkNewAssistantMessage("server1", "sess1") } returns "msg_a1"

        coordinator.processEvent(server, idle())

        assertEquals(listOf("onTurnCompleted:server1:sess1", "showTurnComplete:sess1"), port.calls)
    }

    @Test
    fun idleBackgroundNoAssistantOutputDoesNothing() = runTest {
        background()
        every { appNotificationManager.checkNewAssistantMessage(any(), any()) } returns null

        coordinator.processEvent(server, idle())

        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun idleConvergenceRecoversOnLaterAttempt() = runTest {
        // D2-L30（#112）：首次 250ms 未收敛，第二次检查命中 → 仍通知
        background()
        every { appNotificationManager.checkNewAssistantMessage(any(), any()) } returns null andThen "msg_late"

        coordinator.processEvent(server, idle())

        assertEquals(listOf("onTurnCompleted:server1:sess1", "showTurnComplete:sess1"), port.calls)
    }

    @Test
    fun idleChildSessionNeitherNotifiesNorPlays() = runTest {
        // Q3：子智能体会话轮次完成既不通知也不响（三态全部静默）
        sessionsFlow.value = listOf(session("sess1", parentId = "ses_parent"))
        focusHolder.setAppInForeground(true)

        coordinator.processEvent(server, idle())

        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun idleNotificationsDisabledDoesNothing() = runTest {
        background()
        notificationsEnabledFlow.value = false

        coordinator.processEvent(server, idle())

        assertTrue(port.calls.isEmpty())
    }

    // ============ PermissionAsked 三态路由 + auto-allow ============

    @Test
    fun permissionBackgroundShowsNotification() = runTest {
        background()

        coordinator.processEvent(server, permission())

        assertEquals(listOf("showPermissionAsked:sess1:fs.write"), port.calls)
    }

    @Test
    fun permissionForegroundActiveSessionPlaysSound() = runTest {
        focusOn("sess1")

        coordinator.processEvent(server, permission())

        assertEquals(listOf("sound:PERMISSION:sess1:fs.write"), port.calls)
    }

    @Test
    fun permissionForegroundOtherSessionFocusedStillNotifies() = runTest {
        focusOn("other-session")

        coordinator.processEvent(server, permission())

        assertEquals(listOf("showPermissionAsked:sess1:fs.write"), port.calls)
    }

    @Test
    fun permissionChildSessionBubblesToParentNotification() = runTest {
        background()
        sessionsFlow.value = listOf(session("sess_child", parentId = "ses_parent"))

        coordinator.processEvent(server, permission("sess_child"))

        assertEquals(listOf("showPermissionAsked:ses_parent:fs.write"), port.calls)
    }

    @Test
    fun permissionAutoAllowSwitchSuccessSkipsNotification() = runTest {
        background()
        autoAllowFlow.value = true
        coEvery {
            managePermissionUseCase.replyToPermission("server1", "sess1", "perm_1", "always", null)
        } returns true

        coordinator.processEvent(server, permission())

        assertTrue(port.calls.isEmpty())
        coVerify(exactly = 1) {
            managePermissionUseCase.replyToPermission("server1", "sess1", "perm_1", "always", null)
        }
    }

    @Test
    fun permissionAutoAllowReplyFailureFallsBackToNotification() = runTest {
        background()
        autoAllowFlow.value = true
        coEvery {
            managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any())
        } throws RuntimeException("network down")

        coordinator.processEvent(server, permission())

        assertEquals(listOf("showPermissionAsked:sess1:fs.write"), port.calls)
    }

    @Test
    fun permissionAutoAllowDisabledNeverReplies() = runTest {
        background()
        autoAllowFlow.value = false

        coordinator.processEvent(server, permission())

        coVerify(exactly = 0) { managePermissionUseCase.replyToPermission(any(), any(), any(), any(), any()) }
        assertEquals(listOf("showPermissionAsked:sess1:fs.write"), port.calls)
    }

    @Test
    fun permissionNotificationsDisabledDoesNothing() = runTest {
        background()
        notificationsEnabledFlow.value = false

        coordinator.processEvent(server, permission())

        assertTrue(port.calls.isEmpty())
    }

    // ============ QuestionAsked 三态路由 + 冒泡 + 兜底文案 ============

    @Test
    fun questionBackgroundShowsNotificationWithQuestionText() = runTest {
        background()

        coordinator.processEvent(server, question())

        assertEquals(listOf("showQuestionAsked:sess1:Favorite animal?"), port.calls)
    }

    @Test
    fun questionForegroundActiveSessionPlaysSoundWithTextDedupKey() = runTest {
        focusOn("sess1")

        coordinator.processEvent(server, question())

        assertEquals(listOf("sound:QUESTION:sess1:Favorite animal?"), port.calls)
    }

    @Test
    fun questionForegroundOtherSessionFocusedStillNotifies() = runTest {
        focusOn("other-session")

        coordinator.processEvent(server, question())

        assertEquals(listOf("showQuestionAsked:sess1:Favorite animal?"), port.calls)
    }

    @Test
    fun questionChildSessionBubblesToParentNotification() = runTest {
        background()
        sessionsFlow.value = listOf(session("sess_child", parentId = "ses_parent"))

        coordinator.processEvent(server, question("sess_child"))

        assertEquals(listOf("showQuestionAsked:ses_parent:Favorite animal?"), port.calls)
    }

    @Test
    fun questionMissingTextUsesLocalizedFallback() = runTest {
        background()

        coordinator.processEvent(server, question(text = null))

        assertEquals(listOf("showQuestionAsked:sess1:<<fallback>>"), port.calls)
    }

    // ============ SessionError 三态路由 + streak 门控 ============

    @Test
    fun errorBackgroundFirstErrorShowsNotification() = runTest {
        background()

        coordinator.processEvent(server, error())

        assertEquals(listOf("streak:server1:sess1", "showSessionError:sess1:boom"), port.calls)
    }

    @Test
    fun errorBackgroundConsecutiveErrorSuppressedByStreak() = runTest {
        background()

        coordinator.processEvent(server, error())
        coordinator.processEvent(server, error())

        // R4：streak 占用后连发静默——第二条不再投递
        assertEquals(
            listOf("streak:server1:sess1", "showSessionError:sess1:boom", "streak:server1:sess1"),
            port.calls,
        )
    }

    @Test
    fun errorForegroundActiveSessionPlaysSound() = runTest {
        focusOn("sess1")

        coordinator.processEvent(server, error())

        // 提示音侧 streak 门控在 playIfFocused 内（R3），路由只判抑制
        assertEquals(listOf("sound:ERROR:sess1:boom"), port.calls)
    }

    @Test
    fun errorForegroundOtherSessionFocusedStillNotifies() = runTest {
        focusOn("other-session")

        coordinator.processEvent(server, error())

        assertEquals(listOf("streak:server1:sess1", "showSessionError:sess1:boom"), port.calls)
    }

    @Test
    fun errorNullSessionIdSkipsStreakAndSound() = runTest {
        background()

        coordinator.processEvent(server, error(sid = null))

        // 无会话 ID：不响（无目标）、streak 不占用；null 透传给通知实现侧
        //（AppNotificationManager.showErrorNotification 内部对 null 不投递）
        assertEquals(listOf("showSessionError:null:boom"), port.calls)
    }

    @Test
    fun errorChildSessionBubblesToParent() = runTest {
        background()
        sessionsFlow.value = listOf(session("sess_child", parentId = "ses_parent"))

        coordinator.processEvent(server, error("sess_child"))

        assertEquals(listOf("streak:server1:ses_parent", "showSessionError:ses_parent:boom"), port.calls)
    }

    // ============ MessageUpdated：streak 重置信号 ============

    @Test
    fun userMessageResetsStreak() = runTest {
        coordinator.processEvent(server, SseEvent.MessageUpdated(userMessage()))

        assertEquals(listOf("onUserMessage:server1:sess1"), port.calls)
    }

    @Test
    fun syntheticUserMessageDoesNotResetStreak() = runTest {
        // 合成消息（synthetic，工具代发）不算用户主动
        coordinator.processEvent(server, SseEvent.MessageUpdated(userMessage(role = "synthetic")))

        assertTrue(port.calls.isEmpty())
    }

    // ============ 其他事件：不路由 ============

    @Test
    fun unrelatedEventIsIgnored() = runTest {
        coordinator.processEvent(server, SseEvent.MessageRemoved(sessionId = "sess1", messageId = "msg_x"))

        assertTrue(port.calls.isEmpty())
    }

    // ============ fake 端口 ============

    /** 记录动作序列的 fake（streak 语义镜像 ErrorStreakTracker：首条 true、连发 false）。 */
    private class RecordingPort : NotificationActionPort {
        val calls = mutableListOf<String>()
        private var streakActive = false

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
            silentNotifications: Boolean,
            notificationsEnabled: Boolean,
        ) {
            calls += "sound:$type:$sessionId:$dedupKey"
        }

        override fun onTurnCompleted(serverId: String, sessionId: String) {
            calls += "onTurnCompleted:$serverId:$sessionId"
        }

        override fun onUserMessage(serverId: String, sessionId: String) {
            calls += "onUserMessage:$serverId:$sessionId"
        }

        override fun consumeNotificationErrorStreak(serverId: String, sessionId: String): Boolean {
            calls += "streak:$serverId:$sessionId"
            val allowed = !streakActive
            streakActive = true
            return allowed
        }

        override fun fallbackQuestionText(): String = "<<fallback>>"
    }
}
