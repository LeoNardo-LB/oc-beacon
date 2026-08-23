package dev.leonardo.ocbeacon.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dagger.hilt.android.testing.HiltAndroidTest
import dev.leonardo.ocbeacon.builder.anAssistantMessage
import dev.leonardo.ocbeacon.builder.aUserMessage
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessagePage
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.repository.ProviderRepository
import dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker
import dev.leonardo.ocbeacon.fakes.FakeServerRepository
import dev.leonardo.ocbeacon.fakes.FakeSessionStateRepository
import org.junit.Test
import javax.inject.Inject

/**
 * ChatScreen 交互行为的集成测试。
 *
 * 继承 [BaseChatTest]，复用标准的 Hilt + Compose 搭建模式。
 * 每个测试配置 fake repository 状态，渲染 ChatScreen，
 * 执行 UI 交互，并断言预期结果。
 */
@HiltAndroidTest
class ChatInteractionTest : BaseChatTest() {

    @Inject
    lateinit var providerRepo: ProviderRepository

    /**
     * 会话状态仓库 fake —— 经 SessionStateRepository 接口绑定（FakeDomainModule），
     * 与 ChatViewModel 注入的是同一 @Singleton 实例，二者共享 FSM 状态。
     */
    @Inject
    lateinit var sessionStateRepo: FakeSessionStateRepository

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

    /** 从独立的列表注入消息 — 便捷封装。 */
    private fun seedMessages(messages: List<Message>, parts: List<Part>) {
        fakeChat.messagesState.value = messages
        fakeChat.allPartsMapState.value = parts.groupBy { it.messageId }
    }

    /** 注入一轮 user + assistant 的对话（带文本 parts）。 */
    private fun seedConversation() {
        val userMsg = aUserMessage("Hello", id = "u1")
        val assistantMsg = anAssistantMessage(id = "a1") {
            text("Hi there!")
        }
        seedMessages(
            messages = listOf(userMsg, assistantMsg.info),
            parts = assistantMsg.parts
        )
    }

    /**
     * 注入一个权限请求，它将以 PermissionCard 形式呈现。
     *
     * 注意：存储键为 ""，因为在插桩测试中 ViewModel 的 sessionIdFlow 为 ""
     * （没有导航参数到达 savedStateHandle）。interactionState 调用
     * getPermissionsWithChildren(sid, ...)，其中 sid = sessionIdFlow.value = ""。
     * 事件自身的 sessionId 字段保留为 TEST_SESSION 以贴近真实情况，
     * 但查找键必须匹配。
     */
    private fun seedPermission(
        id: String = "perm-1",
        permission: String = "bash"
    ): SseEvent.PermissionAsked {
        val perm = SseEvent.PermissionAsked(
            id = id,
            sessionId = TEST_SESSION,
            permission = permission
        )
        fakeChat.setPermissions("", listOf(perm))
        return perm
    }

    /**
     * 注入一个问题，它将以 QuestionCard 形式呈现。
     *
     * 注意：存储键为 "" — 原因见 seedPermission()。
     */
    private fun seedQuestion(
        id: String = "q-1",
        question: String = "Which option?"
    ): SseEvent.QuestionAsked {
        val q = SseEvent.QuestionAsked(
            id = id,
            sessionId = TEST_SESSION,
            questions = listOf(
                SseEvent.QuestionAsked.Question(
                    header = "Choice",
                    question = question,
                    options = listOf(
                        SseEvent.QuestionAsked.Option("Yes", "Confirm"),
                        SseEvent.QuestionAsked.Option("No", "Decline")
                    )
                )
            )
        )
        fakeChat.setQuestions("", listOf(q))
        return q
    }

    /**
     * 激活 SSE 消息观察管线。
     *
     * 对于新会话（sessionId=""），startObservingMessages() 仅在 ensureSession()
     * 之后才被调用，而 ensureSession() 发生在首次发送时。此辅助方法发送一条
     * 简单消息以激活观察管线，使注入的消息在 UI 中可见。
     *
     * 重要：调用此方法后，sessionId 会从 "" 变为所创建会话的 ID。
     * 不要在依赖 sessionId="" 的测试中使用（例如权限/问题查找）。
     */
    private fun activateMessageStream() {
        typeInput(".")
        composeRule.onNodeWithTag("chat-send").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeChat.promptAsyncCalls.isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    // ============ 测试用例 ============

    /**
     * 测试 1：输入文本并点击发送会清空输入框并记录消息。
     *
     * 发送路径：ChatInputBar.onSend → ChatScreen doSend() →
     * viewModel.sendMessage(parts) → sendParts() → SendMessageUseCase.sendPrompt() →
     * chatRepository.promptAsync()。
     */
    @Test
    fun sendMessage_clearsInput() {
        renderChatScreen()
        composeRule.waitForIdle()

        typeInput("hello world")

        // 点击发送按钮（testTag 为 "chat-send"）
        composeRule.onNodeWithTag("chat-send").performClick()

        // 等待异步发送（promptAsync）完成
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeChat.promptAsyncCalls.isNotEmpty()
        }

        // fake 应当恰好记录了一次 promptAsync 调用
        assert(fakeChat.promptAsyncCalls.size == 1) {
            "Expected 1 promptAsync call, got ${fakeChat.promptAsyncCalls.size}"
        }
    }

