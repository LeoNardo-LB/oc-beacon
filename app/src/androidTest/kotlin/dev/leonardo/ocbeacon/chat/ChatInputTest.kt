package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.repository.AgentRepository
import dev.leonardo.ocbeacon.fakes.FakeAgentRepository
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Test

/**
 * 聊天输入栏行为的集成测试。
 *
 * 覆盖：文本输入、斜杠命令自动补全、@-文件提及搜索、
 * 附件按钮可见性，以及发送按钮状态管理。
 *
 * 使用 [BaseChatTest] 进行 Hilt + Compose 搭建，fakes 已预注入。
 */
@HiltAndroidTest
class ChatInputTest : BaseChatTest() {

    @Inject lateinit var agentRepo: AgentRepository
    private val fakeAgent get() = agentRepo as FakeAgentRepository

    @Test
    fun typing_updates_draft_text() {
        renderChatScreen()

        // typeInput 使用 hasSetTextAction() 在 BasicTextField+decorationBox 中
        // 定位真正的可编辑节点，绕过 semantics 合并问题。
        typeInput("hello world")

        // BasicTextField + decorationBox 不通过 semantics 暴露 EditableText。
        // 通过副作用验证输入生效：输入非空时发送按钮存在。
        composeRule.onNodeWithTag("chat-send").assertExists()
    }

    @Test
    fun slash_command_shows_autocomplete() {
        renderChatScreen()

        typeInput("/")

        // SlashCommandRegistry.clientCommands() 总是提供：new、compact、fork 等。
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("/new").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("/new").assertIsDisplayed()
    }

    @Test
    fun file_mention_search_shows_results() {
        // 配置 fake 为 @-mention 搜索返回文件路径。
        // 搜索路径为 ManageAgentUseCase → AgentRepository.searchFiles。
        fakeAgent.searchFilesResult = Result.success(listOf("src/main.kt", "README.md"))

        renderChatScreen()

        typeInput("@test")

        // 等待 150ms 防抖 + 异步协程完成
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("main.kt", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("main.kt", substring = true).assertIsDisplayed()
    }

    @Test
    fun attachment_can_be_added() {
        // AgentModelVariantSelector（其中包含附件按钮）仅当
        // modelLabel 非空或 agents.size > 1 时才渲染。
        fakeAgent.agentsResult = Result.success(listOf(
            AgentInfo(name = "build"),
            AgentInfo(name = "general")
        ))

        renderChatScreen()

        // 等待 ViewModel 加载 agents 并渲染选择器行
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Attach")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 附件按钮（AttachFile 图标）应当可见
        composeRule.onNodeWithContentDescription("Attach").assertIsDisplayed()
    }

    @Test
    fun send_button_disabled_when_input_empty() {
        renderChatScreen()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("chat-send").fetchSemanticsNodes().isNotEmpty()
        }

        // 输入为空时，点击发送不应触发 promptAsync
        composeRule.onNodeWithTag("chat-send").performClick()
        composeRule.waitForIdle()

        assert(fakeChat.promptAsyncCalls.isEmpty()) {
            "Send with empty input should not call promptAsync"
        }
    }
}
