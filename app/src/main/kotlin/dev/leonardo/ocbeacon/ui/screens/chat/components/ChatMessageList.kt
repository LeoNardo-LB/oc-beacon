package dev.leonardo.ocbeacon.ui.screens.chat.components

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.testTag
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
import dev.leonardo.ocbeacon.ui.screens.chat.util.rememberSafeFlingBehavior
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
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import dev.leonardo.ocbeacon.util.MessageFingerprints
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.LocalCopyFeedback
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「转后台」合成通知的服务器已知模板变体（命中 → 分割线渲染）。
 * #136（D2-L55）：原先单个硬编码模板——服务器改文案即静默失效；
 * 现改为变体列表，任一命中即视为转后台合成通知。
 * 服务器再次改文案导致特性失效时，在此追加新变体（并在 backlog 登记）。
 */
// #227：压缩尾部兜底分割线的展开表键随认领策略外移
// CompactionDividerPolicy.TAIL_EXPANSION_KEY（C4）。

private val BACKGROUND_SYNTHETIC_MARKERS = listOf(
    "User requested that active blocking work be moved to the background",
    "active blocking work be moved to the background",
    "active blocking work was moved to the background",
)

/** 判断 synthetic 消息文本是否为服务器「转后台」合成通知（供分割线渲染分支与单测使用）。
 * 大小写不敏感——服务器模板可能调整大小写/时态，任一变体命中即视为转后台合成通知。 */
internal fun isBackgroundMoveSynthetic(text: String): Boolean =
    BACKGROUND_SYNTHETIC_MARKERS.any { text.contains(it, ignoreCase = true) }

// 2026-08-21 卫生清理（D 报告 #10/#11-4）：LocalMarkdownStateRegistry（写-only
// 死注册表——消费者 scrollToDisplayItem 已于 2026-08-13 状态机重构中删除）与
// JumpBubbleObserve（targetMsgId/bubbleTopY/settled 全部零读）已删除。


