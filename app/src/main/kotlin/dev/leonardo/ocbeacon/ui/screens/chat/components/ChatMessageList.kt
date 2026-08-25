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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
/** #227：压缩尾部兜底分割线的展开表键——V1 本地置态 messageId 为空串，无真实 id 可用。 */
private const val COMPACTION_TAIL_EXPANSION_KEY = "compaction_banner_tail"

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

    // #217：当前渲染列表的消息 id 集——尾部进行中压缩分割线的去重判据
    //（压缩消息已在列表中时，由消息流内同 item 分割线承担，尾部不再出线）。
    val displayItemMessageIds = remember(displayItems) {
        displayItems.map { it.second.message.id }.toSet()
    }

    // #226：V1 摘要消息（assistant(agent=compaction)）已在列表——进行中压缩由
    // 消息流内该 item 的活跃分割线承担，尾部兜底线让位（V1 本地置态 messageId
    // 为空串永不命中对位判据，此前尾部线全程在场 → 与摘要线/气泡三元素同屏）。
    // V2 消息不带 agent=compaction，恒 false 零影响。
    val v1CompactionSummaryInList = remember(displayItems) {
        displayItems.any { entry ->
            val m = entry.second.message
            m is Message.Assistant && m.agent == "compaction"
        }
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

    // #227：V1 尾部→消息流展开态交接桥——尾部线（固定键）让位给摘要消息（真实
    // id 键）时把展开态搬过去，随后清源键。「完成不收起」（#221 裁决）在 V1
    // 交接路径同样成立；无展开记录时零操作。
    androidx.compose.runtime.LaunchedEffect(v1CompactionSummaryInList) {
        if (v1CompactionSummaryInList) {
            val tailExpansion = compactionExpandedStates[COMPACTION_TAIL_EXPANSION_KEY]
            if (tailExpansion != null) {
                displayItems
                    .firstOrNull { entry ->
                        val m = entry.second.message
                        m is Message.Assistant && m.agent == "compaction"
                    }
                    ?.let { compactionExpandedStates[it.second.message.id] = tailExpansion }
                compactionExpandedStates.remove(COMPACTION_TAIL_EXPANSION_KEY)
            }
        }
    }

    // LazyColumn 中 itemsIndexed 之前渲染的非消息项数量。
    // 必须与下面的条件 `item { ... }` 块保持一致（见横幅渲染）。
    val bannerCount = remember(
        sessionMeta.revert,
        currentCompaction,
        displayItemMessageIds,
        v1CompactionSummaryInList,
        sessionMeta.sessionStatus,
        activeTools,
        currentStep,
        unembeddedQuestions,
        interaction.pendingPermissions,
    ) {
        (if (sessionMeta.revert != null) 1 else 0) +
        (if (currentCompaction != null && currentCompaction.isActive &&
            (currentCompaction.messageId in displayItemMessageIds || v1CompactionSummaryInList)) 1 else 0) +
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
        currentCompaction,
        displayItemMessageIds,
        v1CompactionSummaryInList,
    ) {
        (if (sessionMeta.sessionStatus is SessionStatus.Retry) 1 else 0) +
            (if (activeTools.isNotEmpty()) 1 else 0) +
            (if (currentStep != null) 1 else 0) +
            (if (currentCompaction?.isActive == true &&
                currentCompaction.messageId !in displayItemMessageIds &&
                !v1CompactionSummaryInList) 1 else 0)
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
                            val waitMs = viewModel.conversation.paginationDelegate.autoLoadWaitMillis()
                            if (waitMs > 0) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load backoff wait ${waitMs}ms before retry")
                                delay(waitMs)
                            }
                            // 2026-08-21 竞态修复（同日根因完备化）：!jumpLockActive 只在
                            // effect 启动时检查一次——跳转滚动使 nearTop 在旧实例 collect 里
                            // 发射时（标志翻转与 effect 重启之间有重组延迟窗口——重组帧驱动、
                            // snapshotFlow 发射提交驱动，二者排序无保证，跳转重载下窗口
                            // 实测拉宽到 136ms+），启动闸门已失效 → settle 期间数据变动。
                            // 修复 = 正确的时机 × 正确的源：fire-time 复查 + 直读 phase 真源
                            //（isJumpInProgress 同步快照——不经派生锁的组合帧滞后）。
                            if (jumpController.isJumpInProgress) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load skipped (jump in progress at fire time)")
                                return@collect
                            }
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load triggered (hasOlder=true)")
                            viewModel.conversation.paginationDelegate.loadOlderMessages()
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
                            // 2026-08-21 竞态修复（同日根因完备化，真机日志实证：
                            // jumpToMessage 置锁后 +136ms nearBottom 发射仍漏过启动时
                            // 闸门 → 渐进步进卡 gap=-343 空转 7 次、蒙版多挂 ~2s）。
                            // 修复 = fire-time 复查 + 直读 phase 真源（isJumpInProgress）。
                            if (jumpController.isJumpInProgress) {
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
                    // 尾部兜底分割线：仅当进行中压缩对应的消息还不在渲染列表
                    //（V2 进行期消息未刷新入列 / V1 无 messageId）——消息已对位
                    // 时由消息流内同一 item 承担（避免双分割线）。
                    val tailCompaction = currentCompaction
                        ?.takeIf {
                            it.isActive && it.messageId !in displayItemMessageIds &&
                                // #226：V1 摘要消息入列后由消息流内活跃线承担
                                !v1CompactionSummaryInList
                        }
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
                                    // #227：展开态入表（V2=真实 messageId，尾部→消息流同键交接）
                                    val tailKey = tailCompaction.messageId
                                        .ifBlank { COMPACTION_TAIL_EXPANSION_KEY }
                                    CompactionCard(
                                        state = tailCompaction,
                                        expanded = compactionExpandedStates[tailKey] ?: false,
                                        onExpandedChange = { compactionExpandedStates[tailKey] = it },
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
                                // #226：V1 摘要消息认领——assistant(agent=compaction) 一律
                                // 渲染分割线，与 V2 同构（「一条压缩 = 一条分割线」）：
                                // - 未完结（归一化器完结守卫放行、parts 为流式 text）→
                                //   伪活跃态分割线：骑线进度 + 可展开流式摘要——取代
                                //   此前的普通气泡流式（用户裁决 #217：压缩输出不得在
                                //   气泡中；气泡→完成态分割线的形态突变即「闪现」主源）。
                                // - 已完结（CompactionNormalizer 折叠为单个 Part.Compaction）
                                //   → 完成态分割线 + 摘要可达——修复此前 PartContent 跳过
                                //   Compaction 导致的空 turn（摘要渲染为空、不可达）。
                                // 同 item 原位切换保留 Q13 连续性（latchedText 跨完成保持）。
                                val asstInfo = msg.message as? Message.Assistant
                                if (asstInfo != null && asstInfo.agent == "compaction") {
                                    val compPart = msg.parts
                                        .filterIsInstance<Part.Compaction>()
                                        .firstOrNull()
                                    val v1Active = compPart == null &&
                                        asstInfo.time.completed == null && asstInfo.error == null
                                    var showRevertDialog by remember { mutableStateOf(false) }
                                    if (showRevertDialog) {
                                        ConfirmDialog(
                                            title = stringResource(R.string.chat_revert_title),
                                            message = stringResource(R.string.chat_revert_message),
                                            confirmLabel = stringResource(R.string.chat_revert),
                                            onDismiss = { showRevertDialog = false },
                                            onConfirm = {
                                                showRevertDialog = false
                                                // 撤销边界取压缩触发消息（V1 语义：撤到压缩
                                                // 点之前恢复被压前文；摘要消息 id 会留下
                                                // 触发残骸）——即列表中紧邻其前、带
                                                // Compaction part 的 user 消息；找不到退自身。
                                                val revertTarget =
                                                    displayItems.getOrNull(displayItemIndex - 1)
                                                        ?.second
                                                        ?.takeIf { prev ->
                                                            prev.message is Message.User &&
                                                                prev.parts.any { it is Part.Compaction }
                                                        }?.message?.id ?: msg.message.id
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
                                    val v1GrowModifier = if (v1Active && !isStreamingMsg) {
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
                                            expanded = compactionExpandedStates[msg.message.id] ?: false,
                                            onExpandedChange = { compactionExpandedStates[msg.message.id] = it },
                                            state = if (v1Active) {
                                                val liveSummary = msg.parts
                                                    .filterIsInstance<Part.Text>()
                                                    .joinToString("\n\n") { it.text }
                                                    .trim()
                                                dev.leonardo.ocbeacon.domain.model.CompactionStateInfo(
                                                    isActive = true,
                                                    reason = "",
                                                    deltaText = liveSummary,
                                                    messageId = msg.message.id,
                                                )
                                            } else null,
                                            summary = compPart?.summary,
                                            failed = compPart?.failed ?: (asstInfo.error != null),
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


                                // #217：进行中态按 messageId 对位——仅当前压缩对应的
                                // 消息渲染进行中分割线（历史分割线不受新压缩影响）；同 item
                                // 原位切换保证 Q13 展开/流式文本连续（messageId 来自 started
                                // 事件 inputID，与 compaction 消息 id 同源）。
                                val compactionActiveState = currentCompaction
                                    ?.takeIf { it.isActive && it.messageId == chatMessage.message.id }

                                // #221（展开跨完成保持）：ended 清态 → REST 刷新带入
                                // Part.Compaction 前存在空窗——此时 role=compaction 且无
                                // part，认领条件若翻假，CompactionCard 会离开组合（内部
                                // remember 含 expanded/latchedText 全部丢失）→ REST 回来
                                // 重新组合即收起。锁存最近一次进行中对位到的 messageId：
                                // 同 item 组合存续，空窗期靠 Card 内 latchedText 维持展开。
                                var lastCompactionMsgId by remember { mutableStateOf<String?>(null) }
                                if (compactionActiveState != null) {
                                    lastCompactionMsgId = chatMessage.message.id
                                }

                                // #219 修复二（进行中分割线消失）：inbox.enqueued 在压缩
                                // 发起瞬间即插入 role=compaction 骨架消息（无 Part.Compaction
                                // ——那要等完成后的 REST 刷新）。此前仅按 parts 判定 → 骨架期
                                // 消息流内不渲染分割线；#219 勘误 inputID 后尾部分割线的去重
                                // 条件（messageId 已在列表）又被骨架满足 → 进行中态两边都不
                                // 显示，直到完成才蹦出。按 role+对位认领：started 到达后骨架
                                // 即渲染进行中分割线，完成后同 item 原位切完成态（Q13 本意）。
                                // 注意 steer 排队期（skeleton 已入列但 started 未到）不认领——
                                // compactionState 未置，认领会渲染成静止「已压缩」误导。
                                //
                                // #226：V1 触发消息（role=user + Compaction part、无摘要——
                                // V1 契约里摘要住在后随 assistant(agent=compaction) 消息）
                                // 不再渲染分割线：此前它以静止「已压缩」形态与摘要线/尾部
                                // 活跃线三元素同屏（且进行中误导为完成态）。压缩的视觉呈现
                                // 由摘要消息（见 assistant 分支）与尾部兜底线全权承担。
                                // 撤销入口同步移至摘要消息的分割线（长按）。
                                // 热修（2026-08-26 用户即报）：初版条件
                                // `firstOrNull()?.summary.isNullOrBlank()` 在**无** Compaction
                                // part 的普通用户消息上求值 = null.isNullOrBlank() = true
                                // → 全部用户气泡被隐藏（Kotlin 空安全惯用陷阱）。必须
                                // 显式要求 part 存在且 summary 为空才算触发消息。
                                val v1TriggerCompactionPart = chatMessage.parts
                                    .filterIsInstance<Part.Compaction>()
                                    .firstOrNull()
                                val isV1CompactionTriggerMsg =
                                    (chatMessage.message as? Message.User)?.role == "user" &&
                                        v1TriggerCompactionPart != null &&
                                        v1TriggerCompactionPart.summary.isNullOrBlank()
                                if (isV1CompactionTriggerMsg) {
                                    // 零内容标记消息：不渲染（item 退化为一段 messageSpacing
                                    // 间隙，与消息间距同量级，无可感知残留）。
                                    return@itemsIndexed
                                }
                                val isCompactionTrigger = chatMessage.parts.any { it is Part.Compaction } ||
                                    ((chatMessage.message as? Message.User)?.role == "compaction" &&
                                        (compactionActiveState != null ||
                                            chatMessage.message.id == lastCompactionMsgId))

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
                                    // 卡片展开态（内含压缩后的摘要全文）——与合成通知
                                    // 卡片一致的视觉语言）；长按仍触发撤销确认。
                                    // 2026-08-20 a11y P3：空 onClick 的 combinedClickable
                                    // 会被 TalkBack 朗读为可点击但无动作——改为纯
                                    // pointerInput 长按 + semantics 自定义无障碍动作
                                    //（长按=撤销确认，标签复用已翻译的 chat_revert）。
                                    val revertActionLabel = stringResource(R.string.chat_revert)
                                    // #221/#222：进行中压缩的展开流式增长——延迟
                                    // 揭示真·渲染前补偿（非进行中高度恒定，直通零成本）。
                                    val compactionGrowModifier = if (compactionActiveState != null) {
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
                                            expanded = compactionExpandedStates[chatMessage.message.id] ?: false,
                                            onExpandedChange = { compactionExpandedStates[chatMessage.message.id] = it },
                                            state = compactionActiveState,
                                            summary = chatMessage.parts
                                                .filterIsInstance<Part.Compaction>()
                                                .firstOrNull()?.summary,
                                            failed = chatMessage.parts
                                                .filterIsInstance<Part.Compaction>()
                                                .firstOrNull()?.failed ?: false
                                        )
                                    }
                                    return@itemsIndexed
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

/**
 * 标准 easeInOutCubic 缓动（缓-快-缓）——跳转滚动动画（2026-08-13 恢复：
 * 去动画后视口瞬间跳动产生"闪"感——平滑滚动 + 透明门控组合根治）。
 */
private fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t else 1f - ((-2f * t + 2f) * (-2f * t + 2f) * (-2f * t + 2f)) / 2f

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
