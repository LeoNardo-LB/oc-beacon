package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.HiltEntryActivity
import dev.leonardo.ocbeacon.builder.aSession
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.fakes.FakeChatRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionRepository
import dev.leonardo.ocbeacon.fakes.FakeSettingsRepository
import dev.leonardo.ocbeacon.ui.screens.chat.ChatScreen
import dev.leonardo.ocbeacon.ui.theme.OpenCodeTheme
import org.junit.Before
import org.junit.Rule
import javax.inject.Inject

/**
 * ChatScreen 集成测试的基类。
 *
 * 提供经 ChatSmokeTest 验证的标准 Hilt + Compose 搭建模式。
 * 子类可获得预注入的 fakes 和 [renderChatScreen] 辅助方法。
 */
@HiltAndroidTest
abstract class BaseChatTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltEntryActivity>()

    @Inject lateinit var chatRepo: ChatRepository
    @Inject lateinit var sessionRepo: SessionRepository
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var tokenStatsTracker: dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker

    /** #417：生产单例 —— 测试经 markLinkConnectedForTest 满足 fastFailIfLinkBlocked 前置。 */
    @Inject lateinit var sseConnectionManager: dev.leonardo.ocbeacon.service.SseConnectionManager

    protected val fakeChat get() = chatRepo as FakeChatRepository
    protected val fakeSession get() = sessionRepo as FakeSessionRepository
    protected val fakeSettings get() = settingsRepo as FakeSettingsRepository

    protected companion object {
        const val TEST_SERVER = "test-server"
        const val TEST_SESSION = "test-session"
    }

    @Before
    open fun setup() {
        hiltRule.inject()
        // 重置所有 fake 状态 —— Hilt 单例在同一类的多个测试间持久存在
        fakeChat.apply {
            messagesState.value = emptyList()
            partsState.value = emptyList()
            allPartsMapState.value = emptyMap()
            permissionsState.value = emptyList()
            questionsState.value = emptyList()
            allPermissionsMapState.value = emptyMap()
            allQuestionsMapState.value = emptyMap()
            sentMessages.clear()
            promptAsyncCalls.clear()
            mentionCandidatesResult = Result.success(emptyList())
        }
        fakeSession.apply {
            sessionsState.value = listOf(
                aSession(id = TEST_SESSION, title = "Test", status = SessionStatus.Idle)
            )
            statusesState.value = emptyMap()
        }
        // #417：发送路径三态哨兵前置 —— fastFailIfLinkBlocked 在非 Connected 下
        // 直接吞掉 sendMessage（#267 断连快速失败）；测试环境无真 SSE，标记为已连。
        sseConnectionManager.markLinkConnectedForTest(TEST_SERVER)
        // 注意：TokenStatsTracker 是 @Singleton —— 其状态在测试间持久存在。
        // 依赖特定 token 状态的测试应当在 renderChatScreen() 之后显式设置，
        // 而不是依赖 @Before 的默认值。
    }

    /**
     * 在 theme 包装下渲染 ChatScreen。在配置完 fake 状态后调用。
     */
    protected fun renderChatScreen(
        serverId: String = TEST_SERVER,
        sessionId: String = TEST_SESSION,
        simulateExistingSession: Boolean = false,
    ) {
        // #417：测试无导航 → savedStateHandle 缺 sessionId（VM 侧 sessionIdFlow 为空串）。
        // 默认保持该历史语义（权限/问题卡种子以 "" 为存储键、会话生命周期走新会话
        // 分支）；需要真实会话身份的测试（如 @ mention 搜索的 blank-sessionId 守卫）
        // 传 [simulateExistingSession]——SavedStateViewModelFactory 会把 owner Activity
        // 的 intent extras 吸收进 SavedStateHandle，setContent（首次组合即建 VM）前
        // 注入即等价真实导航参数。
        // #417：VM 的 serverId 同样来自 savedStateHandle——无导航时为空串，
        // 与 setup() 里 markLinkConnectedForTest(TEST_SERVER) 的键失配会让
        // fastFailIfLinkBlocked 静默吞掉全部发送（无日志）。无条件注入 serverId
        // 与 ChatScreen 参数一致（fake 流忽略该 key，无其他行为差异）。
        composeRule.activity.intent.putExtra("serverId", serverId)
        if (simulateExistingSession) {
            composeRule.activity.intent.putExtra("sessionId", sessionId)
        } else {
            // extras 粘性：file_mention 注入后同类后续方法共享同一 activity ——
            // 显式摘除，保持默认测试的新会话语义。
            composeRule.activity.intent.removeExtra("sessionId")
        }
        composeRule.setContent {
            OpenCodeTheme {
                ChatScreen(
                    serverId = serverId,
                    sessionId = sessionId,
                    onNavigateBack = {}
                )
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * 在聊天输入框中输入文本。
     *
     * 使用 [hasSetTextAction] 在 BasicTextField 的 decorationBox 内部定位
     * 真正的可编辑节点 —— 由于 semantics 合并时机问题，外层的 testTag
     * 节点可能没有 SetText semantics action。
     */
    protected fun typeInput(text: String) {
        // 等待可编辑文本节点就绪（ViewModel init 是异步的）
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement(text)
        composeRule.waitForIdle()
    }
}
