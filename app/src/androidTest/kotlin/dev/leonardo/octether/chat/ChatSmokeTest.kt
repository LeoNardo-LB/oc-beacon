package dev.leonardo.octether.chat

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.octether.HiltComponentActivity
import dev.leonardo.octether.domain.repository.ChatRepository
import dev.leonardo.octether.fakes.FakeChatRepository
import dev.leonardo.octether.fakes.FakeSessionRepository
import dev.leonardo.octether.domain.repository.SessionRepository
import dev.leonardo.octether.ui.screens.chat.ChatScreen
import dev.leonardo.octether.ui.theme.OpenCodeTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Smoke test: proves Hilt injection + Compose rendering works end-to-end
 * with the fake repository infrastructure.
 *
 * If this passes, all subsequent ChatScreen integration tests can rely
 * on the same setup pattern.
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
        // Default empty state — just verify the screen mounts without crashing
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

        // If we got here without crashing, Hilt injection + Compose rendering works.
        // Verify the fake was actually injected (not a real repository)
        assert(fakeChat.messagesState.value.isEmpty()) { "FakeChatRepository should be injected" }
    }
}
