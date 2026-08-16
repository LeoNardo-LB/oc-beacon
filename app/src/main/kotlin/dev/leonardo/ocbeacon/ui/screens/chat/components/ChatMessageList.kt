package dev.leonardo.ocbeacon.ui.screens.chat.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.StepProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.ChatViewModel
import dev.leonardo.ocbeacon.ui.screens.chat.InteractionState
import dev.leonardo.ocbeacon.ui.screens.chat.MessageListState
import dev.leonardo.ocbeacon.ui.screens.chat.SessionMetaState
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.PermissionCard
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCard
import dev.leonardo.ocbeacon.ui.screens.chat.components.AlwaysConfirmDialog
import dev.leonardo.ocbeacon.ui.screens.chat.util.snapToBottom
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.computeRenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.util.JumpTarget
import dev.leonardo.ocbeacon.ui.screens.chat.util.computeTurnGroups
import dev.leonardo.ocbeacon.ui.screens.chat.util.extractJumpTargets
import dev.leonardo.ocbeacon.ui.screens.chat.util.findCurrentAnchorTimestamp
import dev.leonardo.ocbeacon.ui.screens.chat.util.findCurrentQuestionMsgId
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatAssistantErrorMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.util.MessageFingerprints
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.LocalCopyFeedback
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 转后台 synthetic 系统提示的服务器已知模板变体（命中 → 分割线渲染）。
 * #136（D2-L55）：原先单个硬编码模板——服务器改文案即静默失效；
 * 现改为变体列表，任一命中即视为转后台提示。
 * 服务器再次改文案导致特性失效时，在此追加新变体（并在 backlog 登记）。
 */
private val BACKGROUND_SYNTHETIC_MARKERS = listOf(
    "User requested that active blocking work be moved to the background",
    "active blocking work be moved to the background",
    "active blocking work was moved to the background",
)

/** 判断 synthetic 消息文本是否为服务器"转后台"系统提示（供分割线渲染分支与单测使用）。
 * 大小写不敏感——服务器模板可能调整大小写/时态，任一变体命中即视为转后台提示。 */
internal fun isBackgroundMoveSynthetic(text: String): Boolean =
    BACKGROUND_SYNTHETIC_MARKERS.any { text.contains(it, ignoreCase = true) }

/**
 * 跳转预渲染注册表（根治方案 2026-08-12）：
 * 消息组件（MessageCardUser）为跳转目标消息创建 MarkdownState 后注册到此表，
 * scrollToDisplayItem 从表里取目标 state 并 await 解析完成（State.Success）——
 * 用"渲染完成信号"精确等待，替代尺寸轮询（内容展示前渲染完）。
 */
val LocalMarkdownStateRegistry = androidx.compose.runtime.staticCompositionLocalOf<MutableMap<String, MarkdownState>> {
    mutableMapOf()
}

/**
 * 2026-08-13 观测：跳转目标气泡（Card）的真实屏幕顶 y——
 * 用户反馈"气泡上边缘距视口顶还有十多个像素"，直接测量定位。
 */
internal object JumpBubbleObserve {
    var targetMsgId: String? = null
    var bubbleTopY = -1f
    /** 2026-08-13：定位收敛完成标记（Compose state——MessageCardUser 门控显示）：
     * 收敛完成（位置精确 + 列表尺寸稳定）前目标保持透明——显示即最终状态，
     * 无"空气泡→突然增高"的视觉突变。 */
    var settled by androidx.compose.runtime.mutableStateOf(false)
}


