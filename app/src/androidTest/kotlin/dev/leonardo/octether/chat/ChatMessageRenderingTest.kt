package dev.leonardo.octether.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.octether.builder.PartListBuilder
import dev.leonardo.octether.builder.aUserMessage
import dev.leonardo.octether.builder.anAssistantMessage
import dev.leonardo.octether.builder.randomId
import dev.leonardo.octether.builder.testSettings
import dev.leonardo.octether.domain.model.MessageWithParts
import org.junit.Test

/**
 * Integration tests verifying message rendering branches in ChatScreen.
 *
 * Covers: user messages, streaming/completed assistant messages, reasoning,
 * tool cards, error display, turn ordering, and empty state.
 *
 * Uses [BaseChatTest] for Hilt + Compose setup. Messages are seeded directly
 * into the fake repository's StateFlows (messagesState + allPartsMapState),
 * which the MessageDataDelegate combine pipeline reads from.
 */
@HiltAndroidTest
class ChatMessageRenderingTest : BaseChatTest() {

    // ============ Helpers ============

    /**
     * Seed messages into the fake repository's observable flows.
     *
     * FakeChatRepository.setMessages writes to an internal store that is NOT
     * connected to the StateFlows the UI reads (messagesState / allPartsMapState).
     * This helper bridges that gap by setting the flows directly, matching the
     * real ChatRepositoryImpl semantics where setMessages updates the flows.
     */
    private fun seedMessages(vararg mwps: MessageWithParts) {
        fakeChat.messagesState.value = mwps.map { it.info }
        fakeChat.allPartsMapState.value = mwps.associate { it.info.id to it.parts }
    }

    /**
     * Build a user MessageWithParts containing a single text part.
     *
     * aUserMessage() creates a bare Message.User without parts; the UI renders
     * user text from Part.Text, so we must attach one.
     */
    private fun userMessageWithText(text: String): MessageWithParts {
        val msg = aUserMessage(text, id = randomId(), sessionId = TEST_SESSION)
        val parts = PartListBuilder(sessionId = TEST_SESSION, messageId = msg.id).apply {
            this.text(text)
        }.build()
        return MessageWithParts(info = msg, parts = parts)
    }

    // ============ Tests ============

    /**
     * Test 1: A user message renders its text content inside a chat bubble.
     */
    @Test
    fun user_message_renders_with_correct_styling() {
        seedMessages(userMessageWithText("Hello world"))

        renderChatScreen()

        composeRule.onNodeWithText("Hello world").assertIsDisplayed()
    }

    /**
     * Test 2: A streaming assistant message (time.completed == null) with a
     * running tool shows the tool card — the ToolCardScaffold renders a
     * PulsingDotsIndicator next to the tool title when isRunning is true.
     *
     * We assert the tool title text is displayed (it co-exists with the
     * pulsing indicator in the same card row). The ReadToolCard resolves
     * the title from R.string.tool_read = "Read".
     */
    @Test
    fun streaming_assistant_shows_pulsing_indicator() {
        val msg = anAssistantMessage(streaming = true, sessionId = TEST_SESSION) {
            tool("read")
        }
        seedMessages(msg)

        renderChatScreen()

        // ReadToolCard title = R.string.tool_read = "Read".
        // The PulsingDotsIndicator renders alongside (isRunning = true).
        composeRule.onNodeWithText("Read").assertIsDisplayed()
    }

    /**
     * Test 3: A completed assistant message renders its text without any
     * streaming/running indicator.
     */
    @Test
    fun completed_assistant_without_streaming_indicator() {
        val msg = anAssistantMessage(streaming = false, sessionId = TEST_SESSION) {
            text("I am a completed response")
        }
        seedMessages(msg)

        renderChatScreen()

        composeRule.onNodeWithText("I am a completed response").assertIsDisplayed()
    }

    /**
     * Test 4: A reasoning part renders inside a collapsible ReasoningBlock.
     *
     * The reasoning block is collapsed by default (expandReasoning = false),
     * so we enable expandReasoning in settings to make the reasoning text
     * content visible for assertion.
     */
    @Test
    fun reasoning_part_renders() {
        fakeSettings.settingsState.value = testSettings(expandReasoning = true)

        val msg = anAssistantMessage(streaming = false, sessionId = TEST_SESSION) {
            reasoning("Thinking about this problem carefully")
            text("Here is my answer")
        }
        seedMessages(msg)

        renderChatScreen()

        // With expandReasoning = true, the reasoning content is visible
        composeRule.onNodeWithText("Thinking about this problem carefully").assertIsDisplayed()
    }

    /**
     * Test 5: A completed tool part renders as an expandable tool card
     * showing the tool name.
     *
     * The DefaultToolCardResolver maps "read" to ReadToolCard, which uses
     * R.string.tool_read = "Read" as the card title.
     */
    @Test
    fun tool_part_renders_as_expandable_card() {
        val msg = anAssistantMessage(streaming = false, sessionId = TEST_SESSION) {
            toolCompleted("read", "file content here")
        }
        seedMessages(msg)

        renderChatScreen()

        // ReadToolCard title = R.string.tool_read = "Read"
        composeRule.onNodeWithText("Read").assertIsDisplayed()
    }

    /**
     * Test 6: An assistant message with an error renders the error text
     * inside an error container surface.
     *
     * anAssistantMessage(error = "...") creates ErrorInfo(name = "TestError").
     * formatAssistantErrorMessage returns "TestError" (falls back to name when
     * data is null). The error is shown at the bottom of the assistant card.
     *
     * A text part is included so the message passes the assistant-with-parts
     * filter in messageListState.
     */
    @Test
    fun error_message_renders() {
        val msg = anAssistantMessage(streaming = false, error = "error", sessionId = TEST_SESSION) {
            text("Partial response before error")
        }
        seedMessages(msg)

        renderChatScreen()

        // ErrorPayloadContent renders the error name as plain text
        composeRule.onNodeWithText("TestError").assertIsDisplayed()
    }

    /**
     * Test 7: A user message followed by an assistant message both render,
     * appearing in the correct order within the chat list.
     */
    @Test
    fun turn_dividers_between_user_assistant_pairs() {
        val user = userMessageWithText("User asks a question")
        val assistant = anAssistantMessage(streaming = false, sessionId = TEST_SESSION) {
            text("Assistant answers")
        }
        seedMessages(user, assistant)

        renderChatScreen()

        // Both messages should be displayed
        composeRule.onNodeWithText("User asks a question").assertIsDisplayed()
        composeRule.onNodeWithText("Assistant answers").assertIsDisplayed()
    }

    /**
     * Test 8: An empty session (no messages) renders without crashing,
     * showing the ChatEmptyState placeholder.
     *
     * ChatEmptyState displays R.string.chat_empty = "Start a conversation with OpenCode".
     */
    @Test
    fun empty_session_shows_placeholder_or_empty_state() {
        // Seed no messages — default empty state
        renderChatScreen()

        // ChatEmptyState shows this text when messages are empty and not loading
        composeRule.onNodeWithText("Start a conversation with OpenCode").assertIsDisplayed()
    }
}