/**
 * 主会话和子智能体会话消息列表共用的 composable。
 *
 * 结构：LazyColumn（待处理问题/权限、revert 横幅、消息项；自动分页加载，
 * 无下拉刷新）+ 跳转蒙版 + 快速导航抽屉。流式 turn 卡片即列表末项
 * （流式判定沿 isStreamingMsg 旧名）。
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
    isAtBottomState: androidx.compose.runtime.State<Boolean>,
    /** #222：在底意图（autoScroll）快照——尾部横幅 reveal 门控。 */
    autoScrollState: androidx.compose.runtime.State<Boolean>,
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
    // turn 内同 id 的旧引用 —— 流式 turn 永不冻结（历史回归 37d9a6ac 的教训）。
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
        CompactionStateInfo(isActive = it.isActive, reason = it.reason,
            deltaText = it.deltaText, messageId = it.messageId)
    }

    // 快速导航双向加载状态（直接从 viewModel 收集，避免侵入 messageListState combine 管道）
    val isLoadingAround by viewModel.conversation.paginationDelegate.isLoadingAround.collectAsStateWithLifecycle(initialValue = false)
    val hasNewerMessages by viewModel.conversation.paginationDelegate.hasNewerMessages.collectAsStateWithLifecycle(initialValue = false)
    val isLoadingNewer by viewModel.conversation.paginationDelegate.isLoadingNewer.collectAsStateWithLifecycle(initialValue = false)

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

    // 以 streamingMsgId 作为 key，流式 turn 变化（新消息
    // 或完成）时状态重置。这比 heightMap + 会话级清除更简单、更正确。
    val compensateState = remember(streamingMsgId) { CompensateState() }
    // #222 修二强化：延迟揭示补偿器（真·渲染前）——消息流/工具/压缩三路共用
    // shouldCompensate 门控（在底意图），各自独立基准。
    val msgReveal = remember(streamingMsgId) { DeferredRevealCompensator() }
    val toolReveal = remember(streamingMsgId) { DeferredRevealCompensator() }
    // #221：压缩展开区流式补偿——进行中压缩展开后 delta 增长与普通流式消息同
    // 待遇（tool_progress 同款：独立 lastHeight + 共享 shouldCompensate 在底意图
    // + layout{} 注入）。key=进行中压缩 messageId：压缩结束置 null 时重置，下一
    // 轮首测不注入（lastHeight>0 守卫防冷启动跳变）。
    val compactionReveal = remember(
        currentCompaction?.takeIf { it.isActive }?.messageId
    ) { DeferredRevealCompensator() }

    // #215 验收反馈·一（终版裁决 2026-08-25 用户定规）：toggle 锚定修正逻辑
    // 全部撤销——修正窗/toggleAnchorCorrection/注入通道一并不用；卡片动画
    // 回 M3/Compose 默认（spring 高度 + 淡入淡出，无任何 spec 覆盖）；展开/
    // 收起引发的视口位移交 LazyColumn 原生锚定行为（倒序列表对该场景的漂移
    // 为存量机制，定因与矩阵数据存档 journal §验收反馈·一）。
    // 流式分支（isStreamingMsg 铁律补偿）不受影响。

    // 跟踪用户是否已滚离底部。
    // 重要（铁律等价改写 2026-08-20 B-F5）：原双 key LaunchedEffect 语义 =
    // isScrollInProgress / isAtBottom 任一变化都要重估（用户通过非拖拽方式
    // 回到底部时 shouldCompensate 重置 false——fling 惯性、SSE 推送；
    // 否则每个 SSE token 都触发 requestScrollToItemNoCancel → 视口抖动）。
    // snapshotFlow 双值流保持相同反应性（任一变化即发射、顺序执行同一
    // 逻辑体），并把 State 读取移出组合作用域——原先参数是 Boolean，
    // ChatScreen 在主体读值导致每次阈值跨越整个 ChatScreen 重组。
    LaunchedEffect(listState, isAtBottomState, compensateState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottomState.value }
            .collect { (scrolling, atBottom) ->
                if (scrolling) {
                    compensateState.shouldCompensate = true
                } else if (atBottom) {
                    compensateState.shouldCompensate = false
                }
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
            jumpTargets = extractJumpTargets(viewModel.conversation.loadJumpTargets(), noTextPlaceholder)
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

    // 2026-08-20 滚动性能修复：isTurnLast 的 O(1) 索引。
    // 原实现在每个 assistant item 的组合中执行
    // rawMessages.subList(rawIndex + 1, size).firstOrNull { !it.isSynthetic }——
    // 每条消息从头线性扫描，长会话（百条级）fling 时每帧多次扫描 = O(N²)；
    // 真机 trace：fling 期单帧 49.7ms 巨帧的复合放大器之一。
    // 预计算：每条非 synthetic 消息 → 它后面第一条非 synthetic 消息 id。
    val nextRealIsAssistantByMsgId = remember(rawMessages) {
        val m = HashMap<String, Boolean?>(rawMessages.size)
        var prevReal: ChatMessage? = null
        for (cm in rawMessages) {
            if (cm.isSynthetic) continue
            if (prevReal != null) m[prevReal.message.id] = cm.isAssistant
            prevReal = cm
        }
        if (prevReal != null) m[prevReal.message.id] = null  // 会话最后一条：无后继 = turn 尾
        m
    }

    // #217/#226：尾部兜底去重判据（消息 id 集 + V1 摘要消息入列判定）——
    // 纯逻辑在 CompactionDividerPolicy（C4），此处只做 remember 缓存。
    val displayItemMessageIds = remember(displayItems) {
        CompactionDividerPolicy.displayItemMessageIds(displayItems)
    }
    val v1CompactionSummaryInList = remember(displayItems) {
        CompactionDividerPolicy.v1SummaryMessageId(displayItems) != null
    }

    // #227：压缩分割线展开表（messageId → expanded）。LazyColumn 视口外 item
    // 会被丢弃——item 内 remember 的 expanded 随之清零，滚回即自动收起（用户
    // 2026-08-26 反馈）。提升到屏幕级：滚出视口不丢；离开会话（本组合销毁）
    // 即清，Q10「展开态不跨会话记忆」仍成立。尾部兜底线 V1 无 messageId 用
    // 固定键；V2 用真实 messageId——尾部→消息流对位交接同键无缝（Q13 强化）。
    val compactionExpandedStates = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    // #232：system 消息（实测 zhipu 构建「Code Mode tool catalog」11KB 工具目录
    // 全量 schema）展开表——此前按普通消息渲染成 1340px 纯文本墙插在对话中间
    //（用户「消息叠在一起/页面乱」观感来源）。屏幕级生命周期，同 #227 模式。
    val systemNoticeExpandedStates = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    // #227：V1 尾部→消息流展开态交接桥——搬移决策在
    // CompactionDividerPolicy.v1TailHandoverPlan（C4），写表留在本 effect；
    // 无展开记录时零操作。
    androidx.compose.runtime.LaunchedEffect(v1CompactionSummaryInList) {
        if (v1CompactionSummaryInList) {
            CompactionDividerPolicy.v1TailHandoverPlan(displayItems, compactionExpandedStates)
                ?.let { plan ->
                    compactionExpandedStates[plan.targetKey] = plan.expanded
                    compactionExpandedStates.remove(CompactionDividerPolicy.TAIL_EXPANSION_KEY)
                }
        }
    }

    // LazyColumn 中 itemsIndexed 之前渲染的非消息项数量。
    // C4-C：压缩项由 CompactionDividerPolicy.bannerTerms 派生（与尾部兜底 item
    // 认领同源，消除独立手算双写）；其余 6 项（revert/retry/tool/step/question/
    // perm）仍必须与下面的条件 `item { ... }` 块保持一致（见横幅渲染）。
    val compactionBanners = remember(
        currentCompaction, displayItemMessageIds, v1CompactionSummaryInList,
    ) {
        CompactionDividerPolicy.bannerTerms(currentCompaction, displayItemMessageIds, v1CompactionSummaryInList)
    }
    val bannerCount = remember(
        sessionMeta.revert,
        compactionBanners,
        sessionMeta.sessionStatus,
        activeTools,
        currentStep,
        unembeddedQuestions,
        interaction.pendingPermissions,
    ) {
        (if (sessionMeta.revert != null) 1 else 0) +
        (if (compactionBanners.streamClaimed) 1 else 0) +
        (if (sessionMeta.sessionStatus is SessionStatus.Retry) 1 else 0) +
        (if (activeTools.isNotEmpty()) 1 else 0) +
        (if (currentStep != null) 1 else 0) +
        (if (unembeddedQuestions.isNotEmpty()) 1 else 0) +
        (if (interaction.pendingPermissions.isNotEmpty()) 1 else 0)
    }

    // #222（贴底尾部横幅 reveal）：调研定音（docs/research/2026-08-25-card-height-
    // precompute-feasibility.md §2.4）——reverseLayout key 锚定下，贴底时横幅区
    // 新 item 插入点在锚之下（P<A）→ 零位移且不被组合 → **不可见**；锚 index
    // 被抬高还使 isAtBottom 翻 false（⬇ FAB 闪现）。受影响且无自有 reveal 路径
    // 的四类：retry（无 error 伴随）、tool_progress 聚合卡、step indicator、
    // 压缩尾部兜底分割线（V1 唯一路径/V2 fallback）。revert/question/perm 不计
    // ——各有 msgCount/pendingCount 路径，重复触发无意义。
    // 修复 = reveal 而非补偿：bannerCount 驱动显式锚底（msgCount effect 同款
    // requestScrollToItem(0) 语义——显式滚动决策，零反射零测量注入）。门控用
    // autoScroll（在底意图）而非 isAtBottom——后者被插入本身翻假会自我闭锁。
    val revealBannerCount = remember(
        sessionMeta.sessionStatus,
        activeTools,
        currentStep,
        compactionBanners,
    ) {
        (if (sessionMeta.sessionStatus is SessionStatus.Retry) 1 else 0) +
            (if (activeTools.isNotEmpty()) 1 else 0) +
            (if (currentStep != null) 1 else 0) +
            (if (compactionBanners.tailFallback) 1 else 0)
    }
    LaunchedEffect(revealBannerCount) {
        if (revealBannerCount > 0 && autoScrollState.value) {
            // fling 等待 + 重校验（msgCount effect 同款防「快照后用户开始拖动」竞态）
            if (listState.isScrollInProgress) {
                kotlinx.coroutines.withTimeoutOrNull(2_000) {
                    androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
                        .first { !it }
                }
            }
            if (autoScrollState.value) {
                listState.requestScrollToItem(0)
            }
        }
    }

    // ===== 2026-08-20 fling 巨帧根治：超长消息块级分片 =====
    // ===== 2026-08-21 架构评审候选 1：渲染供给协调器（Render Supply）=====
    // 原 chunkPlans/pendingChunkPlans/recentStreamedTurnKeys 三个 Compose 状态 +
    // ~190 行视口驱动 LaunchedEffect 收进 RenderSupplyCoordinator（纯 Kotlin，
    // 可 JVM 单测）；本组合只保留只读流消费 + 快照装配桥。
    // 跳转相位共享流：JNC 状态机驱动，协调器构造期直读（B-F3 语义）。
    val sharedJumpPhase = remember { MutableStateFlow<JumpPhase>(JumpPhase.Idle) }
    // 渲染就绪注册表（原声明位置上移——协调器构造依赖）。
    val renderReadiness = remember { RenderReadinessRegistry() }
    val renderSupply = remember {
        RenderSupplyCoordinator(renderReadiness, coroutineScope, sharedJumpPhase)
    }
    val chunkPlans by renderSupply.chunkPlans.collectAsState()
    val recentStreamedTurnKeys by renderSupply.recentStreamedTurnKeys.collectAsState()

    val lastStreamingMsgId = remember { mutableStateOf<String?>(null) }
    // ===== 2026-08-20 fling 巨帧根治：分片发射表（消息区 entries）=====
    // entries = displayItems 经 chunkPlans 展开（巨型 turn → N 个 chunk item）。
    // 双向索引是 LazyColumn index ↔ displayItems index 的单一真相源。
    val chatEntries = remember(displayItems, turnGroups, streamingMsgId, chunkPlans, recentStreamedTurnKeys) {
        dev.leonardo.ocbeacon.debug.RaceProbe.probe {
            "ENTRIES rebuild n=" + displayItems.size +
                " chunkPlans=" + chunkPlans.size +
                " streaming=" + (streamingMsgId != null) +
                " recentN=" + recentStreamedTurnKeys.size
        }
        buildChatEntries(displayItems, turnGroups, streamingMsgId, chunkPlans, recentStreamedTurnKeys)
    }
    val chatEntriesForPreparse = androidx.compose.runtime.rememberUpdatedState(chatEntries)
    // 流式结束瞬间记录 turn key（延迟分片——防视口内 key 裂变闪跳；
    // 由协调器的窗口清理负责释放）。
    LaunchedEffect(streamingMsgId) {
        if (streamingMsgId == null && lastStreamingMsgId.value != null) {
            val found = displayItems.indexOfFirst { (_, m) -> m.message.id == lastStreamingMsgId.value }
            if (found >= 0) {
                val (ri, m) = displayItems[found]
                val tk = "t_" + (turnGroups[ri]?.firstOrNull()?.message?.id ?: m.message.id)
                renderSupply.noteStreamTurnEnded(tk)
            }
        }
        lastStreamingMsgId.value = streamingMsgId
    }

    // 当前可见问题（msgId 驱动，与 Room 全量列表的 JumpTarget.msgId 匹配高亮）。
    // 2026-08-12 修复：基于 displayItems 显示序列（原 rawMessages 索引与显示序列
    // 不一致导致 currentMsgId 恒为 null——见 JumpTargetExtractor 注释）。
    // 2026-08-20 B-F3：只建 State 对象不在此读取——原 by 委托在 ChatMessageList
    // 主体（~1200 行组合作用域）读取 .value，每次滚动跨 item 触发整个主体重启
    //（分片后 = 每个 chunk 交叉）；读取下沉到 QuickNavigateHost 小作用域 +
    // 条件订阅（sheet 关闭时零依赖零重算——这两个 derived 读 layoutInfo，
    // 重算本身也贵，关闭期间完全没有必要算）。真机 Perfetto 定罪：最差帧
    // 63.5% 时间在 Compose:recompose（重组读放大是头号根因）。
    val currentQuestionMsgIdState = remember(displayItems, bannerCount, chatEntries) {
        derivedStateOf { findCurrentQuestionMsgId(listState, displayItems, bannerCount, chatEntries.entryDisplayIndex) }
    }
    // 当前可见区域时间锚点（快速导航打开时降级定位用——见 QuickNavigateSheet）
    val currentAnchorTimestampState = remember(displayItems, bannerCount, chatEntries) {
        derivedStateOf { findCurrentAnchorTimestamp(listState, displayItems, bannerCount, chatEntries.entryDisplayIndex) }
    }

    // 高亮 key（3 秒后自动清除）—— scrollToDisplayItem / onLocateTask 共用。
    var highlightedTurnKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTurnKey) {
        if (highlightedTurnKey != null) {
            delay(3000)
            highlightedTurnKey = null
        }
    }


    // ===== 临时诊断（ScrollDiag，2026-08-20 真机滚动取证，DEBUG-only，行为零变化）=====
    // ① 位置流 LEAP 检测：index/offset 在两次发射间异常跳变（程序化滚动/锚点修正）
    // ② 手势起止记录：与 LEAP 对照区分「用户手势期间」与「停稳后」的跳变
    if (BuildConfig.DEBUG) {
        LaunchedEffect(listState) {
            var lastIdx = -1
            var lastOff = -1
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (idx, off) ->
                    if (lastIdx >= 0) {
                        val dIdx = idx - lastIdx
                        val dOff = off - lastOff
                        if (kotlin.math.abs(dIdx) > 1 || kotlin.math.abs(dOff) > 350) {
                            AppLogger.w(
                                "ScrollDiag",
                                "LEAP idx " + lastIdx + "->" + idx + " (dIdx=" + dIdx + ") off " +
                                    lastOff + "->" + off + " (dOff=" + dOff + ") inProgress=" +
                                    listState.isScrollInProgress + " total=" + listState.layoutInfo.totalItemsCount
                            )
                        }
                    }
                    lastIdx = idx
                    lastOff = off
                }
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { p ->
                AppLogger.d(
                    "ScrollDiag",
                    "gesture=" + p + " idx=" + listState.firstVisibleItemIndex +
                        " off=" + listState.firstVisibleItemScrollOffset +
                        " streamingMsgId=" + (streamingMsgId ?: "null") +
                        " shouldComp=" + compensateState.shouldCompensate
                )
            }
        }
    }

    // 2026-08-13 架构根治（状态机）：跳转定位状态机——蒙版/门控/锁从状态派生
    //（单一真相源——消除 jumpLoading/settled/jumpLockActive 各自为政的竞态）。
    // 2026-08-20 A-F3/D-2 竞态修复：resolveLazyIndex 原 remember{} 无 key 捕获
    // 首次组合的 displayItems/chatEntries/bannerCount——重定位用首帧快照反查
    // index，loadAround 重建+分片裂变后永远偏移 → 落点进中段 chunk（圆角变
    // 直角+无标签行+正文中间露出=用户截图观感）。与渲染供给（preparse）桥
    // 同款 rememberUpdatedState 三件套，闭包内取 .value 永远读最新。
    val displayItemsForJump = androidx.compose.runtime.rememberUpdatedState(displayItems)
    val chatEntriesForJump = androidx.compose.runtime.rememberUpdatedState(chatEntries)
    val bannerCountForJump = androidx.compose.runtime.rememberUpdatedState(bannerCount)
    val jumpController = remember {
        JumpNavigationController(
            listState,
            coroutineScope,
            sharedJumpPhase,
            resolveLazyIndex = { msgId ->
                displayItemsForJump.value.indexOfFirst { it.second.message.id == msgId }
                    .takeIf { it >= 0 }
                    // 2026-08-20 分片适配：turn 首 chunk
                    ?.let { bannerCountForJump.value + chatEntriesForJump.value.displayEntryStart[it] }
            },
        )
    }
    // 2026-08-21 卫生（D-11-2）：jumpPhase 大作用域订阅下沉——原主体直读使
    // 每次跳转 ≥4 次相位变化都重组整个 ~1500 行 ChatMessageList 主体。现在
    // 蒙版由 JumpMaskOverlay 小组件自行订阅；解锁 effect 直收 flow；预解析
    // 提交门控本就直读 phase.value（B-F3）。

    // 2026-08-12 修复（2026-08-22 #159 收口）：跳转定位锁定——jumpToMessage
    // 期间抑制 autoLoad（loadOlder 自动补载会插入 older 批 → 目标被推下视口，
    // logcat 实证 positioned 后被 auto-load 推走）。原手工镜像已删，锁从
    // 状态机派生（异步窗口 ∪ 进行中 ∪ 终点后 300ms——见 JNC.jumpLockActive；
    // 收口动机：loadAround 失败路径漏复位镜像 → 目标不存在时 autoLoad 永久锁死）。
    val jumpLockActive = jumpController.jumpLockActive.collectAsState().value

    // 快速导航异步跳转：jumpToMessage 目标未加载时设此值，loadAround 完成后
    // 消息进入 displayItems → LaunchedEffect 重启 → 状态机跳转
    var pendingJumpTarget by remember { mutableStateOf<String?>(null) }
    // 2026-08-20：loadAround 未命中重试一次（深分页/服务器时序一次加载可能不够，
    // 此前直接 snackbar 误报『此会话中未找到发起任务』——用户快速连跳时必现）
    var pendingJumpRetried by remember { mutableStateOf(false) }
    //（跳转终点时刻 lastJumpEndAtMillis 已收编 RenderSupplyCoordinator——
    // 阶段 2：模块自记终点，跨 effect 时间戳耦合消灭。）

    // ===== 2026-08-20 滚动稳定性：渲染供给·滚动预解析（fling 下跳根因修复） =====
    // 真机取证（ScrollDiag RESIZE）：assistant 长回复初次组合仅测得占位高度
    // （412px），markdown 异步解析完成后暴涨（412→16746px）→ LazyColumn 锚点
    // 修正 → fling 中视口瞬移 1.4 万 px（用户报"下跳"，长回复稳定复现）。
    // 2026-08-21 候选 1：驱动决策外移 RenderSupplyCoordinator——本桥只做
    // snapshotFlow 视口采集 + 世界快照装配（窗口/门控/LRU/提交全在协调器）。
    val displayItemsForPreparse = androidx.compose.runtime.rememberUpdatedState(displayItems)
    val turnGroupsForPreparse = androidx.compose.runtime.rememberUpdatedState(turnGroups)
    val streamingMsgIdForPreparse = androidx.compose.runtime.rememberUpdatedState(streamingMsgId)
    LaunchedEffect(listState, bannerCount) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.firstOrNull()?.index ?: 0) to
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0)
        }.collect { (firstIdx, lastIdx) ->
            dev.leonardo.ocbeacon.debug.RaceProbe.probe {
                "VIEW window " + firstIdx + ".." + lastIdx + " keys=" +
                    listState.layoutInfo.visibleItemsInfo.take(6).joinToString(",", "[", "]") { "${it.key}" }
            }
            renderSupply.onViewportChanged(
                firstIdx,
                lastIdx,
                RenderSupplyWorld(
                    displayItems = displayItemsForPreparse.value,
                    turnGroups = turnGroupsForPreparse.value,
                    entries = chatEntriesForPreparse.value,
                    bannerCount = bannerCount,
                    streamingMsgId = streamingMsgIdForPreparse.value,
                ),
            )
        }
    }

    // （2026-08-13 架构根治 / 2026-08-22 #159 收口）autoLoad 解锁已收编
    // JumpNavigationController——终点后 300ms 缓冲在控制器内经
    // phase.collectLatest 派生（jumpLockActive StateFlow），本组件不再
    // 手工维护镜像写点。

    // 2026-08-21 卫生清理：mdRegistry（写-only 死注册表，D-10）已删除。


    // ===== 状态机版跳转（2026-08-13 架构根治——旧 scrollToDisplayItem 已删除）=====
    // 定位决策全部在 JumpNavigationController 状态机（Preparing→Measuring→Settling→
    // Displayed/Failed）；蒙版/门控从状态派生（单一真相源）；目标一次定位到最终位置
    //（不移动→不回收→不重测→无"重复乱跳"根因）。
    fun jumpToMessage(msgId: String) {
        dev.leonardo.ocbeacon.debug.RaceProbe.probe {
            "JUMP start msg=" + msgId.take(14) + " entries=" + chatEntries.entries.size +
                " displayN=" + displayItems.size
        }
        val displayItemIndex = displayItems.indexOfFirst { it.second.message.id == msgId }
        // 2026-08-12 修复：目标在 displayItems 但 parts 为空（Room 有消息但
        // parts 未 upsert 到内存——重启后内存只加载最新窗口）→ loadAround 加载。
        val targetHasRenderableContent = displayItemIndex >= 0 &&
            displayItems[displayItemIndex].second.parts.any { it is Part.Text && it.text.isNotBlank() }
        if (BuildConfig.DEBUG) {
            AppLogger.d("ChatPaging", "jumpToMessage: msgId=${msgId.take(12)} inDisplay=$displayItemIndex renderable=$targetHasRenderableContent")
        }
        if (targetHasRenderableContent) {
            // 状态机跳转：一次定位 + 蒙版/门控从状态派生
            // 2026-08-20 分片适配：lazyIndex = turn 首 chunk（含标签栏）
            jumpController.jumpTo(
                msgId,
                bannerCount + chatEntries.displayEntryStart[displayItemIndex],
            )
            onQuickNavigateDismiss()
        } else {
            // 未加载或 parts 为空：触发异步跳转加载，等待消息进入 displayItems 后滚动
            //（#159：异步窗口的 autoLoad 锁定 = 控制器 markJumpPending——
            // phase 仍 Idle，锁不经相位发射同步直写）
            jumpController.markJumpPending()
            pendingJumpTarget = msgId
            pendingJumpRetried = false
            onQuickNavigateDismiss()
            coroutineScope.launch { viewModel.conversation.paginationDelegate.loadAround(msgId) }
        }
    }
    // 「定位发起卡片」支持：点击轮次完成合成通知卡片上的定位按钮 → 查找发起卡片
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
            // 2026-08-20 分片适配：turn 首 chunk
            val lazyIndex = bannerCount + chatEntries.displayEntryStart[targetIndex]
            val (rawIndex, targetMsg) = displayItems[targetIndex]
            val targetMsgId = targetMsg.message.id
            // 2026-08-13 架构根治：onLocateTask 复用状态机（同一定位流程——一次
            // 定位 + 蒙版/门控 + 收敛；assistant 目标无预解析，直接测量）。
            // #159：autoLoad 锁由控制器从 Preparing 派生（入口同步置相位）
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

            // 自动分页（older 方向）：用户距顶部 8 项以内时触发加载。
            // 取代 PullToRefreshBox —— 无缝，无需手动手势。
            // C10：触发决策（阈值/方向/退避/跳转互斥）全部在 AutoLoadPolicy（纯函数，
            // JVM 可测——2026-08-10 isScrollInProgress 依赖移除、08-12 reverseLayout
            // 索引方向、08-21 ×2 fire-time 竞态四次历史修复语义存档于该文件）。
            //
            // 关键：LaunchedEffect 必须以 messageState 字段为 key。没有这些 key，
            // snapshotFlow 会捕获初始 messageState（其中 hasOlderMessages=false），
            // loadMessagesForSession() 将 hasOlderMessages 置 true 时永远看不到更新
            // ——这是进入会话后分页静默失败的根源。加载完成 isLoadingOlder 翻转
            // → effect 重启 → 若仍距顶近则自动续载（08-10 语义）。
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
                val paging = AutoLoadPagingState(
                    hasMore = messageState.hasOlderMessages,
                    isLoading = messageState.isLoadingOlder,
                    paused = messageState.autoLoadPaused,
                )
                if (AutoLoadPolicy.startGate(paging, jumpLockActive)) {
                    snapshotFlow { listState.layoutInfo }
                        .map { info ->
                            val snap = info.toAutoLoadSnapshot()
                            // 低频诊断：触发条件附近打印（滚动高频段不刷屏）
                            if (BuildConfig.DEBUG) {
                                AutoLoadPolicy.olderProbeReason(snap)?.let { AppLogger.d("ChatPaging", it) }
                            }
                            AutoLoadPolicy.olderThresholdMet(snap)
                        }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            // 桥：policy 退避 → policy fire-time 复查 → delegate.load
                            val trigger = AutoLoadPolicy.trigger(
                                viewModel.conversation.paginationDelegate.autoLoadWaitMillis(),
                            )
                            if (trigger.backoffMillis > 0) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load backoff wait ${trigger.backoffMillis}ms before retry")
                                delay(trigger.backoffMillis)
                            }
                            // 08-21 ×2：fire-time 复查 + 直读 phase 真源（isJumpInProgress）
                            if (!AutoLoadPolicy.fireAllowed(jumpController.isJumpInProgress)) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load skipped (jump in progress at fire time)")
                                return@collect
                            }
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load triggered (hasOlder=true)")
                            viewModel.conversation.paginationDelegate.loadOlderMessages()
                        }
                }
            }

            // 自动分页（newer 方向）：用户距视觉底部 8 项以内时触发加载更新消息。
            // 仅在 loadAround 定位后激活（hasNewerMessages=true）；正常会话状态
            //（已在最新）hasNewerMessages=false，永不触发。方向语义（与 older 的
            // 视觉顶部阈值对称）存档 AutoLoadPolicy（C10）。
            LaunchedEffect(
                hasNewerMessages,
                isLoadingNewer,
                jumpLockActive,
            ) {
                val paging = AutoLoadPagingState(
                    hasMore = hasNewerMessages,
                    isLoading = isLoadingNewer,
                )
                if (AutoLoadPolicy.startGate(paging, jumpLockActive)) {
                    snapshotFlow { listState.layoutInfo }
                        .map { info ->
                            val snap = info.toAutoLoadSnapshot()
                            if (BuildConfig.DEBUG) {
                                AutoLoadPolicy.newerProbeReason(snap)?.let { AppLogger.d("ChatPaging", it) }
                            }
                            AutoLoadPolicy.newerThresholdMet(snap)
                        }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            // 桥：policy fire-time 复查（08-21 ×2，newer 无退避）→ delegate.load
                            if (!AutoLoadPolicy.fireAllowed(jumpController.isJumpInProgress)) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load newer skipped (jump in progress at fire time)")
                                return@collect
                            }
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load newer triggered (nearBottom=true, hasNewer=true)")
                            viewModel.conversation.paginationDelegate.loadNewerMessages()
                        }
                }
            }

            // 快速导航异步跳转：jumpToMessage 目标未加载时设此值，loadAround 完成后
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
                    // 2026-08-20 分片适配补漏（渲染错位根因）：本路径是三条跳转
                    // 入口中唯一漏改的——display 粒度 index 直接传给 scrollToItem，
                    // 窗口内存在分片 turn（1→N item）时指向错误位置，viewport 落在
                    // 某个 chunk 中间（用户截图的"不完整气泡/非从头开始的回复"）。
                    // 与 jumpToMessage 主路径/onLocateTask 对齐：displayEntryStart 映射。
                    jumpController.jumpTo(
                        target,
                        bannerCount + chatEntries.displayEntryStart[idx],
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
                    val target = pendingJumpTarget ?: return@LaunchedEffect
                    val found = displayItems.indexOfFirst { it.second.message.id == target } >= 0
                    if (!found && !pendingJumpRetried) {
                        // 2026-08-20：首次未命中重试一次 loadAround 再判——避免时序误报
                        pendingJumpRetried = true
                        coroutineScope.launch { viewModel.conversation.paginationDelegate.loadAround(target) }
                    } else if (!found) {
                        pendingJumpTarget = null
                        pendingJumpRetried = false
                        // #159（2026-08-22）：异步跳转失败解锁——旧镜像此处漏
                        // 复位，目标真不存在时 autoLoad 被锁死到下次成功跳转
                        jumpController.clearPendingJumpLock()
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.chat_locate_task_not_found))
                        }
                    } else {
                        pendingJumpRetried = false
                    }
                }
            }

            // 2026-08-13 状态机注入（门控读 LocalJumpController）。
            // 2026-08-21 卫生清理：LocalMarkdownStateRegistry 注入已删除（D-10）。
            androidx.compose.runtime.CompositionLocalProvider(
                LocalRenderReadiness provides renderReadiness,
                LocalJumpController provides jumpController,
            ) {
                LazyColumn(
                    state = listState,
                    // 2026-08-20 滚动稳定性：限速 fling——每帧 ≤ 视口高/8，
                    // 高速段不再冲入未组合区（与渲染供给协调器配合，见上方）
                    flingBehavior = rememberSafeFlingBehavior(listState),
                    modifier = Modifier.fillMaxSize()
                        // #149：唯一 testTag——ChatScreen 树中有 2 个 scrollable 节点
                        //（消息列表 + 底部输入栏），androidTest 的 hasScrollAction()
                        // 匹配多节点导致 touch 注入失败
                        .testTag("chat-message-list")
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
                    contentPadding = PaddingValues(
                        start = SpacingTokens.MD.dp,
                        top = SpacingTokens.SM.dp,
                        end = SpacingTokens.MD.dp,
                        bottom = SpacingTokens.SM.dp
                    ),
                    reverseLayout = true,
                    // 2026-08-20 分片：移除 spacedBy（chunk item 间不能有间隙——
                    // 同一气泡的分段视觉连续），改为 item 级 bottom padding
                    //（横幅/Turn/Chunk 末段加 messageSpacing，chunk 非末段为 0）。
                ) {
                    // reverseLayout=true：先声明的项渲染在底部。
                    // 视觉顺序（上→下）：最旧消息 → 最新消息 → revert → pending。
                    // 声明顺序自下而上：pending（底部）→ 消息（顶部）。

                    // Revert 横幅
                    if (sessionMeta.revert != null) {
                        item(key = "revert_banner") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
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
                    }

                    // #217 分割线包揽（2026-08-24）：压缩进行中 = 进行中分割线
                    //（进度线即分割线 + 可展开流式摘要）——CompactionBanner 已删除。
                    // 插在消息流尾部（最新消息之后），完成态由消息流内 compaction
                    // 消息的 CompactionCard 承担（同一组件两态）。
                    // 尾部兜底认领（去重/让位判定）在 CompactionDividerPolicy.tailSpec（C4）。
                    val tailCompaction = CompactionDividerPolicy.tailSpec(
                        currentCompaction, displayItemMessageIds, v1CompactionSummaryInList,
                    )
                    if (tailCompaction != null) {
                        item(key = "compaction_banner") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
                                // #221/#222：展开区流式增长——延迟揭示真·渲染前
                                // 补偿（尾部兜底路径；消息流对位路径同款见下）。
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clipToBounds()
                                        .deferredRevealCompensation(
                                            listState = listState,
                                            compensator = compactionReveal,
                                            shouldCompensate = { compensateState.shouldCompensate },
                                            logTag = "COMP-CMP(tail)",
                                        )
                                ) {
                                    // #227：展开态入表（expansionKey：V2=真实 messageId，
                                    // V1 空串→固定键；尾部→消息流同键交接）
                                    CompactionCard(
                                        state = tailCompaction.state,
                                        expanded = compactionExpandedStates[tailCompaction.expansionKey] ?: false,
                                        onExpandedChange = { compactionExpandedStates[tailCompaction.expansionKey] = it },
                                    )
                                }
                            }
                        }
                    }

                    // Retry 横幅 —— 会话处于 Retry 状态时显示
                    val retryStatus = sessionMeta.sessionStatus
                    if (retryStatus is SessionStatus.Retry) {
                        item(key = "retry_banner") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
                            RetryBanner(retryStatus)
                            }
                        }
                    }

                    // 工具进度卡片（带漂移补偿）
                    if (activeTools.isNotEmpty()) {
                        item(key = "tool_progress") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                                    .deferredRevealCompensation(
                                        listState = listState,
                                        compensator = toolReveal,
                                        shouldCompensate = { compensateState.shouldCompensate },
                                        logTag = "COMP-TOOL",
                                    )
                            ) {
                                activeTools.forEach { toolInfo ->
                                    ToolProgressCard(toolInfo = toolInfo)
                                }
                            }
                            }
                        }
                    } else {
                        // 无活跃工具时重置
                        toolReveal.reset()
                    }

                    // 步骤进度指示器
                    if (currentStep != null) {
                        item(key = "step_progress") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
                            StepProgressIndicator(stepInfo = currentStep)
                            }
                        }
                    }

                    // 待处理问题（未嵌入消息气泡的保底显示）——一次显示一个（最旧优先）
                    unembeddedQuestions.firstOrNull()?.let { question ->
                        item(key = "question_${question.id}") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
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
                                },
                                answersStore = viewModel.questionAnswerStore,
                            )
                            }
                        }
                    }

                    // 待处理权限 —— 一次显示一个（最旧优先）
                    interaction.pendingPermissions.firstOrNull()?.let { permission ->
                        item(key = "perm_${permission.id}") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
                            PermissionCard(
                                permission = permission,
                                positionLabel = if (interaction.pendingPermissions.size > 1) "1/${interaction.pendingPermissions.size}" else null,
                                onOnce = {
                                    viewModel.replyToPermission(permission.id, "once", permission.sessionId)
                                    onForceScrollToBottom()
                                },
                                onAlways = { showAlwaysDialog = permission },
                                onReject = {
                                    viewModel.replyToPermission(permission.id, "reject", permission.sessionId)
                                    onForceScrollToBottom()
                                }
                            )
                            }
                        }
                    }

                    // 聊天消息：displayItems 已经是新的在前（降序）。
                    // reverseLayout=true 将索引 0（最新）渲染在底部。
                    // 视觉结果：最旧在顶部，最新在底部。
                    // 2026-08-20 分片：items = chatEntries（巨型 turn 已展开为
                    // N 个 chunk item；key 语义不变——buildChatEntries 与原逻辑
                    // 一致，chunk 追加 #c<i> 后缀且保持 t_/u_ 前缀）。
                    itemsIndexed(
                        chatEntries.entries,
                        key = { _, entry -> entry.key },
                        contentType = { _, entry ->
                            when (entry) {
                                is ChatEntry.Chunk -> "assistant_chunk"
                                is ChatEntry.UserChunk -> "user_chunk"
                                is ChatEntry.Turn ->
                                    if (displayItems[entry.displayIndex].second.isUser) "user" else "assistant"
                            }
                        },
                    ) { _, entry ->
                        when (entry) {
                            is ChatEntry.Chunk -> {
                                val displayItemIndex = entry.displayIndex
                                val (rawIndex, msg) = displayItems[entry.displayIndex]
                                val nextRealIsAssistant = nextRealIsAssistantByMsgId[msg.message.id]
                                val isTurnLast = nextRealIsAssistant != true
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // #231：分片 item 同样补 clip（异步增长重排窗口
                                        // 的越界绘制防御，见 Turn 分支注释）
                                        .clipToBounds()
                                        .let { m ->
                                            if (entry.isLast) m.padding(bottom = messageSpacing) else m
                                        }
                                ) {
                                    ChunkedAssistantMessage(
                                        renderableTurn = renderableTurns[displayItemIndex] ?: return@itemsIndexed,
                                        currentMessage = msg,
                                        chunk = entry,
                                        isAmoled = isAmoled,
                                        isTurnLast = isTurnLast,
                                        agents = agents,
                                        onAgentClick = onAgentClick,
                                        onCopy = {
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                            }
                                        },
                                        onViewSubSession = navigateToChildSession,
                                        onOpenFile = onOpenFile,
                                        onLocateTask = onLocateTask,
                                    )
                                }
                            }
                            is ChatEntry.UserChunk -> {
                                val (rawIndex, msg) = displayItems[entry.displayIndex]
                                val chatMessage = msg
                                Box(
                                    modifier = Modifier.fillMaxWidth().let { m ->
                                        if (entry.isLast) m.padding(bottom = messageSpacing) else m
                                    }
                                ) {
                                    ChunkedUserMessage(
                                        currentMessage = chatMessage,
                                        chunk = entry,
                                        isQueued = chatMessage.message.id in messageState.queuedMessageIds,
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
                                        isAmoled = isAmoled,
                                    )
                                }
                            }
                            is ChatEntry.Turn -> {
                        val displayItemIndex = entry.displayIndex
                        val (rawIndex, msg) = displayItems[entry.displayIndex]
                        // #103（M-8）：与 LazyColumn key 同锚点（turn 组首条消息 id）
                        val itemKey = if (msg.isUser) "u_${msg.message.id}"
                            else "t_${turnGroups[rawIndex]?.firstOrNull()?.message?.id ?: msg.message.id}"
                        val isStreamingMsg = (turnGroups[rawIndex] ?: listOf(msg)).any { it.message.id == streamingMsgId }
                        // #231（2026-08-26 用户再报「还是叠在一起」）：非流式 item 此前
                        // 无 clip——异步内容增长（reasoning 展开/Markdown 迟到解析/
                        // 分片裂变）重排窗口内，越界绘制会压到相邻 item 上（用户
                        // 截图实证：灰色思考文本压进代码块容器）。clipToBounds 把
                        // 任何越界绘制转为「暂时裁掉」（内容在界内时零视觉差异），
                        // 跨 item 叠加从构造上不可能再发生。流式 item 原本就有
                        // clip（COMP-MSG 链），此处补齐非流式分支。
                        val itemModifier = if (isStreamingMsg) {
                            Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .deferredRevealCompensation(
                                    listState = listState,
                                    compensator = msgReveal,
                                    shouldCompensate = { compensateState.shouldCompensate },
                                    logTag = "COMP-MSG",
                                )
                        } else Modifier.fillMaxWidth().clipToBounds()
                        // #215 验收反馈·一（终版裁决）：方案一（offset±delta 补偿）与方案三
                        //（修正窗+注入通道）均已撤销——用户定规不用任何补偿逻辑，动画回
                        // M3 默认；定因矩阵与通道数据存档 journal §验收反馈·一
                        // 定位发起卡片后的短暂高亮（3 秒后自动清除）
                        val isHighlighted = itemKey == highlightedTurnKey
                        // 临时诊断（ScrollDiag，DEBUG-only）：item 初次测量后的高度变化
                        //（渐进测量/异步重排检测——跳变根因取证）
                        val diagLastSize = remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
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
                            // 2026-08-20 分片：item 级间距（原 spacedBy 移除——
                            // chunk item 间需无缝，Turn 与相邻项间隙在此补）。
                            // 置于 layout{} 补偿之外，不影响补偿测量高度。
                            .padding(bottom = messageSpacing)
                            .onSizeChanged { s ->
                                if (BuildConfig.DEBUG) {
                                    val prev = diagLastSize.value
                                    if (prev != androidx.compose.ui.unit.IntSize.Zero && s.height != prev.height) {
                                        AppLogger.w(
                                            "ScrollDiag",
                                            "RESIZE key=" + itemKey.take(18) + " h " + prev.height + "->" + s.height +
                                                " (d=" + (s.height - prev.height) + ") dispIdx=" + displayItemIndex +
                                                " inProgress=" + listState.isScrollInProgress
                                        )
                                    }
                                }
                                diagLastSize.value = s
                            }
                        ) {
                        when {
                            msg.isAssistant -> {
                                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                                    dev.leonardo.ocbeacon.logging.AppLogger.d(
                                        "ItemDiag",
                                        "Turn item key=" + itemKey.take(18) + " role=assistant grp=" + (turnGroups[rawIndex]?.size ?: 1)
                                    )
                                }
                                // #226：V1 摘要消息认领——判定/装配在
                                // CompactionDividerPolicy.v1SummarySpec（C4，历史注释
                                // 存档随逻辑迁移）；同 item 原位切换保留 Q13 连续性
                                //（latchedText 跨完成保持）。此处按 spec 分发渲染。
                                val v1Spec = CompactionDividerPolicy.v1SummarySpec(msg)
                                if (v1Spec != null) {
                                    var showRevertDialog by remember { mutableStateOf(false) }
                                    if (showRevertDialog) {
                                        ConfirmDialog(
                                            title = stringResource(R.string.chat_revert_title),
                                            message = stringResource(R.string.chat_revert_message),
                                            confirmLabel = stringResource(R.string.chat_revert),
                                            onDismiss = { showRevertDialog = false },
                                            onConfirm = {
                                                showRevertDialog = false
                                                // 撤销边界（V1 语义：撤到压缩点之前恢复
                                                // 被压前文）判定在
                                                // CompactionDividerPolicy.v1RevertBoundary。
                                                val revertTarget = CompactionDividerPolicy.v1RevertBoundary(
                                                    displayItems, displayItemIndex, msg.message.id,
                                                )
                                                viewModel.revertMessage(revertTarget) { ok ->
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            if (ok) context.getString(R.string.chat_messages_restored)
                                                            else context.getString(R.string.chat_message_redo_failed)
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    val revertActionLabel = stringResource(R.string.chat_revert)
                                    // 流式补偿：streamingMsgId 命中时外层 itemModifier 已包
                                    // COMP-MSG（L isStreamingMsg 判定），此处仅在其缺席时
                                    // 自包 COMP-CMP——避免双重注入。
                                    val v1GrowModifier = if (v1Spec.active && !isStreamingMsg) {
                                        Modifier
                                            .fillMaxWidth()
                                            .clipToBounds()
                                            .deferredRevealCompensation(
                                                listState = listState,
                                                compensator = compactionReveal,
                                                shouldCompensate = { compensateState.shouldCompensate },
                                                logTag = "COMP-CMP(v1)",
                                            )
                                    } else Modifier
                                    Column(
                                        modifier = v1GrowModifier
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onLongPress = { showRevertDialog = true }
                                                )
                                            }
                                            .semantics {
                                                customActions = listOf(
                                                    CustomAccessibilityAction(
                                                        label = revertActionLabel,
                                                        action = { showRevertDialog = true; true }
                                                    )
                                                )
                                            }
                                    ) {
                                        CompactionCard(
                                            expanded = compactionExpandedStates[v1Spec.expansionKey] ?: false,
                                            onExpandedChange = { compactionExpandedStates[v1Spec.expansionKey] = it },
                                            state = v1Spec.activeState,
                                            summary = v1Spec.summary,
                                            failed = v1Spec.failed,
                                        )
                                    }
                                    return@itemsIndexed
                                }
                                // isTurnLast：下一条"非 synthetic"消息不是 assistant 才算 turn 尾。
                                // synthetic 为独立气泡（2026-08-12 起不并入 assistant turn），
                                // 判定跳过它——不阻挡统计栏。
                                // 2026-08-20：O(1) 查表（见上方
                                // nextRealIsAssistantByMsgId），原 subList 线性扫描
                                // 为 O(N²) 复合放大器。null = 无后继（turn 尾）。
                                val isTurnLast = nextRealIsAssistantByMsgId[msg.message.id] != true

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
                                    questionAnswersCache = viewModel.questionAnswerStore,
                                )
                            }
                            msg.isUser -> {
                                val chatMessage = msg


                                // #232（2026-08-26 用户三报「消息叠在一起」取证定音）：
                                // system 角色消息（zhipu 构建的「Code Mode tool catalog
                                // has changed」通知，实测 11235 字符工具目录全量 schema）
                                // 此前按普通用户消息渲染——1340px 无气泡纯文本墙插在
                                // 中文对话中间，视觉上即「多条消息叠在一起/页面乱」。
                                // 折叠为一行系统通知（图标 + 首句截断 + 展开箭头），
                                // 点击展开可滚动全文（300dp 上限）。
                                if ((chatMessage.message as? Message.User)?.role == "system") {
                                    if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                                        dev.leonardo.ocbeacon.logging.AppLogger.w(
                                            "SysMsgDiag",
                                            "#232 system branch RENDER id=" + chatMessage.message.id.takeLast(8) +
                                                " textLen=" + chatMessage.parts.filterIsInstance<Part.Text>().sumOf { it.text.length }
                                        )
                                    }
                                    val sysText = chatMessage.parts
                                        .filterIsInstance<Part.Text>()
                                        .joinToString("\n") { it.text }.trim()
                                    val sysKey = chatMessage.message.id
                                    val sysExpanded = systemNoticeExpandedStates[sysKey] ?: false
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = messageSpacing)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { systemNoticeExpandedStates[sysKey] = !sysExpanded }
                                                .padding(vertical = SpacingTokens.XS.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Info,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                            )
                                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(SpacingTokens.XS.dp))
                                            Text(
                                                text = sysText.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                                                    ?: "system",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = if (sysExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                            )
                                        }
                                        androidx.compose.animation.AnimatedVisibility(visible = sysExpanded) {
                                            val sysScroll = androidx.compose.foundation.rememberScrollState()
                                            androidx.compose.foundation.layout.Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 300.dp)
                                                    .verticalScroll(sysScroll)
                                            ) {
                                                Text(
                                                    text = sysText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                                                )
                                            }
                                        }
                                    }
                                    return@itemsIndexed
                                }


                                // #217/#219/#221/#226：压缩触发认领（对位/锁存/隐藏/
                                // 分割线判定）——纯逻辑在 CompactionDividerPolicy（C4，
                                // 历史修复语义注释随逻辑迁移存档）。
                                val compactionActiveState = CompactionDividerPolicy.activeCompactionFor(
                                    currentCompaction, chatMessage.message.id,
                                )
                                // #221（展开跨完成保持）：ended 清态 → REST 刷新带入
                                // Part.Compaction 前存在空窗——锁存最近一次进行中对位到的
                                // messageId（item 级 Compose 状态留在此处）：同 item 组合
                                // 存续，空窗期认领不翻假，靠 Card 内 latchedText 维持展开。
                                var lastCompactionMsgId by remember { mutableStateOf<String?>(null) }
                                if (compactionActiveState != null) {
                                    lastCompactionMsgId = chatMessage.message.id
                                }
                                when (val compactionClaim = CompactionDividerPolicy.userTriggerClaim(
                                    chatMessage, compactionActiveState, lastCompactionMsgId,
                                )) {
                                    CompactionDividerSpec.V1TriggerHidden -> {
                                        // 零内容标记消息：不渲染（item 退化为一段 messageSpacing
                                        // 间隙，与消息间距同量级，无可感知残留）。
                                        return@itemsIndexed
                                    }
                                    is CompactionDividerSpec.Trigger -> {
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
                                    // 卡片展开态（内含压缩后的摘要全文）——与合成通知
                                    // 卡片一致的视觉语言）；长按仍触发撤销确认。
                                    // 2026-08-20 a11y P3：空 onClick 的 combinedClickable
                                    // 会被 TalkBack 朗读为可点击但无动作——改为纯
                                    // pointerInput 长按 + semantics 自定义无障碍动作
                                    //（长按=撤销确认，标签复用已翻译的 chat_revert）。
                                    val revertActionLabel = stringResource(R.string.chat_revert)
                                    // #221/#222：进行中压缩的展开流式增长——延迟
                                    // 揭示真·渲染前补偿（非进行中高度恒定，直通零成本）。
                                    val compactionGrowModifier = if (compactionClaim.activeState != null) {
                                        Modifier
                                            .fillMaxWidth()
                                            .clipToBounds()
                                            .deferredRevealCompensation(
                                                listState = listState,
                                                compensator = compactionReveal,
                                                shouldCompensate = { compensateState.shouldCompensate },
                                                logTag = "COMP-CMP(msg)",
                                            )
                                    } else Modifier
                                    Column(modifier = compactionGrowModifier
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = { showRevertDialog = true }
                                            )
                                        }
                                        .semantics {
                                            customActions = listOf(
                                                CustomAccessibilityAction(
                                                    label = revertActionLabel,
                                                    action = { showRevertDialog = true; true }
                                                )
                                            )
                                        }
                                    ) {
                                    CompactionCard(
                                            expanded = compactionExpandedStates[compactionClaim.expansionKey] ?: false,
                                            onExpandedChange = { compactionExpandedStates[compactionClaim.expansionKey] = it },
                                            state = compactionClaim.activeState,
                                            summary = compactionClaim.summary,
                                            failed = compactionClaim.failed
                                        )
                                    }
                                    return@itemsIndexed
                                    }
                                    CompactionDividerSpec.NotCompaction -> {
                                        // 非压缩认领——正常消息渲染路径（下方）。
                                    }
                                    // Tail/V1Summary 不出自 userTriggerClaim（穷举防御）。
                                    else -> { }
                                }

                                // 2026-08-13：「转后台」合成通知（服务器固定模板
                                // "User requested that active blocking work be moved to the
                                // background"）→ 分割线渲染（类似压缩分割线——简短提示
                                //「已移至后台」，丢弃服务器英文原文）
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
                                        // #67：合成通知（后台任务/subagent 完成注入的 synthetic 消息）用独立样式
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
                        }
                    }

                    // 分页加载指示器 —— 抓取更旧消息时出现在视觉顶部（reverseLayout）
                    if (messageState.isLoadingOlder) {
                        item(key = "loading_older") {
                            Box(modifier = Modifier.padding(bottom = messageSpacing)) {
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
                    }
                } // LazyColumn 结束（CompositionLocalProvider 内）
            } // CompositionLocalProvider 结束

            // 跳转加载指示器：jumpToMessage 目标未加载时双向加载期间显示（覆盖层）
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

            // 2026-08-13 跳转定位 loading 蒙版（用户建议——参考进入会话蒙版）：
            // 遮住定位过程的全部视口跳动/透明渲染/收敛修正——完成后直接显示
            // 目标（用户只看到 loading → 目标完整出现，无乱跳）。
            // 2026-08-21 D-11-2：phase 订阅下沉到小组件——蒙版显隐不再重组主体。
            JumpMaskOverlay(jumpController = jumpController)

            // 快速导航底部弹窗（B-F3：经 Host 包装——derived 读取发生在
            // Host 小作用域，且仅在 show=true 时订阅/重算）
            QuickNavigateHost(
                show = showQuickNavigate,
                jumpTargets = jumpTargets,
                questionMsgIdState = currentQuestionMsgIdState,
                anchorTimestampState = currentAnchorTimestampState,
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
                        viewModel.replyToPermission(perm.id, "always", perm.sessionId)
                        showAlwaysDialog = null
                    },
                    onDismiss = { showAlwaysDialog = null }
                )
            }
        } // Box(weight)
    } // Column
    } // CompositionLocalProvider(LocalCopyFeedback)
}



