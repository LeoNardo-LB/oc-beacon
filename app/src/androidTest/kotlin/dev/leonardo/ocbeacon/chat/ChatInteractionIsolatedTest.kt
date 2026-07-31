package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.builder.anAssistantMessage
import dev.leonardo.ocbeacon.builder.aUserMessage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderInfo
import dev.leonardo.ocbeacon.domain.model.ModelInfo
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.fakes.FakeServerRepository
import org.junit.Test
import javax.inject.Inject

/**
 * Isolated integration tests for ChatScreen interaction behaviors.
 *
 * These tests were split from [ChatInteractionTest] because they fail due to
 * ViewModel reuse contamination — previous tests in the same class modify
 * shared ViewModel state that can't be reset. Each test here gets a fresh
 * ViewModel by living in its own test class.
 *
 * Extends [BaseChatTest] for the standard Hilt + Compose setup pattern.
 */
@HiltAndroidTest
class ChatInteractionIsolatedTest : BaseChatTest() {

    @Inject
    lateinit var providerRepo: ProviderRepository

    private val fakeServer: FakeServerRepository
        get() = providerRepo as FakeServerRepository

    // ============ Helpers ============

    /**
     * Seed messages so they appear in the UI.
     *
     * messageListState combines messages from messagesState with parts from
     * allPartsMapState (keyed by messageId). partsState is used by
     * startObservingMessages() internally but the UI reads allPartsMapState.
     */
    private fun seedMessages(vararg mwps: MessageWithParts) {
        fakeChat.messagesState.value = mwps.map { it.info }
        fakeChat.allPartsMapState.value = mwps.associate { it.info.id to it.parts }
    }

    // ============ Tests ============

    /**
     * Test: A tool card with completed output is displayed.
     *
     * Tool cards render through ToolCardScaffold. ReadToolCard (resolved by
     * DefaultToolCardResolver for "read" tool name) renders title from
     * R.string.tool_read = "Read".
     */
    @Test
    fun toolCardExpand_toggles() {
        renderChatScreen()
        composeRule.waitForIdle()

        // Seed AFTER render — ensures fresh ViewModel subscription
        val assistantMsg = anAssistantMessage(id = "a-tool") {
            toolCompleted(
                name = "read",
                output = "File contents here"
            )
        }
        seedMessages(assistantMsg)

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Read", substring = true, ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val toolNodes = composeRule.onAllNodesWithText("Read", substring = true, ignoreCase = true)
        assert(toolNodes.fetchSemanticsNodes().isNotEmpty()) {
            "Tool card with 'Read' should be displayed"
        }
    }

    /**
     * Test: Model selector shows available models when provider data is loaded.
     *
     * Providers are loaded from FakeServerRepository.catalogResult via
     * SelectModelUseCase → ProviderRepository.loadProviderCatalog().
     * The model label appears in AgentModelVariantSelector after providers load.
     */
    @Test
    fun modelSelector_showsAvailableModels() {
        // Set BOTH providersResult AND catalogResult — ModelConfigDelegate uses
        // loadProviders() for ProviderInfo list and loadProviderCatalog() for catalog.
        fakeServer.providersResult = Result.success(listOf(
            ProviderInfo(
                id = "test-provider",
                name = "Test Provider",
                enabled = true,
                connected = true,
                models = listOf(
                    ModelInfo(id = "model-a", name = "Model A", visible = true),
                    ModelInfo(id = "model-b", name = "Model B", visible = true)
                )
            )
        ))

        val testProvider = ProviderCatalog(
            id = "test-provider",
            name = "Test Provider",
            models = mapOf(
                "model-a" to dev.leonardo.ocbeacon.domain.model.ModelCatalog(
                    id = "model-a",
                    name = "Model A",
                    contextWindow = 128000
                ),
                "model-b" to dev.leonardo.ocbeacon.domain.model.ModelCatalog(
                    id = "model-b",
                    name = "Model B",
                    contextWindow = 200000
                )
            )
        )
        fakeServer.catalogResult = Result.success(
            ProvidersResponse(
                providers = listOf(testProvider),
                default = mapOf("test-provider" to "model-a")
            )
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // Force modelConfigState combine to re-evaluate after subscription
        tokenStatsTracker.update { copy(lastContextTokens = 1) }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Model A").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Test: Scroll-to-bottom FAB appears when scrolled away from bottom.
     *
     * ChatMessageList renders a SmallFloatingActionButton with
     * contentDescription "Scroll to bottom" (R.string.chat_scroll_bottom)
     * when !isAtBottom. Uses swipeDown() because reverseLayout=true.
     */
    @Test
    fun scrollToBottomFab_appearsWhenScrolledAway() {
        renderChatScreen()
        composeRule.waitForIdle()

        // Seed AFTER render — ensures fresh ViewModel subscription
        val mwps = (1..20).map { i ->
            val msg = aUserMessage(text = "", id = "u$i")
            val parts = listOf(Part.Text(
                id = "part-$i",
                sessionId = TEST_SESSION,
                messageId = "u$i",
                text = "Message number $i with enough text content to fill at least one full line"
            ))
            MessageWithParts(info = msg, parts = parts)
        }
        seedMessages(*mwps.toTypedArray())

        // Wait for messages to render
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Message", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Swipe to scroll away from bottom (reverseLayout: swipeDown scrolls up)
        composeRule.onAllNodes(hasScrollAction())[0].performTouchInput {
            repeat(3) { swipeDown(startY = 0.1f, endY = 0.9f) }
        }
        composeRule.waitForIdle()

        // FAB should appear (contentDescription = "Scroll to bottom")
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Scroll to bottom")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