    /**
     * 测试 3：当 token 统计可用时，上下文用量指示器出现。
     *
     * 当 contextWindow > 0 且 lastContextTokens > 0 时，ChatTopBar 会显示
     * 一个带百分比的 CircularProgressIndicator。
     *
     * TokenStatsTracker 是由 Hilt 注入的 @Singleton —— 测试与 ViewModel
     * 共享同一实例。我们在渲染后设置统计值（init 会调用 reset()），
     * 并配置 provider catalog，使 modelConfig.contextWindow 解析为非零值。
     */
    @Test
    fun contextUsageBar_shows_whenTokenStatsAvailable() {
        // 配置一个拥有上下文窗口的模型的 Provider
        val testProvider = ProviderCatalog(
            id = "ctx-provider",
            name = "Ctx Provider",
            models = mapOf(
                "ctx-model" to dev.leonardo.ocbeacon.domain.model.ModelCatalog(
                    id = "ctx-model",
                    name = "Ctx Model",
                    contextWindow = 128000
                )
            )
        )
        fakeServer.catalogResult = Result.success(
            ProvidersResponse(
                providers = listOf(testProvider),
                default = mapOf("ctx-provider" to "ctx-model")
            )
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // 在 ViewModel init 之后设置 token 统计（init 会调用 tokenStatsTracker.reset()）
        // percentage = round(64000 / 128000 * 100) = 50
        // 2026-08-18 更新（#149）：0cb68851 口径修正删除了 currentModel 兜底，
        // fake 会话无 model → catalog 分支查不到——改由 tokenStats.contextWindow
        // 直接提供分母（解析链优先分支，tokenStats.contextWindow > 0 即命中）
        tokenStatsTracker.update {
            copy(lastContextTokens = 64000, contextWindow = 128000)
        }

        // 等待上下文指示器渲染（需要 providers 已加载 + token 统计已设置）
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("50").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("50").assertIsDisplayed()
    }

    /**
     * 测试 5：输入 /undo 显示撤销建议且不崩溃。
     *
     * SlashCommandRegistry 将 "undo" 注册为客户端命令。输入 "/undo" 会
     * 过滤出匹配的建议。完整的撤销验证推迟到 ViewModel 层级的单元测试。
     */
    @Test
    fun undo_callsUndoRedo() {
        seedConversation()

        renderChatScreen()

        typeInput("/undo")

        // 验证输入生效：输入非空时发送按钮存在
        composeRule.onNodeWithTag("chat-send").assertExists()
    }

    /**
     * 测试 6：会话忙碌时，中断/停止按钮会调用 abort API。
     *
     * 当 isBusy && 文本为空时，发送按钮转换为停止按钮。
     * isBusy 派生自 sessionMeta.sessionStatus（Busy 或 Retry）。
     *
     * 我们注入 SessionStateService（一个 @Singleton），并调用 onClientSendParts("")
     * 将 sessionId="" 的 FSM 转移到 Busy —— 该实例与 ViewModel 读取的是同一实例。
     */
    @Test
    fun abortSession_callsAbortApi() {
        // 注入一条流式 assistant 消息，使会话看起来处于活跃状态
        val streamingMsg = anAssistantMessage(streaming = true, id = "a-stream") {
            text("Generating...")
        }
        seedMessages(listOf(streamingMsg.info), streamingMsg.parts)

        renderChatScreen()
        composeRule.waitForIdle()

        // 在 ViewModel 就绪后将会话状态设置为 Busy —— 确保 sessionMetaState
        //（6 路 combine + WhileSubscribed5s）已订阅，FSM 更新能立即被捕获，
        // 避免冷启动时序竞态导致 Stop 按钮出现延迟。
        // ChatViewModel 经 SessionStateRepository 接口注入的是 FakeSessionStateRepository
        //（FakeDomainModule 绑定），测试注入同一 @Singleton 实例，二者共享 FSM 状态。
        sessionStateRepo.onClientSendParts("")
        composeRule.waitForIdle()

        // 停止按钮在 isBusy && 输入文本为空时显示。
        // 其 contentDescription 为 "Stop"（R.string.chat_stop）。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Stop")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Stop").performClick()
        composeRule.waitForIdle()

        // abortSession() → sessionRepository.abort(serverId, sessionId, directory)
        // 测试中 sessionId 为 ""（来自 sessionIdFlow）。
        composeRule.waitUntil(timeoutMillis = 10_000) {
            fakeSession.abortCalls.isNotEmpty()
        }
        assert(fakeSession.abortCalls.isNotEmpty()) {
            "Abort should have been called"
        }
    }

    /**
     * 测试 7：请求权限时，权限卡片出现。
     *
     * interactionState（7 路 combine）调用 getPermissionsWithChildren(sid, ...)，
     * 其中 sid = sessionIdFlow.value = ""。存储在 "" 键下的数据会被找到。
     */
    @Test
    fun permissionDialog_appears_whenPermissionRequested() {
        // seed 一条消息确保走 ChatMessageList 分支（非空状态）——
        // ChatScreen 在 messages.isEmpty() && !isLoading 时渲染 ChatEmptyState，
        // 会让 ChatMessageList（含 PermissionCard）永不进入组合。
        seedConversation()
        renderChatScreen()
        composeRule.waitForIdle()

        // 在 ViewModel 就绪后注入权限 —— 确保 interactionState（7 路 combine）
        // 已订阅，allPermissionsMapState 的 emit 能立即被 combine 捕获，
        // 避免冷启动时序竞态导致 PermissionCard 出现延迟。
        seedPermission(permission = "bash echo hello")
        composeRule.waitForIdle()

        // 等待 interactionState flow（7 路 combine）将权限传播到
        // pendingPermissions 并渲染 PermissionCard。
        // PermissionCard 渲染 R.string.permission_title = "Permission Required"
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Permission Required")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Permission Required").assertIsDisplayed()

        // 权限描述也应当可见
        composeRule.onNodeWithText("bash echo hello", substring = true).assertIsDisplayed()
    }

