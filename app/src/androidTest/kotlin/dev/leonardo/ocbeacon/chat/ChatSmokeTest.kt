package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.HiltComponentActivity
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import dev.leonardo.ocbeacon.fakes.FakeChatRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.ui.screens.chat.ChatScreen
import dev.leonardo.ocbeacon.ui.theme.OpenCodeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * 冒烟测试：验证 Hilt 注入 + Compose 渲染在 fake repository 基础设施上
 * 能够端到端工作。
 *
 * 如果此测试通过，后续所有 ChatScreen 集成测试都可依赖相同的搭建模式。
 */
@HiltAndroidTest
class ChatSmokeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject lateinit var chatRepo: ChatRepository
    @Inject lateinit var sessionRepo: SessionRepository

    private val fakeChat get() = chatRepo as FakeChatRepository
    private val fakeSession get() = sessionRepo as FakeSessionRepository

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun chatScreen_renders_with_hilt_injection() {
        // 默认空状态 —— 仅验证屏幕挂载不崩溃
        composeRule.setContent {
            OpenCodeTheme {
                ChatScreen(
                    serverId = "test-server",
                    sessionId = "test-session",
                    onNavigateBack = {}
                )
            }
        }

        composeRule.waitForIdle()

        // 走到这里仍未崩溃，说明 Hilt 注入 + Compose 渲染正常工作。
        // 验证注入的确实是 fake（而非真实 repository）
        assert(fakeChat.messagesState.value.isEmpty()) { "FakeChatRepository should be injected" }
    }
}
