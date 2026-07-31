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
 * Shared composable for both main-session and sub-session message lists.
 *
 * Contains: PullToRefreshBox > LazyColumn (pending questions/permissions, revert banner,
 * message items) + scroll-to-bottom FAB + streaming message card.
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
    // Structural signature — only changes when messages are added/removed/role-changed.
    // During SSE token streaming, part content updates every 48ms but message
    // structure stays the same → this signature prevents unnecessary recompute
    // of turnGroups and streamingMsgId during scrolling.
    val msgStructKey = remember(rawMessages) {
        rawMessages.size.toString() + rawMessages.joinToString(",") {
            it.message.id.take(12) + it.message.role.first().toString()
        }
    }

    val turnGroups = remember(msgStructKey) { computeTurnGroups(rawMessages) }

    // Pre-compute all rendering data for assistant display items.
    // Single remember block — runs only when rawMessages/displayItems change, not during composition.
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

    // Determine which message is currently streaming — based SOLELY on the
    // completed timestamp, NOT on sessionMeta.isStreaming. The latter was added
    // in 668384e3 but can fail to reflect active streaming (observed stuck
    // false in production), which forces streamingMsgId=null and disables ALL
    // height compensation → viewport dragged to bottom. v360 used completed
    // timestamp only and worked correctly. Do NOT re-add takeIf(sessionMeta).
    // Key on structural signature — streamingMsgId only changes when a new
    // streaming message starts or completes, not on every token batch.
    val streamingMsgId = remember(msgStructKey, rawMessages.lastOrNull()?.message?.time?.completed) {
        rawMessages.lastOrNull {
            it.isAssistant && it.message.time.completed == null
        }?.message?.id
    }

    // Key on streamingMsgId so state resets when streaming message changes (new message
    // or completion). This is simpler and more correct than heightMap + session-scope clear.
    val compensateState = remember(streamingMsgId) { CompensateState() }
    val toolCompensateState = remember(streamingMsgId) { CompensateState() }

    // Track whether user has scrolled away from bottom.
    // IMPORTANT: key on BOTH isScrollInProgress AND isAtBottom.
    // isAtBottom as a key is REQUIRED so shouldCompensate resets to false when
    // the user returns to the bottom via non-drag means (fling inertia, SSE
    // content push). Without it, shouldCompensate stays true at the bottom and
    // every SSE token fires requestScrollToItemNoCancel → viewport jitter.
    // This dual-key form is the beta.360-verified behavior; do NOT remove
    // isAtBottom from the key (see docs/research/sse-scroll-stability-iron-laws.md).
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) {
            compensateState.shouldCompensate = true
        } else if (isAtBottom) {
            compensateState.shouldCompensate = false
        }
    }

    // Real-time status from ChatRepository — domain types
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

    // Quick Navigate: extract jump targets + track current question
    val jumpTargets = remember(rawMessages) { extractJumpTargets(rawMessages) }

    val currentQuestionRawIndex by remember(rawMessages) {
        derivedStateOf { findCurrentQuestionRawIndex(listState, rawMessages) }
    }

    // Number of non-message items rendered before itemsIndexed in the LazyColumn.
    // MUST mirror the conditional `item { ... }` blocks below (see banner rendering).
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
            // reverseLayout=true: requestScrollToItemNoCancel places the item at the
            // viewport's scroll-start (visually the BOTTOM). A follow-up scrollBy moves
            // it up to the top so the jumped-to message is immediately readable.
            LazyListReflection.requestScrollToItemNoCancel(listState, lazyIndex, 0)
            listState.scroll {
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == lazyIndex }
                if (item != null) {
                    // negative delta = backward scroll = content shifts up = item rises to top
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

            // Custom FlingBehavior: caps per-frame scroll delta below LazyListMeasure's
            // fast-scroll estimation threshold. Without this cap, large fling velocities
            // produce per-frame deltas > viewportSize, triggering the estimation — but
            // ONLY for forward scroll (towards END/higher indices). This creates asymmetric
            // fling speed: scrolling towards END (older msgs in reverseLayout) is near-
            // instant (estimation skips items), while scrolling towards START (newer msgs)
            // is slow (every item composed). The cap + carry pattern ensures symmetric
            // behavior: each frame's scrollBy stays sub-threshold for BOTH directions,
            // and excess delta is carried to the next frame to preserve total distance.
            //
            // Root cause: the original chunking approach (inner while loop calling scrollBy
            // multiple times per frame) did NOT prevent estimation because all scrollBy
            // calls within one frame are accumulated into a single layout pass. The
            // estimation sees the TOTAL per-frame delta, not individual chunks.
            val safeFlingBehavior = remember {
                object : FlingBehavior {
                    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                        val absVel = kotlin.math.abs(initialVelocity)
                        if (absVel < 1f) return initialVelocity

                        var velocity = initialVelocity
                        val friction = 3f
                        val minVelocity = 50f
                        // Safely below viewport/2 on typical phones (viewport ≈ 600-800px).
                        // 200px ensures no fast-scroll estimation triggers for either direction.
                        val maxPerFrame = 200f
                        var carry = 0f
                        var lastFrame = withFrameNanos { it }

                        while (kotlin.math.abs(velocity) > minVelocity) {
                            val frame = withFrameNanos { it }
                            val dt = (frame - lastFrame).toFloat() / 1_000_000_000f
                            lastFrame = frame
                            if (dt <= 0f || dt > 0.1f) continue

                            // Per-frame delta = velocity * dt + carry from previous frame.
                            // carry preserves total scroll distance when capping kicks in.
                            val rawDelta = velocity * dt + carry
                            val delta = rawDelta.coerceIn(-maxPerFrame, maxPerFrame)
                            carry = rawDelta - delta

                            val consumed = scrollBy(delta)
                            if (kotlin.math.abs(consumed) < 0.5f) return velocity

                            // Exponential decay: v(t+dt) = v(t) * e^(-friction * dt)
                            velocity *= kotlin.math.exp(-friction * dt)
                        }
                        return velocity
                    }
                }
            }

            // Auto-pagination: trigger load when user is within 8 items of the top.
            // Replaces PullToRefreshBox — seamless, no manual gesture needed.
            //
            // CRITICAL: remember MUST be keyed on messageState. Without the key,
            // derivedStateOf captures the initial messageState (where hasOlderMessages=false)
            // and never sees updates when loadMessagesForSession() sets hasOlderMessages=true.
            // This was the root cause of pagination silently failing after session entry.
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
                    // reverseLayout=true: items declared first render at the BOTTOM.
                    // Visual order (top→bottom): oldest msgs → newest msgs → revert → pending.
                    // Declaration order is bottom-up: pending (bottom) → messages (top).

                    // Revert banner
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

                    // Compaction banner
                    if (currentCompaction != null && currentCompaction.isActive) {
                        item(key = "compaction_banner") {
                            CompactionBanner(state = currentCompaction)
                        }
                    }

                    // Retry banner — shown when session is in Retry status
                    val retryStatus = sessionMeta.sessionStatus
                    if (retryStatus is SessionStatus.Retry) {
                        item(key = "retry_banner") {
                            RetryBanner(retryStatus)
                        }
                    }

                    // Tool progress cards (with drift compensation)
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
                        // Reset when no active tools
                        toolCompensateState.lastHeight = 0
                    }

                    // Step progress indicator
                    if (currentStep != null) {
                        item(key = "step_progress") {
                            StepProgressIndicator(stepInfo = currentStep)
                        }
                    }

                    // Pending questions — show one at a time (oldest first)
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

                    // Pending permissions — show one at a time (oldest first)
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

                    // Chat messages: displayItems is already newest-first (descending).
                    // reverseLayout=true renders index 0 (newest) at the bottom.
                    // Visual result: oldest at top, newest at bottom.
                    itemsIndexed(
                        displayItems,
                        key = { _, (rawIndex, msg) ->
                            // Stable turn-based key: prevents item disposal when pagination
                            // loads more messages from the same turn (the representative
                            // message changes but the turn identity stays the same).
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

                    // Pagination loading indicator — appears at visual top (reverseLayout)
                    // when older messages are being fetched.
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

            // Scroll-to-bottom FAB
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

            // Quick navigate bottom sheet
            QuickNavigateSheet(
                show = showQuickNavigate,
                jumpTargets = jumpTargets,
                currentRawIndex = currentQuestionRawIndex,
                onJump = { msgId -> jumpToMessage(msgId) },
                onDismiss = onQuickNavigateDismiss,
            )

            // Always-allow confirmation dialog
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


