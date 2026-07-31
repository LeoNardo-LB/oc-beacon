package dev.leonardo.octether.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.octether.builder.aUserMessage
import dev.leonardo.octether.builder.anAssistantMessage
import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.MessageWithParts
import dev.leonardo.octether.domain.model.Part
import dev.leonardo.octether.domain.model.TimeInfo
import org.junit.Test

/**
 * Integration tests for SSE scroll viewport stability (the bug fixed in beta.445).
 *
 * Verifies the ChatMessageList behavior described in
 * `docs/research/sse-scroll-stability-iron-laws.md`:
 * - Height compensation tracks only the streaming message
 * - shouldCompensate resets when user returns to bottom
 * - Completed messages do not trigger compensation
 *
 * These tests use the FakeChatRepository's [messagesState] and [allPartsMapState]
 * flows — NOT [partsState] — because [MessageDataDelegate.messageListState] reads
 * from `getAllPartsMap()`, not `getParts()`.
 *
 * Behavioural assertions focus on what the user sees (text visibility) rather
 * than internal scroll offset, which Compose UI testing does not expose directly.
 */
@HiltAndroidTest
class ChatScrollStabilityTest : BaseChatTest() {

    // ============ Helpers ============

    /**
     * Set messages + parts in the fake repository.
     * [messageListState] reads from `messagesState` and `allPartsMapState`.
     */
    private fun setMessages(vararg entries: Pair<Message, List<Part>>) {
        fakeChat.messagesState.value = entries.map { it.first }
        fakeChat.allPartsMapState.value = entries.associate { it.first.id to it.second }
    }

    /** Create a user message with a single text part. */
    private fun userWithText(text: String, id: String): Pair<Message, List<Part>> {
        val msg = aUserMessage(text, id = id, sessionId = TEST_SESSION)
        val part = Part.Text(
            id = "part-$id",
            sessionId = TEST_SESSION,
            messageId = id,
            text = text
        )
        return msg to listOf(part)
    }

    /** Unwrap [MessageWithParts] into a (Message, List<Part>) pair. */
    private fun MessageWithParts.toPair(): Pair<Message, List<Part>> = info to parts

    /**
     * Simulate token growth: replace the text part of [messageId] with [newText].
     * Only modifies [allPartsMapState]; the Message info stays the same.
     */
    private fun growText(messageId: String, newText: String) {
        val currentMap = fakeChat.allPartsMapState.value.toMutableMap()
        currentMap[messageId] = listOf(
            Part.Text(
                id = "part-$messageId",
                sessionId = TEST_SESSION,
                messageId = messageId,
                text = newText
            )
        )
        fakeChat.allPartsMapState.value = currentMap
    }

    /** Generate a long filler string to simulate substantial token output. */
    private fun longText(marker: String, repeat: Int = 15): String =
        "$marker ${"This is streaming content that grows as tokens arrive. ".repeat(repeat)} End of $marker"

    // ============ Tests ============

    /**
     * Test 1: Streaming message grows, viewport stays at bottom.
     *
     * When the streaming message gets longer (tokens arrive) and the user is at
     * the bottom, the viewport should follow — the new content must be visible.
     */
    @Test
    fun streamingMessageGrows_viewportStaysAtBottom() {
        val userMsg = userWithText("What is Kotlin?", "u1")
        val asst = anAssistantMessage(streaming = true, id = "a1", sessionId = TEST_SESSION) {
            text("Kotlin is")
        }
        setMessages(userMsg, asst.toPair())
        renderChatScreen()

        // Verify initial content is displayed
        composeRule.onNodeWithText("Kotlin is", substring = true).assertIsDisplayed()

        // Simulate token growth
        growText("a1", "Kotlin is a cross-platform statically typed programming language by JetBrains.")
        composeRule.waitForIdle()

        // The grown content should be visible (viewport followed the streaming)
        composeRule.onNodeWithText("cross-platform", substring = true).assertIsDisplayed()
    }

