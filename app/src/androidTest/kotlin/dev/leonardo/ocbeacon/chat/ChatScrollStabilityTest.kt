package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.builder.aUserMessage
import dev.leonardo.ocbeacon.builder.anAssistantMessage
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import org.junit.Test

/**
 * SSE 滚动视口稳定性的集成测试（修复 beta.445 中 bug 的回归测试）。
 *
 * 验证 `docs/research/sse-scroll-stability-iron-laws.md` 中描述的
 * ChatMessageList 行为：
 * - 高度补偿只跟踪流式消息
 * - shouldCompensate 在用户回到底部时重置
 * - 已完成消息不会触发补偿
 *
 * 这些测试使用 FakeChatRepository 的 [messagesState] 和 [allPartsMapState]
 * flow —— 而非 [partsState] —— 因为 [MessageDataDelegate.messageListState]
 * 读取的是 `getAllPartsMap()`，而非 `getParts()`。
 *
 * 行为断言关注用户所见（文本可见性），而非内部滚动偏移，因为 Compose UI
 * 测试不直接暴露滚动偏移。
 */
@HiltAndroidTest
class ChatScrollStabilityTest : BaseChatTest() {

    // ============ 辅助方法 ============

    /**
     * 在 fake repository 中设置 messages + parts。
     * [messageListState] 读取 `messagesState` 和 `allPartsMapState`。
     */
    private fun setMessages(vararg entries: Pair<Message, List<Part>>) {
        fakeChat.messagesState.value = entries.map { it.first }
        fakeChat.allPartsMapState.value = entries.associate { it.first.id to it.second }
    }

    /** 创建一个带单个文本 part 的 user 消息。 */
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

    /** 将 [MessageWithParts] 拆解为 (Message, List<Part>) 对。 */
    private fun MessageWithParts.toPair(): Pair<Message, List<Part>> = info to parts

    /**
     * 模拟 token 增长：将 [messageId] 的文本 part 替换为 [newText]。
     * 仅修改 [allPartsMapState]；Message info 保持不变。
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

    /** 生成长填充字符串，以模拟大量 token 输出。 */
    private fun longText(marker: String, repeat: Int = 15): String =
        "$marker ${"This is streaming content that grows as tokens arrive. ".repeat(repeat)} End of $marker"

    // ============ 测试用例 ============

    /**
     * 测试 1：流式消息增长时，视口保持在底部。
     *
     * 当流式消息变长（token 到达）且用户位于底部时，视口应当跟随 ——
     * 新内容必须可见。
     */
    @Test
    fun streamingMessageGrows_viewportStaysAtBottom() {
        val userMsg = userWithText("What is Kotlin?", "u1")
        val asst = anAssistantMessage(streaming = true, id = "a1", sessionId = TEST_SESSION) {
            text("Kotlin is")
        }
        setMessages(userMsg, asst.toPair())
        renderChatScreen()

        // 验证初始内容已显示
        composeRule.onNodeWithText("Kotlin is", substring = true).assertIsDisplayed()

        // 模拟 token 增长
        growText("a1", "Kotlin is a cross-platform statically typed programming language by JetBrains.")
        composeRule.waitForIdle()

        // 增长后的内容应当可见（视口跟随流式内容）
        composeRule.onNodeWithText("cross-platform", substring = true).assertIsDisplayed()
    }

    /**
     * 测试 2：用户滚离后流式增长，视口保持不动。
     *
     * 用户向上滚动阅读历史后，流式 token 增长绝不能把视口拽回底部。
     */
    @Test
    fun userScrollsAway_streamingGrows_viewportStaysPut() {
        // 创建足够多的消息使列表可滚动
        val entries = mutableListOf<Pair<Message, List<Part>>>()
        repeat(5) { i ->
            entries.add(userWithText("Question number $i about topic $i", "u$i"))
            entries.add(
                anAssistantMessage(streaming = false, id = "a$i", sessionId = TEST_SESSION) {
                    text("Answer $i: " + " filler ".repeat(20) + " done $i")
                }.toPair()
            )
        }
        // 最后一条消息：流式
        val streamingId = "a-stream"
        entries.add(userWithText("Latest question here", "u-last"))
        entries.add(
            anAssistantMessage(streaming = true, id = streamingId, sessionId = TEST_SESSION) {
                text("Starting response")
            }.toPair()
        )
        setMessages(*entries.toTypedArray())
        renderChatScreen()

        // 向更早的消息方向滚动。
        // reverseLayout=true：索引 0（最新）在底部，更高索引（更早）
        // 在顶部。swipeDown（手指从上到下）将内容向下拖动，揭示视觉上
        // 位于上方的条目 —— 即更早的消息。
        // 两次滑动确保在长列表中滚动到最早的条目。
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        // 验证一条较早的消息现已可见（确认我们滚动成功）
        composeRule.onNodeWithText("Question number 0", substring = true).assertIsDisplayed()

        // 增长流式消息（模拟 token 到达）
        growText(streamingId, longText("TOKEN_GROWTH"))
        composeRule.waitForIdle()

        // 较早的消息应当仍然可见 —— 视口没有跳到底部
        composeRule.onNodeWithText("Question number 0", substring = true).assertIsDisplayed()
    }

