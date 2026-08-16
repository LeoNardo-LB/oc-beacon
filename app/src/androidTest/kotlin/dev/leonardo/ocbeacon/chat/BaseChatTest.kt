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
        }
        fakeSession.apply {
            sessionsState.value = listOf(
                aSession(id = TEST_SESSION, title = "Test", status = SessionStatus.Idle)
            )
            statusesState.value = emptyMap()
        }
        // 注意：TokenStatsTracker 是 @Singleton —— 其状态在测试间持久存在。
        // 依赖特定 token 状态的测试应当在 renderChatScreen() 之后显式设置，
        // 而不是依赖 @Before 的默认值。
    }

    /**
     * 在 theme 包装下渲染 ChatScreen。在配置完 fake 状态后调用。
     */
    protected fun renderChatScreen(
        serverId: String = TEST_SERVER,
        sessionId: String = TEST_SESSION
    ) {
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
