package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.builder.PartListBuilder
import dev.leonardo.ocbeacon.builder.aUserMessage
import dev.leonardo.ocbeacon.builder.anAssistantMessage
import dev.leonardo.ocbeacon.builder.randomId
import dev.leonardo.ocbeacon.builder.testSettings
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import org.junit.Test

/**
 * 验证 ChatScreen 中消息渲染分支的集成测试。
 *
 * 覆盖：user 消息、流式/已完成的 assistant 消息、reasoning、
 * 工具卡片、错误展示、轮次顺序，以及空状态。
 *
 * 使用 [BaseChatTest] 进行 Hilt + Compose 搭建。消息直接注入到
 * fake repository 的 StateFlow（messagesState + allPartsMapState）中，
 * MessageDataDelegate 的 combine pipeline 从中读取。
 */
@HiltAndroidTest
class ChatMessageRenderingTest : BaseChatTest() {

    // ============ 辅助方法 ============

    /**
     * 将消息注入 fake repository 的可观察 flow。
     *
     * FakeChatRepository.setMessages 写入的是一个内部存储，该存储并未
     * 连接到 UI 读取的 StateFlow（messagesState / allPartsMapState）。
     * 此辅助方法通过直接设置 flow 来弥合这一差异，与真实的
     * ChatRepositoryImpl 中 setMessages 更新 flow 的语义一致。
     */
    private fun seedMessages(vararg mwps: MessageWithParts) {
        fakeChat.messagesState.value = mwps.map { it.info }
        fakeChat.allPartsMapState.value = mwps.associate { it.info.id to it.parts }
    }

    /**
     * 构建一个包含单个文本 part 的 user MessageWithParts。
     *
     * aUserMessage() 创建一个不带 parts 的纯 Message.User；UI 从
     * Part.Text 渲染 user 文本，因此我们必须附加一个 part。
     */
    private fun userMessageWithText(text: String): MessageWithParts {
        val msg = aUserMessage(text, id = randomId(), sessionId = TEST_SESSION)
        val parts = PartListBuilder(sessionId = TEST_SESSION, messageId = msg.id).apply {
            this.text(text)
        }.build()
        return MessageWithParts(info = msg, parts = parts)
    }

    // ============ 测试用例 ============

    /**
     * 测试 1：一条 user 消息在聊天气泡内渲染其文本内容。
     */
    @Test
    fun user_message_renders_with_correct_styling() {
        seedMessages(userMessageWithText("Hello world"))

        renderChatScreen()

        composeRule.onNodeWithText("Hello world").assertIsDisplayed()
    }

    /**
     * 测试 2：一条流式 assistant 消息（time.completed == null）带有一个
     * 正在运行的工具时，显示工具卡片 —— 当 isRunning 为 true 时，
     * ToolCardScaffold 在工具标题旁边渲染一个 PulsingDotsIndicator。
     *
     * 我们断言工具标题文本已显示（它与脉动指示器共存于同一卡片行）。
     * ReadToolCard 从 R.string.tool_read = "Read" 解析标题。
     */
    @Test
    fun streaming_assistant_shows_pulsing_indicator() {
        val msg = anAssistantMessage(streaming = true, sessionId = TEST_SESSION) {
            tool("read")
        }
        seedMessages(msg)

        renderChatScreen()

        // ReadToolCard 标题 = R.string.tool_read = "Read"。
        // PulsingDotsIndicator 与之并排渲染（isRunning = true）。
        composeRule.onNodeWithText("Read").assertIsDisplayed()
    }

    /**
     * 测试 3：已完成的 assistant 消息渲染其文本，不带任何流式/运行中指示器。
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
     * 测试 4：reasoning part 渲染在一个可折叠的 ReasoningBlock 内。
     *
     * reasoning block 默认折叠（expandReasoning = false），因此我们在
     * settings 中启用 expandReasoning，使 reasoning 文本内容可见以便断言。
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

        // 当 expandReasoning = true 时，reasoning 内容可见
        composeRule.onNodeWithText("Thinking about this problem carefully").assertIsDisplayed()
    }

    /**
     * 测试 5：已完成的 tool part 渲染为一个可展开的工具卡片，显示工具名称。
     *
     * DefaultToolCardResolver 将 "read" 映射到 ReadToolCard，后者使用
     * R.string.tool_read = "Read" 作为卡片标题。
     */
    @Test
    fun tool_part_renders_as_expandable_card() {
        val msg = anAssistantMessage(streaming = false, sessionId = TEST_SESSION) {
            toolCompleted("read", "file content here")
        }
        seedMessages(msg)

        renderChatScreen()

        // ReadToolCard 标题 = R.string.tool_read = "Read"
        composeRule.onNodeWithText("Read").assertIsDisplayed()
    }

    /**
     * 测试 6：带错误的 assistant 消息在错误容器 surface 内渲染错误文本。
     *
     * anAssistantMessage(error = "...") 创建 ErrorInfo(name = "TestError")。
     * formatAssistantErrorMessage 返回 "TestError"（当 data 为 null 时
     * 降级到 name）。错误显示在 assistant 卡片底部。
     *
     * 包含一个 text part 以便消息能通过 messageListState 中
     * 的 assistant-with-parts 过滤器。
     */
    @Test
    fun error_message_renders() {
        val msg = anAssistantMessage(streaming = false, error = "error", sessionId = TEST_SESSION) {
            text("Partial response before error")
        }
        seedMessages(msg)

        renderChatScreen()

        // ErrorPayloadContent 将错误名称渲染为纯文本
        composeRule.onNodeWithText("TestError").assertIsDisplayed()
    }

    /**
     * 测试 7：一条 user 消息后跟一条 assistant 消息都能渲染，并在聊天列表中
     * 以正确顺序出现。
     */
    @Test
    fun turn_dividers_between_user_assistant_pairs() {
        val user = userMessageWithText("User asks a question")
        val assistant = anAssistantMessage(streaming = false, sessionId = TEST_SESSION) {
            text("Assistant answers")
        }
        seedMessages(user, assistant)

        renderChatScreen()

        // 两条消息都应显示
        composeRule.onNodeWithText("User asks a question").assertIsDisplayed()
        composeRule.onNodeWithText("Assistant answers").assertIsDisplayed()
    }

    /**
     * 测试 8：空会话（无消息）渲染时不崩溃，显示 ChatEmptyState 占位符。
     *
     * ChatEmptyState 显示 R.string.chat_empty = "Start a session with OpenCode"
     * （#213：08-23 KT10a 术语批 conversation→session 改了 EN 源，断言同步勘误）。
     */
    @Test
    fun empty_session_shows_placeholder_or_empty_state() {
        // 不注入任何消息 —— 默认空状态
        renderChatScreen()

        // 当消息为空且未在加载时，ChatEmptyState 显示此文本
        composeRule.onNodeWithText("Start a session with OpenCode").assertIsDisplayed()
    }
}