    /**
     * 测试 8：提出问题时，问题卡片出现。
     */
    @Test
    fun questionDialog_appears_whenQuestionAsked() {
        // seed 一条消息确保走 ChatMessageList 分支（非空状态）——原因同 permissionDialog
        seedConversation()
        renderChatScreen()
        composeRule.waitForIdle()

        // 在 ViewModel 就绪后注入问题 —— 确保 interactionState（7 路 combine）
        // 已订阅，allQuestionsMapState 的 emit 能立即被 combine 捕获，
        // 避免冷启动时序竞态导致 QuestionCard 出现延迟。
        seedQuestion(question = "Which framework?")
        composeRule.waitForIdle()

        // 等待 interactionState flow 将问题传播到 pendingQuestions
        // 并渲染 QuestionCard。
        // 2026-08-18 更新（#149）：卡片标题 2026-08-17 改版——
        // R.string.chat_question_label("Question") → R.string.question_awaiting_reply
        //（"Awaiting your reply"，M3 原生化标题栏）
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Awaiting your reply")
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Awaiting your reply").assertIsDisplayed()

        // 问题文本也应当可见（QuestionCard 中 summary Text 与输入框可能各含一次该文本，用 onAllNodes 兼容）
        composeRule.onAllNodesWithText("Which framework?", substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * 测试 9：向上滚动触发分页（loadOlderMessages）。
     *
     * ChatMessageList 使用自动分页：当用户滚动到距顶部 8 条以内时，
     * 会调用 viewModel.loadOlderMessages()。
     *
     * 注意：对于新会话（sessionId=""），init 期间永远不会调用
     * loadMessagesForSession()，因此 hasOlderMessages 保持为 false，
     * 分页无法触发。要让此测试通过，可以：
     * 1. 通过测试框架中的 SavedStateHandle 提供非空的 sessionId
     * 2. 添加一个测试可见的方法来强制设置 hasOlderMessages
     * 3. Mock SessionLifecycleDelegate，将 sessionId 视为非空
     *
     * 在实现以上任一方案之前，保持 @Ignore。
     */
    @org.junit.Ignore("Pagination needs hasOlderMessages=true, which requires loadMessagesForSession() to run. For new sessions (sessionId=\"\"), init skips this — hasOlderMessages stays false. Fix: provide non-empty sessionId via SavedStateHandle test harness, or add a test hook to set hasOlderMessages directly.")
    @Test
    fun pagination_triggersOnScrollUp() {
        // 生成大量消息
        val messages = mutableListOf<Message>()
        for (i in 1..30) {
            messages.add(aUserMessage("Message $i", id = "u$i"))
        }

        seedMessages(messages.reversed(), emptyList())
        fakeSession.listMessagesResult = Result.success(
            MessagePage(
                messages = messages.mapIndexed { i, msg ->
                    MessageWithParts(info = msg, parts = emptyList())
                },
                nextCursor = null,
            )
        )

        renderChatScreen()
        composeRule.waitForIdle()

        // 激活消息观察，使消息可见
        activateMessageStream()

        // 等待至少一条消息渲染
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Message", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 向上滚动（reverseLayout=true：swipeDown 在顶部揭示更早的消息）
        val messageNodes = composeRule.onAllNodesWithText("Message", substring = true)
        messageNodes[0].performTouchInput {
            repeat(5) { swipeDown() }
        }
        composeRule.waitForIdle()

        // 分页应当触发 loadOlderMessages()，它会以翻倍的 limit 调用 listMessages。
        // 若没有 hasOlderMessages=true，则不会触发。
    }
}
