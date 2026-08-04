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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.withFrameNanos
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
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.StepProgressInfo
import dev.leonardo.ocbeacon.domain.model.ToolProgressInfo
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
import dev.leonardo.ocbeacon.ui.screens.chat.util.findCurrentQuestionRawIndex
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatAssistantErrorMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

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
    // 结构签名 —— 仅在消息被添加/移除/角色变化时改变。
    // SSE token 流式期间，part 内容每 48ms 更新，但消息
    // 结构保持不变 → 该签名可防止滚动时不必要的
    // turnGroups 和 streamingMsgId 重算。
    val msgStructKey = remember(rawMessages) {
        rawMessages.size.toString() + rawMessages.joinToString(",") {
            it.message.id.take(12) + it.message.role.first().toString()
        }
    }

    val turnGroups = remember(msgStructKey) { computeTurnGroups(rawMessages) }

    // 预计算 assistant 显示项的全部渲染数据。
    // 单个 remember 块 —— 仅在 rawMessages/displayItems 变化时运行，而非组合期间。
    val renderableTurns: List<RenderableTurn?> = remember(rawMessages, displayItems, turnGroups) {
        displayItems.map { (rawIndex, msg) ->
            if (!msg.isAssistant) return@map null
            val turnMsgs = turnGroups[rawIndex] ?: listOf(msg)
            val isTurnLast = rawIndex == rawMessages.lastIndex ||
                rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true
            computeRenderableTurn(
                turnMessages = turnMsgs,
                currentMessage = msg,
                isTurnLast = isTurnLast,
                formatError = ::formatAssistantErrorMessage,
            )
        }
    }

    // 判断当前哪条消息在流式输出 —— 仅基于 completed 时间戳，
    // 而非 sessionMeta.isStreaming。后者是在 668384e3 中加入的，
    // 但可能无法反映活跃流式状态（生产环境观察到 stuck false），
    // 导致 streamingMsgId=null 并禁用所有高度补偿 → 视口被拖到底部。
    // v360 只用 completed 时间戳，工作正常。不要再加 takeIf(sessionMeta)。
    // 以结构签名作为 key —— streamingMsgId 仅在新流式消息开始或
    // 完成时变化，而非每个 token 批次。
    val streamingMsgId = remember(msgStructKey, rawMessages.lastOrNull()?.message?.time?.completed) {
        rawMessages.lastOrNull {
            it.isAssistant && it.message.time.completed == null
        }?.message?.id
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

    // 来自 ChatRepository 的实时状态 —— 领域类型
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

    // 快速导航：提取跳转目标 + 跟踪当前问题
    val noTextPlaceholder = stringResource(R.string.no_text)
    val jumpTargets = remember(rawMessages, noTextPlaceholder) {
        extractJumpTargets(rawMessages, noTextPlaceholder)
    }

    val currentQuestionRawIndex by remember(rawMessages) {
        derivedStateOf { findCurrentQuestionRawIndex(listState, rawMessages) }
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

    fun jumpToMessage(msgId: String) {
        val displayItemIndex = displayItems.indexOfFirst { it.second.message.id == msgId }
        if (displayItemIndex < 0) return
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
        onQuickNavigateDismiss()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            var showAlwaysDialog by remember { mutableStateOf<SseEvent.PermissionAsked?>(null) }

            // 自定义 FlingBehavior：将每帧滚动 delta 限制在 LazyListMeasure 的
            // 快速滚动估算阈值之下。没有此限制，较大的 fling 速度会
            // 产生超过 viewportSize 的每帧 delta，从而触发估算 —— 但
            // 仅对向前滚动（朝向 END/更大索引）生效。这导致不对称的
            // fling 速度：向 END 滚动（reverseLayout 中的较旧消息）几乎是
            // 瞬时的（估算跳过项），而向 START 滚动（较新消息）
            // 很慢（每项都组合）。cap + carry 模式确保了对称
            // 行为：每一帧的 scrollBy 对两个方向都低于阈值，
            // 多余的 delta 会带入下一帧以保持总距离。
            //
            // 根本原因：原始的切块方案（每帧在内部 while 循环中多次调用 scrollBy）
            // 并不能阻止估算，因为同一帧内的所有 scrollBy 调用
            // 会累计到一次布局传递中。估算看到的是每帧总 delta，
            // 而不是单个块。
            val safeFlingBehavior = remember {
                object : FlingBehavior {
                    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                        val absVel = kotlin.math.abs(initialVelocity)
                        if (absVel < 1f) return initialVelocity

                        var velocity = initialVelocity
                        val friction = 3f
                        val minVelocity = 50f
                        // 在典型手机上安全地低于 viewport/2（viewport ≈ 600-800px）。
                        // 200px 确保任一方向都不会触发快速滚动估算。
                        val maxPerFrame = 200f
                        var carry = 0f
                        var lastFrame = withFrameNanos { it }

                        while (kotlin.math.abs(velocity) > minVelocity) {
                            val frame = withFrameNanos { it }
                            val dt = (frame - lastFrame).toFloat() / 1_000_000_000f
                            lastFrame = frame
                            if (dt <= 0f || dt > 0.1f) continue

                            // 每帧 delta = velocity * dt + 上一帧的 carry。
                            // carry 在限幅生效时保持总滚动距离。
                            val rawDelta = velocity * dt + carry
                            val delta = rawDelta.coerceIn(-maxPerFrame, maxPerFrame)
                            carry = rawDelta - delta

                            val consumed = scrollBy(delta)
                            if (kotlin.math.abs(consumed) < 0.5f) return velocity

                            // 指数衰减：v(t+dt) = v(t) * e^(-friction * dt)
                            velocity *= kotlin.math.exp(-friction * dt)
                        }
                        return velocity
                    }
                }
            }

            // 自动分页：用户距顶部 8 项以内时触发加载。
            // 取代 PullToRefreshBox —— 无缝，无需手动手势。
            //
            // 关键：remember 必须以 messageState 为 key。没有这个 key，
            // derivedStateOf 会捕获初始 messageState（其中 hasOlderMessages=false）
            // 并且当 loadMessagesForSession() 将 hasOlderMessages 设为 true 时
            // 永远看不到更新。这是进入会话后分页静默失败的根源。
            val shouldPaginate by remember(messageState) {
                derivedStateOf {
                    val layoutInfo = listState.layoutInfo
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val total = layoutInfo.totalItemsCount
                    !messageState.isLoadingOlder &&
                    messageState.hasOlderMessages &&
                    total - lastVisible <= 8
                }
            }
            LaunchedEffect(shouldPaginate) {
                if (shouldPaginate) viewModel.loadOlderMessages()
            }

                LazyColumn(
                    state = listState,
                    flingBehavior = safeFlingBehavior,
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
                        Box(modifier = itemModifier) {
                        when {
                            msg.isAssistant -> {
                                val isTurnLast = rawIndex == rawMessages.lastIndex || rawMessages.getOrNull(rawIndex + 1)?.isAssistant != true

                                MessageCard(
                                    role = MessageCardRole.ASSISTANT,
                                    renderableTurn = renderableTurns[displayItemIndex],
                                    currentMessage = msg,
                                    onViewSubSession = navigateToChildSession,
                                    onOpenFile = onOpenFile,
                                    isAmoled = isAmoled,
                                    isTurnLast = isTurnLast,
                                    agents = agents,
                                    onCopy = {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_copied_clipboard))
                                        }
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
                                    role = MessageCardRole.USER,
                                    currentMessage = chatMessage,
                                    isQueued = chatMessage.message.id in messageState.queuedMessageIds,
                                    pendingStatus = messageState.pendingMessages.find { it.pendingId == chatMessage.message.id }?.status,
                                    onRetry = { viewModel.retrySendMessage(chatMessage.message.id) },
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
                currentRawIndex = currentQuestionRawIndex,
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