    /**
     * Test 2: User scrolls away, streaming grows, viewport stays put.
     *
     * After the user scrolls up to read history, streaming token growth must NOT
     * yank the viewport back to the bottom.
     */
    @Test
    fun userScrollsAway_streamingGrows_viewportStaysPut() {
        // Create enough messages to make the list scrollable
        val entries = mutableListOf<Pair<Message, List<Part>>>()
        repeat(5) { i ->
            entries.add(userWithText("Question number $i about topic $i", "u$i"))
            entries.add(
                anAssistantMessage(streaming = false, id = "a$i", sessionId = TEST_SESSION) {
                    text("Answer $i: " + " filler ".repeat(20) + " done $i")
                }.toPair()
            )
        }
        // Last message: streaming
        val streamingId = "a-stream"
        entries.add(userWithText("Latest question here", "u-last"))
        entries.add(
            anAssistantMessage(streaming = true, id = streamingId, sessionId = TEST_SESSION) {
                text("Starting response")
            }.toPair()
        )
        setMessages(*entries.toTypedArray())
        renderChatScreen()

        // Scroll toward older messages.
        // reverseLayout=true: index 0 (newest) is at the BOTTOM, higher indices
        // (older) are at the TOP. swipeDown (finger: top→bottom) drags content
        // DOWN, revealing items that are visually above — i.e. older messages.
        // Two swipes ensure we reach the oldest entries in a tall list.
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        // Verify an earlier message is now visible (confirming we scrolled)
        composeRule.onNodeWithText("Question number 0", substring = true).assertIsDisplayed()

        // Grow the streaming message (simulate tokens arriving)
        growText(streamingId, longText("TOKEN_GROWTH"))
        composeRule.waitForIdle()

        // The earlier message should STILL be visible — viewport didn't jump to bottom
        composeRule.onNodeWithText("Question number 0", substring = true).assertIsDisplayed()
    }

    /**
     * Test 3: shouldCompensate resets when user returns to bottom.
     *
     * After scrolling away then back to bottom, the compensation must resume —
     * new streaming growth should keep the viewport at the bottom.
     */
    @Test
    fun shouldCompensateResetsWhenUserReturnsToBottom() {
        val entries = mutableListOf<Pair<Message, List<Part>>>()
        repeat(4) { i ->
            entries.add(userWithText("Earlier question $i", "pre-u$i"))
            entries.add(
                anAssistantMessage(streaming = false, id = "pre-a$i", sessionId = TEST_SESSION) {
                    text("Earlier answer $i with " + " padding ".repeat(15))
                }.toPair()
            )
        }
        val streamingId = "a-stream"
        entries.add(userWithText("Current question", "u-now"))
        entries.add(
            anAssistantMessage(streaming = true, id = streamingId, sessionId = TEST_SESSION) {
                text("Initial")
            }.toPair()
        )
        setMessages(*entries.toTypedArray())
        renderChatScreen()

        // Scroll toward older messages (swipeDown in reverseLayout, see test 2)
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Earlier question 0", substring = true).assertIsDisplayed()

        // Scroll back to bottom via the FAB
        composeRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeRule.waitForIdle()

        // Grow streaming content
        growText(streamingId, "After returning to bottom the content grew significantly " +
            "with many new tokens that should be visible now at the bottom of the screen.")
        composeRule.waitForIdle()

        // New content should be visible (compensation resumed)
        composeRule.onNodeWithText("After returning to bottom", substring = true).assertIsDisplayed()
    }

