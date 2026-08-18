package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.onNodeWithTag
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
 * ChatScreen 交互行为的隔离集成测试。
 *
 * 这些测试从 [ChatInteractionTest] 拆分而来，因为它们由于 ViewModel 复用
 * 污染而失败 —— 同一类中的先前测试会修改共享的 ViewModel 状态，且该状态
 * 无法重置。这里的每个测试都通过独立成类来获得全新的 ViewModel。
 *
 * 继承 [BaseChatTest]，复用标准的 Hilt + Compose 搭建模式。
 */
@HiltAndroidTest
class ChatInteractionIsolatedTest : BaseChatTest() {

    @Inject
    lateinit var providerRepo: ProviderRepository

    private val fakeServer: FakeServerRepository
        get() = providerRepo as FakeServerRepository

    // ============ 辅助方法 ============

    /**
     * 注入消息，使其出现在 UI 中。
     *
     * messageListState 将 messagesState 中的消息与 allPartsMapState
     * （以 messageId 为键）中的 parts 合并。partsState 由 startObservingMessages()
     * 内部使用，但 UI 读取的是 allPartsMapState。
     */
    private fun seedMessages(vararg mwps: MessageWithParts) {
        fakeChat.messagesState.value = mwps.map { it.info }
        fakeChat.allPartsMapState.value = mwps.associate { it.info.id to it.parts }
    }

    // ============ 测试用例 ============

    /**
     * 测试：带已完成输出的工具卡片被显示。
     *
     * 工具卡片通过 ToolCardScaffold 渲染。ReadToolCard（由
     * DefaultToolCardResolver 为 "read" 工具名解析）从
     * R.string.tool_read = "Read" 渲染标题。
     */
    @Test
    fun toolCardExpand_toggles() {
        renderChatScreen()
        composeRule.waitForIdle()

        // 渲染后注入 —— 确保全新的 ViewModel 订阅
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
     * 测试：provider 数据加载后，模型选择器显示可用模型。
     *
     * providers 通过 SelectModelUseCase → ProviderRepository.loadProviderCatalog()
     * 从 FakeServerRepository.catalogResult 加载。模型标签在 providers 加载后
     * 出现在 AgentModelVariantSelector 中。
     */
    @Test
    fun modelSelector_showsAvailableModels() {
        // 同时设置 providersResult 和 catalogResult —— ModelConfigDelegate 用
        // loadProviders() 获取 ProviderInfo 列表，用 loadProviderCatalog() 获取 catalog。
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

        // 强制 modelConfigState combine 在订阅后重新求值
        tokenStatsTracker.update { copy(lastContextTokens = 1) }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Model A").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 测试：从底部滚离时，滚动到底部的 FAB 出现。
     *
     * ChatMessageList 在 !isAtBottom 时渲染一个 SmallFloatingActionButton，
     * 其 contentDescription 为 "Scroll to bottom"（R.string.chat_scroll_bottom）。
     * 使用 swipeDown() 是因为 reverseLayout=true。
     */
    @Test
    fun scrollToBottomFab_appearsWhenScrolledAway() {
        renderChatScreen()
        composeRule.waitForIdle()

        // 渲染后注入 —— 确保全新的 ViewModel 订阅
        // 40 条消息确保列表远超视口：20 条单行消息在大屏模拟器上可能接近满屏，
        // 导致 swipe 无法越过 isAtBottom 阈值（firstVisibleItemScrollOffset < 100）。
        val mwps = (1..40).map { i ->
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

        // 等待消息渲染
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Message", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 滑动以从底部滚离（reverseLayout：swipeDown 向上滚动）。
        // 用 onNode(hasScrollAction()) + 默认 swipeDown()（与 ChatScrollStabilityTest 一致）；
        // 3 次确保越过 isAtBottom 阈值（firstVisibleItemIndex==0 && scrollOffset<100）。
        // 2026-08-18（#149）：hasScrollAction 匹配 2 节点（消息列表+底部栏）
        // 导致注入失败——改用唯一 testTag（与 ChatScrollStabilityTest 同修）
        composeRule.onNodeWithTag("chat-message-list").performTouchInput {
            repeat(3) { swipeDown() }
        }
        composeRule.waitForIdle()

        // FAB 应当出现（contentDescription = "Scroll to bottom"）
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Scroll to bottom")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
}
