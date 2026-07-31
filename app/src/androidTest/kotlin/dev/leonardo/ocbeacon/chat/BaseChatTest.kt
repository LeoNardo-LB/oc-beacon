package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextReplacement
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.HiltComponentActivity
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
 * Base class for ChatScreen integration tests.
 *
 * Provides the standard Hilt + Compose setup pattern verified by ChatSmokeTest.
 * Subclasses get pre-injected fakes and a [renderChatScreen] helper.
 */
@HiltAndroidTest
abstract class BaseChatTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltComponentActivity>()

    @Inject lateinit var chatRepo: ChatRepository
    @Inject lateinit var sessionRepo: SessionRepository
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var sessionStateService: dev.leonardo.ocbeacon.data.repository.SessionStateService
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
        // Reset ALL fake state — Hilt singletons persist across tests in the same class
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
        // Note: SessionStateService and TokenStatsTracker are @Singleton — their state
        // persists across tests. Tests that depend on specific FSM/token state should
        // set it explicitly AFTER renderChatScreen(), not rely on @Before defaults.
    }

    /**
     * Render ChatScreen with theme wrapper. Call after configuring fake state.
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
     * Type text into the chat input field.
     *
     * Uses [hasSetTextAction] to find the actual editable node inside BasicTextField's
     * decorationBox — the outer testTag node may not have the SetText semantics action
     * due to semantics merge timing.
     */
    protected fun typeInput(text: String) {
        // Wait for the editable text node to be ready (ViewModel init is async)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextReplacement(text)
        composeRule.waitForIdle()
    }
}