// C10 桥：LazyListLayoutInfo → AutoLoadLayoutSnapshot（决策输入的纯数据快照）
private fun LazyListLayoutInfo.toAutoLoadSnapshot(): AutoLoadLayoutSnapshot =
    AutoLoadLayoutSnapshot(
        totalItemsCount = totalItemsCount,
        visibleItems = visibleItemsInfo.map { VisibleItemSnapshot(it.index, it.offset, it.size) },
        viewportEndOffset = viewportEndOffset,
    )

/**
 * 提取 task/subagent 工具卡片的子智能体会话 ID（metadata.sessionId / sessionID / jobId）。
 * - V1 task 工具：metadata.sessionId
 * - V2 subagent 工具：服务器返回 metadata.jobId（= 子智能体会话 ID，task.ts:
 *   `metadata: {..., jobId: nextSession.id}`）——2026-08-11 实测发现
 *   键不匹配导致子智能体会话跳转/定位失效
 * - 轮次完成合成通知的 <task id> 与之匹配，用于「定位发起卡片」按钮。
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


// #215 验收反馈·一（终版裁决 2026-08-25）：toggleAnchorCorrection 修饰符与
// TOGGLE_ANCHOR_WINDOW_MS 已随方案三整体撤销（用户定规不用补偿逻辑，动画回
// M3 默认）；定因矩阵、request-position 通道竞争实测、scrollToBeConsumed
// 注入通道设计存档 journal §验收反馈·一 供未来复用。

// 预解析/分片调参常量已随渲染供给协调器外移 RenderSupplyCoordinator.companion（候选 1）。

/**
 * 跳转定位 loading 蒙版（2026-08-21 D-11-2 从主体下沉为小组件）：
 * 自行订阅 phase（Preparing/Measuring/Settling 显示）——蒙版显隐变化
 * 只重组本组件，不再重组 ChatMessageList ~1500 行主体。
 */
@Composable
private fun JumpMaskOverlay(jumpController: JumpNavigationController) {
    val phase by jumpController.phase.collectAsStateWithLifecycle()
    val show = phase is JumpPhase.Preparing || phase is JumpPhase.Measuring || phase is JumpPhase.Settling
    if (show) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                .pointerInput(Unit) { },
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
}