    /**
     * 测试 3：用户回到底部时 shouldCompensate 重置。
     *
     * 滚离后回到底部，补偿必须恢复 —— 新的流式增长应让视口保持在底部。
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

        // 向更早的消息方向滚动（reverseLayout 中的 swipeDown，见测试 2）
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Earlier question 0", substring = true).assertIsDisplayed()

        // 通过 FAB 滚动回底部
        composeRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeRule.waitForIdle()

        // 增长流式内容
        growText(streamingId, "After returning to bottom the content grew significantly " +
            "with many new tokens that should be visible now at the bottom of the screen.")
        composeRule.waitForIdle()

        // 新内容应当可见（补偿已恢复）
        composeRule.onNodeWithText("After returning to bottom", substring = true).assertIsDisplayed()
    }

    /**
     * 测试 4：streamingMsgId 跟踪最后一条未完成的 assistant 消息。
     *
     * 当存在两条 assistant 消息（第一条已完成、第二条流式）时，流式消息
     * 应当正确渲染。单条消息卡片上没有可视的流式指示器 —— 流式状态仅
     * 控制内部的 `layout{}` 高度补偿修饰符。
     *
     * 理想断言：验证仅第二条消息应用了补偿 layout 修饰符。这无法通过
     * Compose UI 测试观察。我们改为断言两条消息都渲染出各自内容。
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

        // 两条消息都应渲染出各自内容
        composeRule.onNodeWithText("This is a completed response", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("This is still streaming", substring = true).assertIsDisplayed()
    }

    /**
     * 测试 5：所有消息都完成时 streamingMsgId 为 null。
     *
     * 当存在两条已完成的 assistant 消息时，没有流式状态处于活跃。
     * 两条消息都应正常渲染。
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
     * 测试 6：回到底部时 autoScrollEnabled 重置。
     *
     * 滚离后回到底部，新消息到达时应自动滚动以保持可见
     * （autoScroll 重新启用）。
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

        // 向更早的消息方向滚动（reverseLayout 中的 swipeDown，见测试 2）
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Background question 0", substring = true).assertIsDisplayed()

        // 回到底部
        composeRule.onNodeWithContentDescription("Scroll to bottom").performClick()
        composeRule.waitForIdle()

        // 在底部添加一条全新消息
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

        // 新消息应当可见（autoScroll 重新接合）
        composeRule.onNodeWithText("New arrival message", substring = true).assertIsDisplayed()
    }

    /**
     * 测试 7：已完成消息的高度变化不触发补偿。
     *
     * `layout{}` 补偿修饰符仅应用于流式消息（`isStreamingMsg == true`）。
     * 增长一条已完成消息的内容不应导致视口偏移。
     *
     * 方法：从底部滚离，增长一条已完成消息，然后验证视口位置未变
     * （我们滚动到的那条较早消息仍然可见）。
     *
     * 理想断言：比较已完成消息增长前后的滚动偏移。Compose UI 测试不
     * 暴露滚动偏移，因此我们改为验证内容可见性的稳定性。
     */
    @Test
    fun completedMessageHeightChangeDoesNotTriggerCompensation() {
        val completedId = "completed-tall"
        val streamingId = "streaming-current"
        val entries = mutableListOf<Pair<Message, List<Part>>>()

        // 较早的已完成消息（稍后会增长）
        entries.add(userWithText("Tell me about cats", "u1"))
        entries.add(
            anAssistantMessage(streaming = false, id = completedId, sessionId = TEST_SESSION) {
                text("Short cat answer")
            }.toPair()
        )

        // 填充消息以使列表可滚动
        repeat(3) { i ->
            entries.add(userWithText("Filler question $i", "fill-u$i"))
            entries.add(
                anAssistantMessage(streaming = false, id = "fill-a$i", sessionId = TEST_SESSION) {
                    text("Filler answer $i " + " padding ".repeat(15))
                }.toPair()
            )
        }

        // 底部的流式消息
        entries.add(userWithText("Latest question", "u-last"))
        entries.add(
            anAssistantMessage(streaming = true, id = streamingId, sessionId = TEST_SESSION) {
                text("Streaming now")
            }.toPair()
        )

        setMessages(*entries.toTypedArray())
        renderChatScreen()

        // 向更早的消息方向滚动（reverseLayout 中的 swipeDown，见测试 2）
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        // 验证我们已滚动到能看到较早内容
        composeRule.onNodeWithText("Filler answer 0", substring = true).assertIsDisplayed()

        // 仅增长已完成消息（不增长流式消息）
        growText(completedId, "Short cat answer. " + longText("COMPLETED_GROWTH", repeat = 20))
        composeRule.waitForIdle()

        // 填充消息应当仍然可见 —— 视口没有跳动
        // 如果对已完成消息应用了补偿，视口本会偏移，
        // 可能把填充消息滚出可视范围。
        composeRule.onNodeWithText("Filler answer 0", substring = true).assertIsDisplayed()
    }
}
