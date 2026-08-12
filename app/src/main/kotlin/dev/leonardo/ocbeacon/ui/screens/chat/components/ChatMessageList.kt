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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
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
import dev.leonardo.ocbeacon.ui.screens.chat.util.findCurrentQuestionMsgId
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatAssistantErrorMessage
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
    modifier: Modifier = Modifier,
) {
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

    // 当前可见问题（msgId 驱动，与 Room 全量列表的 JumpTarget.msgId 匹配高亮）。
    val currentQuestionMsgId by remember(rawMessages) {
        derivedStateOf { findCurrentQuestionMsgId(listState, rawMessages) }
    }

    // LazyColumn 中 itemsIndexed 之前渲染的非消息项数量。
    // 必须与下面的条件 `item { ... }` 块保持一致（见横幅渲染）。
    val bannerCount = remember(
        sessionMeta.revert,
        currentCompaction,
        sessionMeta.sessionStatus,
        activeTools,
        currentStep,
        interaction.pendingQuestions,
        interaction.pendingPermissions,
    ) {
        (if (sessionMeta.revert != null) 1 else 0) +
        (if (currentCompaction != null && currentCompaction.isActive) 1 else 0) +
        (if (sessionMeta.sessionStatus is SessionStatus.Retry) 1 else 0) +
        (if (activeTools.isNotEmpty()) 1 else 0) +
        (if (currentStep != null) 1 else 0) +
        (if (interaction.pendingQuestions.isNotEmpty()) 1 else 0) +
        (if (interaction.pendingPermissions.isNotEmpty()) 1 else 0)
    }

    // 高亮 key（3 秒后自动清除）—— scrollToDisplayItem / onLocateTask 共用。
    var highlightedTurnKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlightedTurnKey) {
        if (highlightedTurnKey != null) {
            delay(3000)
            highlightedTurnKey = null
        }
    }

    // jumpToMessage 的核心滚动 + 高亮（立即路径与异步路径共用）。
    fun scrollToDisplayItem(displayItemIndex: Int) {
        val (rawIndex, msg) = displayItems[displayItemIndex]
        val lazyIndex = bannerCount + displayItemIndex
        coroutineScope.launch {
            // reverseLayout=true：requestScrollToItemNoCancel 将项放置在
            // 视口滚动起点（视觉上的底部）。后续的 scrollBy 将其
            // 移到顶部，使跳转到的消息立即可读。
            LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
            listState.scroll {
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
                if (item != null) {
                    // 负 delta = 向后滚动 = 内容上移 = 项升至顶部
                    val delta = (info.viewportStartOffset - item.offset).toFloat()
                    scrollBy(delta)
                }
            }
        }
        highlightedTurnKey = if (msg.isUser) {
            "u_${msg.message.id}"
        } else {
            "t_${rawMessages.getOrNull(rawIndex + 1)?.message?.id ?: "head"}"
        }
    }

    // 快速导航异步定位：jumpToMessage 目标未加载时设此值，loadAround 完成后
    // 消息进入 displayItems → 此 LaunchedEffect 重启（displayItems 是 key）→ 滚动 + 高亮。
    // 以 displayItems 为 key：每次重组（新消息到达）都会重新检查目标是否已出现。
    var pendingJumpTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingJumpTarget, displayItems) {
        val target = pendingJumpTarget ?: return@LaunchedEffect
        val idx = displayItems.indexOfFirst { it.second.message.id == target }
        if (idx >= 0) {
            pendingJumpTarget = null
            scrollToDisplayItem(idx)
        }
    }

    /**
     * 快速导航跳转。
     *
     * - 目标已加载（在 displayItems）→ 直接 scrollToDisplayItem + 高亮。
     * - 目标未加载 → 触发 viewModel.loadAround 双向加载（前后各 N 条），
     *   设 pendingJumpTarget；LaunchedEffect 监听 displayItems，目标进入后滚动 + 高亮。
     *   （2026-08-12：修复"快速定位不准确"——原实现 displayItemIndex < 0 直接 return，
     *   目标未加载时静默失败。）
     */
    fun jumpToMessage(msgId: String) {
        val displayItemIndex = displayItems.indexOfFirst { it.second.message.id == msgId }
        if (displayItemIndex >= 0) {
            scrollToDisplayItem(displayItemIndex)
            onQuickNavigateDismiss()
        } else {
            // 未加载：触发异步定位加载，等待消息进入 displayItems 后滚动
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
            coroutineScope.launch {
                LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
                listState.scroll {
                    val info = listState.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
                    if (item != null) {
                        val delta = (info.viewportStartOffset - item.offset).toFloat()
                        scrollBy(delta)
                    }
                }
            }
            val (rawIndex, targetMsg) = displayItems[targetIndex]
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
            ) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d(
                        "ChatPaging",
                        "auto-load effect restart: hasOlder=${messageState.hasOlderMessages} isLoading=${messageState.isLoadingOlder} paused=${messageState.autoLoadPaused}"
                    )
                }
                if (messageState.hasOlderMessages && !messageState.isLoadingOlder && !messageState.autoLoadPaused) {
                    snapshotFlow { listState.layoutInfo }
                        .map { layoutInfo ->
                            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
                            val total = layoutInfo.totalItemsCount
                            if (BuildConfig.DEBUG && total > 0 && total - firstVisible <= 12) {
                                // 低频诊断：仅距顶 12 项内打印（滚动高频段不刷屏）
                                AppLogger.d("ChatPaging", "nearTop probe: firstVisible=$firstVisible total=$total dist=${total - firstVisible}")
                            }
                            total - firstVisible <= 8
                        }
                        .distinctUntilChanged()
                        .filter { it }
                        .collect {
                            val waitMs = viewModel.autoLoadWaitMillis()
                            if (waitMs > 0) {
                                if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load backoff wait ${waitMs}ms before retry")
                                delay(waitMs)
                            }
                            if (BuildConfig.DEBUG) AppLogger.d("ChatPaging", "auto-load triggered (nearTop=true, hasOlder=true)")
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
            ) {
                if (hasNewerMessages && !isLoadingNewer) {
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

            // loadAround 失败保护：加载结束（isLoadingAround=false）但目标未进入
            // displayItems（pendingJumpTarget 仍悬空）→ 清除并 Snackbar 提示，避免悬死。
            LaunchedEffect(isLoadingAround) {
                if (!isLoadingAround && pendingJumpTarget != null) {
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

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { keyboardController?.hide() }) },
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

                    // 待处理问题 —— 一次显示一个（最旧优先）
                    interaction.pendingQuestions.firstOrNull()?.let { question ->
                        item(key = "question_${question.id}") {
                            QuestionCard(
                                question = question,
                                positionLabel = if (interaction.pendingQuestions.size > 1) "1/${interaction.pendingQuestions.size}" else null,
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
                            if (msg.isUser) "u_${msg.message.id}"
                            else "t_${rawMessages.getOrNull(rawIndex + 1)?.message?.id ?: "head"}"
                        },
                        contentType = { _, item -> if (item.second.isUser) "user" else "assistant" }
                    ) { displayItemIndex, (rawIndex, msg) ->
                        val itemKey = if (msg.isUser) "u_${msg.message.id}"
                            else "t_${rawMessages.getOrNull(rawIndex + 1)?.message?.id ?: "head"}"
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
                                    onCopy = {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                        }
                                    },
                                    onLocateTask = onLocateTask,
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
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { },
                                                onLongClick = { showRevertDialog = true }
                                            )
                                            .padding(vertical = SpacingTokens.XS.dp, horizontal = SpacingTokens.XXL.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        HorizontalDivider(
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                                        )
                                        Text(
                                            text = stringResource(R.string.chat_summarized),
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
                }

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

            // 快速导航底部弹窗
            QuickNavigateSheet(
                show = showQuickNavigate,
                jumpTargets = jumpTargets,
                currentMsgId = currentQuestionMsgId,
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