/**
 * 主会话和子会话消息列表共用的 composable。
 *
 * 结构：PullToRefreshBox > LazyColumn（待处理问题/权限、revert 横幅、
 * 消息项）+ 滚动到底部 FAB + 流式消息卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageList(
    listState: LazyListState,
    messageState: MessageListState,
    sessionMeta: SessionMetaState,
    interaction: InteractionState,
    rawMessages: List<ChatMessage>,
    displayItems: List<Pair<Int, ChatMessage>>,
    isAtBottom: Boolean,
    isAmoled: Boolean,
    messageSpacing: Dp,
    isMainSession: Boolean,
    coroutineScope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: Context,
    clipboard: Clipboard,
    keyboardController: SoftwareKeyboardController?,
    viewModel: ChatViewModel,
    navigateToChildSession: (String) -> Unit,
    onOpenFile: (filePath: String) -> Unit,
    onForceScrollToBottom: () -> Unit,
    showQuickNavigate: Boolean,
    onQuickNavigateDismiss: () -> Unit,
    agents: List<dev.leonardo.ocbeacon.domain.model.AgentInfo> = emptyList(),
    onAgentClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // #137（D2-L50）：工具卡片复制反馈统一 Snackbar 通道（ToolCardScaffold 原用 Toast）
    CompositionLocalProvider(LocalCopyFeedback provides {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
        }
    }) {
    // turnGroups 缓存（v6）：消息 id 序列未变时复用上次 Map，消除流式期间
    // 每 48ms 全量重建（~2000 entry/轮）的分配压力（GC 卡顿根因之一）。
    // 安全前提：renderableTurns 的 miss 分支（流式/新消息）用最新 msg 引用替换
    // turn 内同 id 的旧引用 —— 流式消息永不冻结（历史回归 37d9a6ac 的教训）。
    // 此处仅按结构（id 序列）缓存；内容（parts）变化不重建 Map —— 同 id 的
    // 旧引用由 miss 分支修正；另一读取点（isStreamingMsg 判断）只比较 id，
    // 不受旧引用影响。
    val turnGroupsSigRef = remember { intArrayOf(Int.MIN_VALUE) }
    val turnGroupsRef = remember { arrayOfNulls<Map<Int, List<ChatMessage>>>(1) }
    val turnGroups: Map<Int, List<ChatMessage>> = remember(rawMessages) {
        val sig = MessageFingerprints.messagesSignature(rawMessages)
        val cached = turnGroupsRef[0]
        if (cached != null && sig == turnGroupsSigRef[0]) {
            cached
        } else {
            turnGroupsSigRef[0] = sig
            computeTurnGroups(rawMessages).also { turnGroupsRef[0] = it }
        }
    }

    // 来自 ChatRepository 的实时状态 —— 领域类型。
    // 置于 renderableTurns 之前：其缓存开关（activeTools 是否为空）需要在此计算。
    val currentSessionId = viewModel.sessionId
    val toolProgress by viewModel.chatRepositoryExposed.getActiveToolProgressForSession(currentSessionId).collectAsStateWithLifecycle(initialValue = null)
    val stepProgress by viewModel.chatRepositoryExposed.getStepProgressForSession(currentSessionId).collectAsStateWithLifecycle(initialValue = null)
    val compactionState by viewModel.chatRepositoryExposed.getCompactionStateForSession(currentSessionId).collectAsStateWithLifecycle(initialValue = null)
    val activeTools = toolProgress.orEmpty().map { 
        ToolProgressInfo(callId = it.callId, partId = it.partId, tool = it.tool, status = it.status, progress = it.progress, title = it.title)
    }
    val currentStep = stepProgress?.let { 
        StepProgressInfo(step = it.step, agent = it.agent, model = it.model)
    }
    val currentCompaction = compactionState?.let { 
        CompactionStateInfo(isActive = it.isActive, reason = it.reason)
    }

    // 快速导航双向加载状态（直接从 viewModel 收集，避免侵入 messageListState combine 管道）
    val isLoadingAround by viewModel.isLoadingAround.collectAsStateWithLifecycle(initialValue = false)
    val hasNewerMessages by viewModel.hasNewerMessages.collectAsStateWithLifecycle(initialValue = false)
    val isLoadingNewer by viewModel.isLoadingNewer.collectAsStateWithLifecycle(initialValue = false)

    // 判断当前哪条消息在流式输出 —— 仅基于 completed 时间戳，
    // 而非 sessionMeta.isStreaming。后者是在 668384e3 中加入的，
    // 但可能无法反映活跃流式状态（生产环境观察到 stuck false），
    // 导致 streamingMsgId=null 并禁用所有高度补偿 → 视口被拖到底部。
    // v360 只用 completed 时间戳，工作正常。不要再加 takeIf(sessionMeta)。
    // 直接以 rawMessages 为 key —— 与 turnGroups 同理，stale 引用
    // 会冻结流式判定（回归：37d9a6ac 重新引入 msgStructKey）。
    val streamingMsgId = remember(rawMessages) {
        rawMessages.lastOrNull {
            it.isAssistant && it.message.time.completed == null
        }?.message?.id
    }

    // 预计算 assistant 显示项的全部渲染数据。
    // 单个 remember 块 —— 仅在 rawMessages/displayItems 变化时运行，而非组合期间。
    // 缓存优化（2026-08）：流式期间数据层每 48ms 全量重建消息列表（即使只有最后
    // 一条在变），若 renderableTurns 全量重算 → 新实例 → @Immutable 相等性失效 →
    // LazyColumn 可见 item 全量重组 → 滚动卡顿。这里按消息 id + 内容指纹缓存：
    // 指纹覆盖流式追加（Text/Reasoning 尾部）、工具输出注入（Running/Completed
    // output 尾部）与消息级时间/错误字段；内容未变的消息复用缓存实例 → 重组只落
    // 在变化消息上。相比早期"activeTools 非空整体禁用缓存"的实现（工具运行期间
    // 每 48ms 全量重算 → 工具调用时滑动卡顿），工具活跃时其他消息仍命中缓存。
    val renderableCache = remember { HashMap<String, Pair<Int, RenderableTurn>>() }
    val renderableTurns: List<RenderableTurn?> = remember(rawMessages, displayItems, turnGroups) {
        val streamingId = streamingMsgId
        val result = displayItems.map { (rawIndex, msg) ->
            if (!msg.isAssistant) return@map null
            val fingerprint = MessageFingerprints.messageFingerprint(msg)
            val cached = renderableCache[msg.message.id]
            if (cached != null && cached.first == fingerprint && msg.message.id != streamingId) {
                cached.second
            } else {
                // 引用修正：miss（流式/新消息）时，turn 内当前消息必须使用最新
                // 引用（turnGroups 缓存 Map 中可能是上一轮旧引用 → 冻结在首个
                // token，历史回归 37d9a6ac）
                val turnMsgs = turnGroups[rawIndex]
                    ?.map { if (it.message.id == msg.message.id) msg else it }
                    ?: listOf(msg)
                val isTurnLast = rawIndex == rawMessages.lastIndex ||
                    rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true
                computeRenderableTurn(
                    turnMessages = turnMsgs,
                    currentMessage = msg,
                    isTurnLast = isTurnLast,
                    formatError = ::formatAssistantErrorMessage,
                ).also { renderableCache[msg.message.id] = fingerprint to it }
            }
        }
        // 择机清理：只保留当前展示的 assistant 消息（分页移除/会话切换后不泄漏）
        renderableCache.keys.retainAll(
            displayItems.mapNotNull { (_, m) -> if (m.isAssistant) m.message.id else null }
        )
        // 注意：不能复用上一轮列表引用 —— displayItems 结构变化（如乐观消息插入）时
        // 旧列表索引错位会导致 ASSISTANT 消息拿到 null 崩溃。元素复用缓存实例已足够
        // 让 Compose 通过相等性跳过重组，仅每轮多一个轻量 List 引用拷贝。
        result
    }

    // 以 streamingMsgId 作为 key，流式消息变化（新消息
    // 或完成）时状态重置。这比 heightMap + 会话级清除更简单、更正确。
    val compensateState = remember(streamingMsgId) { CompensateState() }
    val toolCompensateState = remember(streamingMsgId) { CompensateState() }

    // 跟踪用户是否已滚离底部。
    // 重要：同时以 isScrollInProgress 和 isAtBottom 作为 key。
    // 以 isAtBottom 作为 key 是必需的，这样当用户通过非拖拽方式
    // （fling 惯性、SSE 内容推送）回到底部时 shouldCompensate 会重置为 false。
    // 没有它，shouldCompensate 在底部保持 true，每个 SSE token 都会触发
    // requestScrollToItemNoCancel → 视口抖动。
    // 这种双 key 形式是 beta.360 验证过的行为；不要把
    // isAtBottom 从 key 中移除（参见 docs/research/sse-scroll-stability-iron-laws.md）。
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) {
            compensateState.shouldCompensate = true
        } else if (isAtBottom) {
            compensateState.shouldCompensate = false
        }
    }

    // 快速导航：Room 全量 user 消息列表（抽屉打开时异步查询一次）。
    // 数据源为 Room 热表（≤1000 条全量 user），覆盖内存窗口（rawMessages ~30 条）外的更早历史。
    val noTextPlaceholder = stringResource(R.string.no_text)
    var jumpTargets by remember { mutableStateOf<List<JumpTarget>>(emptyList()) }
    var jumpTargetsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(showQuickNavigate, currentSessionId) {
        if (showQuickNavigate) {
            jumpTargetsLoading = true
            jumpTargets = extractJumpTargets(viewModel.loadJumpTargets(), noTextPlaceholder)
            jumpTargetsLoading = false
        }
    }

    // 预计算：可嵌入 assistant 消息气泡的待处理提问（按 tool.messageId 匹配可见消息）
    val embeddedQuestionByMsgId: Map<String, SseEvent.QuestionAsked> = remember(
        interaction.pendingQuestions, displayItems
    ) {
        val visibleMsgIds = displayItems.mapNotNull { (_, msg) ->
            if (msg.isAssistant) msg.message.id else null
        }.toSet()
        interaction.pendingQuestions
            .filter { q -> q.tool?.messageId != null && q.tool.messageId in visibleMsgIds }
            .associateBy { it.tool!!.messageId }
    }
    // 未嵌入任何可见 assistant 消息的提问（保底独立显示）
    val unembeddedQuestions = remember(interaction.pendingQuestions, embeddedQuestionByMsgId) {
        val embeddedIds = embeddedQuestionByMsgId.values.map { it.id }.toSet()
        interaction.pendingQuestions.filter { it.id !in embeddedIds }
    }

    // LazyColumn 中 itemsIndexed 之前渲染的非消息项数量。
    // 必须与下面的条件 `item { ... }` 块保持一致（见横幅渲染）。
    val bannerCount = remember(
        sessionMeta.revert,
        currentCompaction,
        sessionMeta.sessionStatus,
        activeTools,
        currentStep,
        unembeddedQuestions,
        interaction.pendingPermissions,
    ) {
        (if (sessionMeta.revert != null) 1 else 0) +
        (if (currentCompaction != null && currentCompaction.isActive) 1 else 0) +
        (if (sessionMeta.sessionStatus is SessionStatus.Retry) 1 else 0) +
        (if (activeTools.isNotEmpty()) 1 else 0) +
        (if (currentStep != null) 1 else 0) +
        (if (unembeddedQuestions.isNotEmpty()) 1 else 0) +
        (if (interaction.pendingPermissions.isNotEmpty()) 1 else 0)
    }

    // 当前可见问题（msgId 驱动，与 Room 全量列表的 JumpTarget.msgId 匹配高亮）。
    // 2026-08-12 修复：基于 displayItems 显示序列（原 rawMessages 索引与显示序列
    // 不一致导致 currentMsgId 恒为 null——见 JumpTargetExtractor 注释）。
    val currentQuestionMsgId by remember(displayItems, bannerCount) {
        derivedStateOf { findCurrentQuestionMsgId(listState, displayItems, bannerCount) }
    }
    // 当前可见区域时间锚点（快速导航打开时降级定位用——见 QuickNavigateSheet）
    val currentAnchorTimestamp by remember(displayItems, bannerCount) {
        derivedStateOf { findCurrentAnchorTimestamp(listState, displayItems, bannerCount) }
    }

    // 高亮 key（3 秒后自动清除）—— scrollToDisplayItem / onLocateTask 共用。
    var highlightedTurnKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTurnKey) {
        if (highlightedTurnKey != null) {
            delay(3000)
            highlightedTurnKey = null
        }
    }

    // 2026-08-12 修复：跳转定位锁定——jumpToMessage 期间抑制 autoLoad
    //（loadOlder 自动补载会插入 older 批 → 目标被推下视口，用户反馈
    // "目标不在视口顶部"——logcat 实证 positioned 后被 auto-load 推走）。
    var jumpLockActive by remember { mutableStateOf(false) }

    // 2026-08-13 架构根治：渲染就绪信号注册表（统一信号层——预解析、就绪
    // 上报、awaitReady 消费；替代分散的 preParsed map + 轮询）
    val renderReadiness = remember { RenderReadinessRegistry() }

    // 2026-08-13 架构根治（状态机）：跳转定位状态机——蒙版/门控/锁从状态派生
    //（单一真相源——消除 jumpLoading/settled/jumpLockActive 各自为政的竞态）。
    val jumpController = remember {
        JumpNavigationController(
            listState,
            renderReadiness,
            coroutineScope,
            resolveLazyIndex = { msgId ->
                displayItems.indexOfFirst { it.second.message.id == msgId }
                    .takeIf { it >= 0 }
                    ?.let { bannerCount + it }
            },
        )
    }
    val jumpPhase by jumpController.phase.collectAsStateWithLifecycle()
    val jumpLoading = jumpController.showMask

    // 快速导航异步定位：jumpToMessage 目标未加载时设此值，loadAround 完成后
    // 消息进入 displayItems → LaunchedEffect 重启 → 状态机跳转
    var pendingJumpTarget by remember { mutableStateOf<String?>(null) }

    // 2026-08-13 架构根治：jumpLock 解锁由状态机终点驱动（Displayed/Failed——
    // 定位结束才放行 autoLoad；不再靠旧流程末尾手动解锁）
    LaunchedEffect(jumpPhase) {
        if (jumpPhase is JumpPhase.Displayed || jumpPhase is JumpPhase.Failed) {
            delay(300)
            jumpLockActive = false
            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "jump: 状态机终点——autoLoad 解锁")
        }
    }

    // 2026-08-13 根治：跳转预渲染注册表——目标消息组件（MessageCardUser）
    // 注册其 MarkdownState，scrollToDisplayItem await 解析完成信号。
    val mdRegistry = remember { mutableMapOf<String, MarkdownState>() }

    // 2026-08-13 观测：LazyColumn 视口顶边的屏幕 y（判断气泡顶是否超出 topBar）
    var listTopY by remember { mutableStateOf(-1f) }

    // ===== 状态机版跳转（2026-08-13 架构根治——旧 scrollToDisplayItem 已删除）=====
    // 定位决策全部在 JumpNavigationController 状态机（Preparing→Measuring→Settling→
    // Displayed/Failed）；蒙版/门控从状态派生（单一真相源）；目标一次定位到最终位置
    //（不移动→不回收→不重测→无"重复乱跳"根因）。
    fun jumpToMessage(msgId: String) {
        jumpLockActive = true
        val displayItemIndex = displayItems.indexOfFirst { it.second.message.id == msgId }
        // 2026-08-13 架构根治（Mikepenz 官方 Parse-ahead + 状态机）：
        // 文本必须与渲染归一化一致（normalizeForRender）——否则解析 AST 与
        // 渲染内容不同（换行差异 → 高度 214 vs 331）。preParse 在状态机内触发。
        val jumpText = displayItems.getOrNull(displayItemIndex)
            ?.second?.parts?.filterIsInstance<Part.Text>()
            ?.firstOrNull { it.text.isNotBlank() }?.text
        // 2026-08-12 修复：目标在 displayItems 但 parts 为空（Room 有消息但
        // parts 未 upsert 到内存——重启后内存只加载最新窗口）→ loadAround 加载。
        val targetHasRenderableContent = displayItemIndex >= 0 &&
            displayItems[displayItemIndex].second.parts.any { it is Part.Text && it.text.isNotBlank() }
        if (BuildConfig.DEBUG) {
            AppLogger.d("ChatPaging", "jumpToMessage: msgId=${msgId.take(12)} inDisplay=$displayItemIndex renderable=$targetHasRenderableContent")
        }
        if (targetHasRenderableContent) {
            // 状态机跳转：一次定位 + 蒙版/门控从状态派生
            jumpController.jumpTo(
                msgId,
                bannerCount + displayItemIndex,
                jumpText?.let { normalizeForRender(it, isUser = true) },
            )
            onQuickNavigateDismiss()
        } else {
            // 未加载或 parts 为空：触发异步定位加载，等待消息进入 displayItems 后滚动
            pendingJumpTarget = msgId
            onQuickNavigateDismiss()
            coroutineScope.launch { viewModel.loadAround(msgId) }
        }
    }
    // 「定位发起卡片」支持：点击完成通知卡片上的定位按钮 → 查找发起卡片
    //（task/subagent 工具的 metadata.sessionId/jobId）→ 滚动 + 3 秒高亮。
    val onLocateTask: (String) -> Unit = { targetSessionId ->
        val targetIndex = displayItems.indexOfFirst { (rawIndex, m) ->
            val turnMsgs = turnGroups[rawIndex] ?: listOf(m)
            turnMsgs.any { tm ->
                tm.parts.any { p ->
                    p is Part.Tool &&
                        (p.tool == "task" || p.tool == "subagent") &&
                        extractToolSubagentSessionId(p) == targetSessionId
                }
            }
        }
        if (targetIndex >= 0) {
            val lazyIndex = bannerCount + targetIndex
            val (rawIndex, targetMsg) = displayItems[targetIndex]
            val targetMsgId = targetMsg.message.id
            // 2026-08-13 架构根治：onLocateTask 复用状态机（同一定位流程——一次
            // 定位 + 蒙版/门控 + 收敛；assistant 目标无预解析，直接测量）
            jumpLockActive = true
            jumpController.jumpToTask(lazyIndex, targetMsgId)
            highlightedTurnKey = if (targetMsg.isUser) {
                "u_${targetMsg.message.id}"
            } else {
                "t_${rawMessages.getOrNull(rawIndex + 1)?.message?.id ?: "head"}"
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.chat_locate_task_not_found))
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            var showAlwaysDialog by remember { mutableStateOf<SseEvent.PermissionAsked?>(null) }

            // 自动分页：用户距顶部 8 项以内时触发加载。
            // 取代 PullToRefreshBox —— 无缝，无需手动手势。
            //
            // 关键：remember 必须以 messageState 为 key。没有这个 key，
            // derivedStateOf 会捕获初始 messageState（其中 hasOlderMessages=false）
            // 并且当 loadMessagesForSession() 将 hasOlderMessages 设为 true 时
            // 永远看不到更新。这是进入会话后分页静默失败的根源。
            //
            // reverseLayout=true 下：索引 0 = 视觉底部（最新消息），
            // firstVisibleItemIndex = 视觉顶部（最旧可见消息）。
            // "距顶部" = total - firstVisibleItemIndex。不可用 lastOrNull()
            //（那是最新消息，恒等于底部 → 进入会话即无限翻页拉网络）。
            //
            // 2026-08-10 修复：不再依赖 isScrollInProgress——用户滑到顶"停住"
            // 时 isScrollInProgress=false 导致不触发（"看似滑到顶但有更多内容"）。
            // 改用 LaunchedEffect(hasOlderMessages, isLoadingOlder, autoLoadPaused) + snapshotFlow：
            // 距顶 <=8 即触发（无论是否滚动中）；加载完成 isLoadingOlder 翻转
            // → LaunchedEffect 重启 → 重新监听布局 → 若仍距顶近则自动续载。
            // 进入会话不触发：firstVisible=0（视觉底部），total-firstVisible 大。
            // 防风暴：autoLoadPaused（连续失败 3 次）→ 停止自动续载；collect 触发前
            // 查询 delegate 的退避等待（autoLoadWaitMillis）——失败后按 500ms 指数退避重试。
            LaunchedEffect(
                messageState.hasOlderMessages,
                messageState.isLoadingOlder,
                messageState.autoLoadPaused,
                jumpLockActive,
            ) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "ChatPaging",
                        "auto-load effect restart: hasOlder=${messageState.hasOlderMessages} isLoading=${messageState.isLoadingOlder} paused=${messageState.autoLoadPaused}"
                    )
                }
                if (messageState.hasOlderMessages && !messageState.isLoadingOlder && !messageState.autoLoadPaused && !jumpLockActive) {
                    snapshotFlow { listState.layoutInfo }
                        .map { layoutInfo ->
                            // 2026-08-12 修复：视觉顶部 = 可见项中 index 最大
                            //（reverseLayout：最旧在最上、index 最大）——原实现用
                            // visibleItemsInfo.firstOrNull()（index 最小 = 视觉底部），
                            // 用户滑到顶部时底部项 index 仍远离 total → nearTop 永不
                            // 满足 → 更旧消息永远加载不了（用户反馈"滚动不上去了"，
                            // 视口卡在 11:44——该处已是已加载最旧但 loadOlder 未触发）。
                            val topVisible = layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
                            val total = layoutInfo.totalItemsCount
                            val nearTop = total - topVisible <= 8
                            // 内容不足一屏（最后可见项未填满视口）时也触发。
                            // 主会话初始加载经 displayItems 过滤后可能仅剩 13 条——不足一屏时
                            // 用户无法滚动（firstVisible 恒 0），永达不到 nearTop → 历史加载
                            // 静默失效（用户反馈"向上滑动加载历史消息也没有"）。
                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                            val contentDoesNotFillViewport = lastVisible == null ||
                                lastVisible.offset + lastVisible.size < layoutInfo.viewportEndOffset
                            if (BuildConfig.DEBUG && total > 0 && (nearTop || contentDoesNotFillViewport)) {
                                // 低频诊断：触发条件附近打印（滚动高频段不刷屏）
                                AppLogger.d("ChatPaging", "auto-load probe: topVisible=$topVisible total=$total nearTop=$nearTop fillsViewport=${!contentDoesNotFillViewport}")
                            }
                            nearTop || contentDoesNotFillViewport
                        }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            val waitMs = viewModel.autoLoadWaitMillis()
                            if (waitMs > 0) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load backoff wait ${waitMs}ms before retry")
                                delay(waitMs)
                            }
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load triggered (hasOlder=true)")
                            viewModel.loadOlderMessages()
                        }
                }
            }

            // 自动分页（更新方向）：用户距视觉底部 8 项以内时触发加载更新消息。
            // 仅在 loadAround 定位后激活（hasNewerMessages=true）；正常会话状态
            //（已在最新）hasNewerMessages=false，永不触发。
            //
            // reverseLayout=true：firstVisible（visibleItemsInfo 最低索引）接近 0
            // = 视觉底部（更新方向）。与 older 的 `total - firstVisible <= 8`（视觉顶部）
            // 对称。无更多更新数据时服务器返回不足一页 → FSM 置 hasNewer=false → 停止。
            LaunchedEffect(
                hasNewerMessages,
                isLoadingNewer,
                jumpLockActive,
            ) {
                if (hasNewerMessages && !isLoadingNewer && !jumpLockActive) {
                    snapshotFlow { listState.layoutInfo }
                        .map { layoutInfo ->
                            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                            if (BuildConfig.DEBUG && firstVisible <= 12) {
                                AppLogger.d("ChatPaging", "nearBottom probe: firstVisible=$firstVisible")
                            }
                            firstVisible <= 8
                        }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load newer triggered (nearBottom=true, hasNewer=true)")
                            viewModel.loadNewerMessages()
                        }
                }
            }

            // 快速导航异步定位：jumpToMessage 目标未加载时设此值，loadAround 完成后
            // 消息进入 displayItems → 此 LaunchedEffect 重启（displayItems 是 key）→
            // 状态机跳转（2026-08-13 架构根治——旧 scrollToDisplayItem 已删除）。
            LaunchedEffect(pendingJumpTarget, displayItems) {
                val target = pendingJumpTarget ?: return@LaunchedEffect
                val idx = displayItems.indexOfFirst { it.second.message.id == target }
                if (idx >= 0) {
                    // 等 3 帧（约 50ms）让组合/布局稳定后再跳转（loadAround 批量插入）
                    withFrameNanos { }
                    withFrameNanos { }
                    withFrameNanos { }
                    pendingJumpTarget = null
                    val text = displayItems[idx].second.parts
                        .filterIsInstance<Part.Text>()
                        .firstOrNull { it.text.isNotBlank() }?.text
                    jumpLockActive = true
                    jumpController.jumpTo(
                        target,
                        bannerCount + idx,
                        text?.let { normalizeForRender(it, isUser = true) },
                    )
                }
            }

            // loadAround 失败保护：加载结束（isLoadingAround=false）但目标未进入
            // displayItems（pendingJumpTarget 仍悬空）→ 清除并 Snackbar 提示，避免悬死。
            // 2026-08-13 修复：**延迟 500ms 再查**——loadAround 完成（isLoadingAround=false）
            // 与 displayItems 更新（内存热视图 → UI）存在协程调度时序差——立即查会
            // 误判"未找到"→ 提前清 pendingJumpTarget → 目标已加载但永不跳转
            //（用户反馈"只会第一次点击时加载"）。
            LaunchedEffect(isLoadingAround) {
                if (!isLoadingAround && pendingJumpTarget != null) {
                    delay(500)
                    val target = pendingJumpTarget
                    val found = displayItems.indexOfFirst { it.second.message.id == target } >= 0
                    if (!found) {
                        pendingJumpTarget = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_locate_task_not_found))
                        }
                    }
                }
            }

            // 2026-08-12 根治：预渲染注册表注入——消息组件（MessageCardUser）
            // 通过 LocalMarkdownStateRegistry 注册目标的 MarkdownState；
            // 2026-08-13 状态机注入（门控读 LocalJumpController）。
                        androidx.compose.runtime.CompositionLocalProvider(
                LocalMarkdownStateRegistry provides mdRegistry,
                LocalRenderReadiness provides renderReadiness,
                LocalJumpController provides jumpController,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) }
                        .onGloballyPositioned { coords ->
                            listTopY = coords.positionInWindow().y
                        },
                    contentPadding = PaddingValues(
                        start = SpacingTokens.MD.dp,
                        top = SpacingTokens.SM.dp,
                        end = SpacingTokens.MD.dp,
                        bottom = SpacingTokens.SM.dp
                    ),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(messageSpacing)
                ) {
                    // reverseLayout=true：先声明的项渲染在底部。
                    // 视觉顺序（上→下）：最旧消息 → 最新消息 → revert → pending。
                    // 声明顺序自下而上：pending（底部）→ 消息（顶部）。

                    // Revert 横幅
                    if (sessionMeta.revert != null) {
                        item(key = "revert_banner") {
                            RevertBanner(onRedo = {
                                viewModel.redoMessage { ok ->
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                        )
                                    }
                                }
                                onForceScrollToBottom()
                            })
                        }
                    }

                    // Compaction 横幅
                    if (currentCompaction != null && currentCompaction.isActive) {
                        item(key = "compaction_banner") {
                            CompactionBanner(state = currentCompaction)
                        }
                    }

                    // Retry 横幅 —— 会话处于 Retry 状态时显示
                    val retryStatus = sessionMeta.sessionStatus
                    if (retryStatus is SessionStatus.Retry) {
                        item(key = "retry_banner") {
                            RetryBanner(retryStatus)
                        }
                    }

                    // 工具进度卡片（带漂移补偿）
                    if (activeTools.isNotEmpty()) {
                        item(key = "tool_progress") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(
                                            constraints.copy(maxHeight = Constraints.Infinity)
                                        )
                                        val realHeight = placeable.height
                                        val delta = realHeight - toolCompensateState.lastHeight
                                        if (compensateState.shouldCompensate && toolCompensateState.lastHeight > 0 && delta > 0) {
                                            LazyListReflection.requestScrollToItemNoCancel(
                                                listState,
                                                listState.firstVisibleItemIndex,
                                                listState.firstVisibleItemScrollOffset + delta
                                            )
                                        }
                                        toolCompensateState.lastHeight = realHeight
                                        layout(placeable.width, realHeight) {
                                            placeable.placeRelative(0, 0)
                                        }
                                    }
                            ) {
                                activeTools.forEach { toolInfo ->
                                    ToolProgressCard(toolInfo = toolInfo)
                                }
                            }
                        }
                    } else {
                        // 无活跃工具时重置
                        toolCompensateState.lastHeight = 0
                    }

                    // 步骤进度指示器
                    if (currentStep != null) {
                        item(key = "step_progress") {
                            StepProgressIndicator(stepInfo = currentStep)
                        }
                    }

                    // 待处理问题（未嵌入消息气泡的保底显示）——一次显示一个（最旧优先）
                    unembeddedQuestions.firstOrNull()?.let { question ->
                        item(key = "question_${question.id}") {
                            QuestionCard(
                                question = question,
                                positionLabel = if (unembeddedQuestions.size > 1) "1/${unembeddedQuestions.size}" else null,
                                onSubmit = { answers ->
                                    viewModel.replyToQuestion(question.id, answers)
                                    onForceScrollToBottom()
                                },
                                onReject = {
                                    viewModel.rejectQuestion(question.id)
                                    onForceScrollToBottom()
                                }
                            )
                        }
                    }

                    // 待处理权限 —— 一次显示一个（最旧优先）
                    interaction.pendingPermissions.firstOrNull()?.let { permission ->
                        item(key = "perm_${permission.id}") {
                            PermissionCard(
                                permission = permission,
                                positionLabel = if (interaction.pendingPermissions.size > 1) "1/${interaction.pendingPermissions.size}" else null,
                                onOnce = {
                                    viewModel.replyToPermission(permission.id, "once")
                                    onForceScrollToBottom()
                                },
                                onAlways = { showAlwaysDialog = permission },
                                onReject = {
                                    viewModel.replyToPermission(permission.id, "reject")
                                    onForceScrollToBottom()
                                }
                            )
                        }
                    }

                    // 聊天消息：displayItems 已经是新的在前（降序）。
                    // reverseLayout=true 将索引 0（最新）渲染在底部。
                    // 视觉结果：最旧在顶部，最新在底部。
                    itemsIndexed(
                        displayItems,
                        key = { _, (rawIndex, msg) ->
                            // 稳定的基于 turn 的 key：分页从同一 turn
                            // 加载更多消息时防止项被销毁（代表消息
                            // 会变化，但 turn 身份保持不变）。
                            // #103（M-8）：key 锚点改为 turn 组首条消息 id——
                            // 原最新 turn 用固定 "head" fallback，流式期间新消息
                            // 到达改变 rawIndex+1 边界 → key 变化 → 整气泡销毁重建
                            //（含 rememberMarkdownState 重解析）
                            if (msg.isUser) "u_${msg.message.id}"
                            else "t_${turnGroups[rawIndex]?.firstOrNull()?.message?.id ?: msg.message.id}"
                        },
                        contentType = { _, item -> if (item.second.isUser) "user" else "assistant" }
                    ) { displayItemIndex, (rawIndex, msg) ->
                        // #103（M-8）：与 LazyColumn key 同锚点（turn 组首条消息 id）
                        val itemKey = if (msg.isUser) "u_${msg.message.id}"
                            else "t_${turnGroups[rawIndex]?.firstOrNull()?.message?.id ?: msg.message.id}"
                        val isStreamingMsg = (turnGroups[rawIndex] ?: listOf(msg)).any { it.message.id == streamingMsgId }
                        val itemModifier = if (isStreamingMsg) {
                            Modifier
                                .fillMaxWidth()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(maxHeight = Constraints.Infinity)
                                    )
                                    val realHeight = placeable.height
                                    val delta = realHeight - compensateState.lastHeight
                                    if (compensateState.shouldCompensate && compensateState.lastHeight > 0 && delta > 0) {
                                        LazyListReflection.requestScrollToItemNoCancel(
                                            listState,
                                            listState.firstVisibleItemIndex,
                                            listState.firstVisibleItemScrollOffset + delta
                                        )
                                    }
                                    compensateState.lastHeight = realHeight

                                    layout(placeable.width, realHeight) {
                                        placeable.placeRelative(0, 0)
                                    }
                                }
                        } else Modifier.fillMaxWidth()
                        // 定位发起卡片后的短暂高亮（3 秒后自动清除）
                        val isHighlighted = itemKey == highlightedTurnKey
                        Box(
                            modifier = itemModifier.then(
                                if (isHighlighted) {
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.SELECTED)
                                        )
                                } else Modifier
                            )
                        ) {
                        when {
                            msg.isAssistant -> {
                                // isTurnLast：下一条"非 synthetic"消息不是 assistant 才算 turn 尾。
                                // synthetic 通知嵌入 turn 内（2026-08-11），不阻挡统计栏。
                                val nextReal = rawMessages.subList(rawIndex + 1, rawMessages.size)
                                    .firstOrNull { !it.isSynthetic }
                                val isTurnLast = nextReal == null || !nextReal.isAssistant

                                // 嵌入式提问卡片：按 tool.messageId 匹配当前消息，
                                // 嵌入该消息的思考卡片（ReasoningBlock）内部渲染
                                val embeddedQ = embeddedQuestionByMsgId[msg.message.id]

                                MessageCard(
                                    role = MessageCardRole.ASSISTANT,
                                    renderableTurn = renderableTurns[displayItemIndex],
                                    currentMessage = msg,
                                    onViewSubSession = navigateToChildSession,
                                    onOpenFile = onOpenFile,
                                    isAmoled = isAmoled,
                                    isTurnLast = isTurnLast,
                                    isStreamingTurn = isStreamingMsg,
                                    agents = agents,
                                    onAgentClick = onAgentClick,
                                    onCopy = {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                        }
                                    },
                                    onLocateTask = onLocateTask,
                                    pendingQuestion = embeddedQ,
                                    onQuestionSubmit = { qId, answers ->
                                        viewModel.replyToQuestion(qId, answers)
                                        onForceScrollToBottom()
                                    },
                                    onQuestionReject = { qId ->
                                        viewModel.rejectQuestion(qId)
                                        onForceScrollToBottom()
                                    },
                                )
                            }
                            msg.isUser -> {
                                val chatMessage = msg

                                val isCompactionTrigger = chatMessage.parts.any { it is Part.Compaction }

                                if (isCompactionTrigger) {
                                    var showRevertDialog by remember { mutableStateOf(false) }

                                    if (showRevertDialog) {
                                        ConfirmDialog(
                                            title = stringResource(R.string.chat_revert_title),
                                            message = stringResource(R.string.chat_revert_message),
                                            confirmLabel = stringResource(R.string.chat_revert),
                                            onDismiss = { showRevertDialog = false },
                                            onConfirm = {
                                                showRevertDialog = false
                                                viewModel.revertMessage(chatMessage.message.id) { ok ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) context.getString(R.string.chat_messages_restored) else context.getString(R.string.chat_message_redo_failed)
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }

                                    @OptIn(ExperimentalFoundationApi::class)
                                    // 2026-08-15：压缩分割线升级为可展开卡片
                                    //（CompactionCard：分割线收起态 + 无边框轻量
                                    // 卡片摘要展开态——与 synthetic 通知卡片一致
                                    // 的视觉语言）；长按仍触发回退确认。
                                    Column(modifier = Modifier.combinedClickable(
                                        onClick = { },
                                        onLongClick = { showRevertDialog = true }
                                    )) {
                                        CompactionCard(
                                            summary = chatMessage.parts
                                                .filterIsInstance<Part.Compaction>()
                                                .firstOrNull()?.summary
                                        )
                                    }
                                    return@itemsIndexed
                                }

                                // 2026-08-13：转后台 synthetic 系统提示（服务器固定模板
                                // "User requested that active blocking work be moved to the
                                // background"）→ 分割线渲染（类似压缩分割线——简短提示
                                //「已移至后台」，丢弃服务器英文系统提示文本）
                                val isSyntheticMsg = chatMessage.message is Message.User &&
                                    (chatMessage.message as Message.User).role == "synthetic"
                                if (isSyntheticMsg) {
                                    val syntheticText = chatMessage.parts
                                        .filterIsInstance<Part.Text>()
                                        .joinToString("\n") { it.text }
                                    if (isBackgroundMoveSynthetic(syntheticText)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = SpacingTokens.XS.dp, horizontal = SpacingTokens.XXL.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                            )
                                            Text(
                                                text = stringResource(R.string.chat_moved_to_background),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                                modifier = Modifier.padding(horizontal = SpacingTokens.MD.dp)
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                            )
                                        }
                                        return@itemsIndexed
                                    }
                                }

                                MessageCard(
                                    role = if (chatMessage.message is Message.User &&
                                        (chatMessage.message as Message.User).role == "synthetic"
                                    ) {
                                        // #67：synthetic 系统通知（后台任务/subagent 完成注入）用独立样式
                                        MessageCardRole.SYNTHETIC
                                    } else {
                                        MessageCardRole.USER
                                    },
                                    currentMessage = chatMessage,
                                    isQueued = chatMessage.message.id in messageState.queuedMessageIds,
                                    onViewSubSession = navigateToChildSession,
                                    onRevert = if (isMainSession) {
                                        {
                                            val revertText = chatMessage.parts
                                                .filterIsInstance<Part.Text>()
                                                .joinToString("\n") { it.text }
                                            viewModel.revertMessage(chatMessage.message.id, revertText) { ok ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        if (ok) context.getString(R.string.chat_message_reverted) else context.getString(R.string.chat_message_revert_failed)
                                                    )
                                                }
                                            }
                                            onForceScrollToBottom()
                                        }
                                    } else null,
                                    onCopyText = {
                                        val text = chatMessage.parts
                                            .filterIsInstance<Part.Text>()
                                            .joinToString("\n") { it.text }
                                        if (text.isNotBlank()) {
                                            coroutineScope.launch {
                                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("copy", text)))
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                            }
                                        }
                                    },
                                    isAmoled = isAmoled
                                )
                            }
                        }
                        } // Box freeze
                    }

                    // 分页加载指示器 —— 抓取更旧消息时出现在视觉顶部（reverseLayout）
                    if (messageState.isLoadingOlder) {
                        item(key = "loading_older") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = SpacingTokens.MD.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                } // LazyColumn 结束（CompositionLocalProvider 内）
            } // CompositionLocalProvider 结束

            // 定位加载指示器：jumpToMessage 目标未加载时双向加载期间显示（覆盖层）
            if (isLoadingAround) {
                Surface(
                    shape = ShapeTokens.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.MD.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(SpacingTokens.SM.dp))
                        Text(
                            text = stringResource(R.string.loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // 滚动到底部 FAB
            if (!isAtBottom) {
                SmallFloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.snapToBottom()
                            compensateState.shouldCompensate = false
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = SpacingTokens.SM.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.chat_scroll_bottom),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 2026-08-13 跳转定位 loading 蒙版（用户建议——参考进入会话蒙版）：
            // 遮住定位过程的全部视口跳动/透明渲染/收敛修正——完成后直接显示
            // 目标（用户只看到 loading → 目标完整出现，无乱跳）。
            if (jumpLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .pointerInput(Unit) { }
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PulsingDotsIndicator()
                        Spacer(Modifier.height(SpacingTokens.MD.dp))
                        Text(
                            text = stringResource(R.string.loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM),
                        )
                    }
                }
            }

            // 快速导航底部弹窗
            QuickNavigateSheet(
                show = showQuickNavigate,
                jumpTargets = jumpTargets,
                currentMsgId = currentQuestionMsgId,
                anchorTimestampMs = currentAnchorTimestamp,
                isLoading = jumpTargetsLoading,
                onJump = { msgId -> jumpToMessage(msgId) },
                onDismiss = onQuickNavigateDismiss,
            )

            // 始终允许确认对话框
            showAlwaysDialog?.let { perm ->
                AlwaysConfirmDialog(
                    toolName = perm.permission,
                    directoryPattern = viewModel.getSessionDirectory() ?: "*",
                    onConfirm = {
                        viewModel.savePermissionRule(perm, viewModel.getSessionDirectory() ?: "*")
                        viewModel.replyToPermission(perm.id, "always")
                        showAlwaysDialog = null
                    },
                    onDismiss = { showAlwaysDialog = null }
                )
            }
        } // Box(weight)
    } // Column
    } // CompositionLocalProvider(LocalCopyFeedback)
}



/**
 * 提取 task/subagent 工具卡片的子会话 ID（metadata.sessionId / sessionID / jobId）。
 * - V1 task 工具：metadata.sessionId
 * - V2 subagent 工具：服务器返回 metadata.jobId（= 子会话 ID，task.ts:
 *   `metadata: {..., jobId: nextSession.id}`）——2026-08-11 实测发现
 *   键不匹配导致子会话跳转/定位失效
 * - synthetic 完成通知的 <task id> 与之匹配，用于「定位发起卡片」按钮。
 */
internal fun extractToolSubagentSessionId(tool: Part.Tool): String? {
    val metadata = when (val state = tool.state) {
        is ToolState.Completed -> state.metadata
        is ToolState.Running -> state.metadata
        else -> null
    } ?: return null
    val raw = metadata["sessionId"] ?: metadata["sessionID"] ?: metadata["jobId"] ?: return null
    return runCatching { raw.jsonPrimitive.contentOrNull }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

/**
 * 标准 easeInOutCubic 缓动（缓-快-缓）——跳转滚动动画（2026-08-13 恢复：
 * 去动画后视口瞬间跳动产生"闪"感——平滑滚动 + 透明门控组合根治）。
 */
private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - ((-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f)) / 2f