    /**
     * Test 4: streamingMsgId tracks last uncompleted assistant.
     *
     * With two assistant messages (first completed, second streaming), the
     * streaming message should render correctly. There is no visual streaming
     * indicator on individual message cards — streaming state only controls
     * the internal `layout{}` height compensation modifier.
     *
     * Ideal assertion: verify that only the second message has the compensation
     * layout modifier applied. This is not observable via Compose UI testing.
     * We assert both messages render with their content instead.
     */
    @Test
    fun streamingMsgIdTracksLastUncompletedAssistant() {
        val completedAsst = anAssistantMessage(streaming = false, id = "completed-1", sessionId = TEST_SESSION) {
            text("This is a completed response")
        }
        val streamingAsst = anAssistantMessage(streaming = true, id = "streaming-1", sessionId = TEST_SESSION) {
            text("This is still streaming")
        }
        setMessages(
            userWithText("First question", "u1"),
            completedAsst.toPair(),
            userWithText("Second question", "u2"),
            streamingAsst.toPair()
        )
        renderChatScreen()

        // Both messages should render with their content
        composeRule.onNodeWithText("This is a completed response", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("This is still streaming", substring = true).assertIsDisplayed()
    }

    /**
     * Test 5: streamingMsgId null when all completed.
     *
     * With two completed assistant messages, no streaming state is active.
     * Both messages should render normally.
     */
    @Test
    fun streamingMsgIdNullWhenAllCompleted() {
        val asst1 = anAssistantMessage(streaming = false, id = "done-1", sessionId = TEST_SESSION) {
            text("First completed answer")
        }
        val asst2 = anAssistantMessage(streaming = false, id = "done-2", sessionId = TEST_SESSION) {
            text("Second completed answer")
        }
        setMessages(
            userWithText("Question one", "u1"),
            asst1.toPair(),
            userWithText("Question two", "u2"),
            asst2.toPair()
        )
        renderChatScreen()

        composeRule.onNodeWithText("First completed answer", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Second completed answer", substring = true).assertIsDisplayed()
    }

    /**
     * Test 6: autoScrollEnabled resets on return to bottom.
     *
     * After scrolling away and returning to bottom, a NEW message arriving
     * should auto-scroll to keep it visible (autoScroll re-enabled).
     */
    @Test
    fun autoScrollEnabledResetsOnReturnToBottom() {
        val entries = mutableListOf<Pair<Message, List<Part>>>()
        repeat(4) { i ->
            entries.add(userWithText("Background question $i", "bg-u$i"))
            entries.add(
                anAssistantMessage(streaming = false, id = "bg-a$i", sessionId = TEST_SESSION) {
                    text("Background answer $i " + " fill ".repeat(15))
                }.toPair()
            )
        }
        val streamingId = "a-current"
        entries.add(userWithText("Current question", "u-now"))
        entries.add(
            anAssistantMessage(streaming = true, id = streamingId, sessionId = TEST_SESSION) {
                text("Current response")
            }.toPair()
        )
        setMessages(*entries.toTypedArray())
        renderChatScreen()

        // Scroll toward older messages (swipeDown in reverseLayout, see test 2)
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Background question 0", substring = true).assertIsDisplayed()

        // Return to bottom
        composeRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeRule.waitForIdle()

        // Add a brand-new message at the bottom
        val newMsgId = "a-newarrival"
        val currentMessages = fakeChat.messagesState.value.toMutableList()
        currentMessages.add(
            Message.Assistant(
                id = newMsgId,
                sessionId = TEST_SESSION,
                parentId = "parent-$newMsgId",
                time = TimeInfo(created = System.currentTimeMillis() + 1000)
            )
        )
        fakeChat.messagesState.value = currentMessages

        val currentParts = fakeChat.allPartsMapState.value.toMutableMap()
        currentParts[newMsgId] = listOf(
            Part.Text(
                id = "part-$newMsgId",
                sessionId = TEST_SESSION,
                messageId = newMsgId,
                text = "New arrival message that should auto scroll into view"
            )
        )
        fakeChat.allPartsMapState.value = currentParts
        composeRule.waitForIdle()

        // New message should be visible (autoScroll re-engaged)
        composeRule.onNodeWithText("New arrival message", substring = true).assertIsDisplayed()
    }

    /**
     * Test 7: Completed message height change does not trigger compensation.
     *
     * The `layout{}` compensation modifier is applied ONLY to the streaming
     * message (`isStreamingMsg == true`). Growing a completed message's content
     * should NOT cause the viewport to shift.
     *
     * Approach: scroll away from bottom, grow a completed message, then verify
     * the viewport position is unchanged (the earlier message we scrolled to is
     * still visible).
     *
     * Ideal assertion: compare scroll offset before and after the completed
     * message grows. Compose UI testing does not expose scroll offset, so we
     * verify content visibility stability instead.
     */
    @Test
    fun completedMessageHeightChangeDoesNotTriggerCompensation() {
        val completedId = "completed-tall"
        val streamingId = "streaming-current"
        val entries = mutableListOf<Pair<Message, List<Part>>>()

        // Older completed message (will grow later)
        entries.add(userWithText("Tell me about cats", "u1"))
        entries.add(
            anAssistantMessage(streaming = false, id = completedId, sessionId = TEST_SESSION) {
                text("Short cat answer")
            }.toPair()
        )

        // Filler messages to enable scrolling
        repeat(3) { i ->
            entries.add(userWithText("Filler question $i", "fill-u$i"))
            entries.add(
                anAssistantMessage(streaming = false, id = "fill-a$i", sessionId = TEST_SESSION) {
                    text("Filler answer $i " + " padding ".repeat(15))
                }.toPair()
            )
        }

        // Streaming message at the bottom
        entries.add(userWithText("Latest question", "u-last"))
        entries.add(
            anAssistantMessage(streaming = true, id = streamingId, sessionId = TEST_SESSION) {
                text("Streaming now")
            }.toPair()
        )

        setMessages(*entries.toTypedArray())
        renderChatScreen()

        // Scroll toward older messages (swipeDown in reverseLayout, see test 2)
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        // Verify we scrolled to see the older content
        composeRule.onNodeWithText("Filler answer 0", substring = true).assertIsDisplayed()

        // Grow ONLY the completed message (not the streaming one)
        growText(completedId, "Short cat answer. " + longText("COMPLETED_GROWTH", repeat = 20))
        composeRule.waitForIdle()

        // The filler message should still be visible — viewport didn't jump
        // If compensation had been applied to the completed message, the viewport
        // would have shifted, potentially scrolling the filler message out of view.
        composeRule.onNodeWithText("Filler answer 0", substring = true).assertIsDisplayed()
    }
}
