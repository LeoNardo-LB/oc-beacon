package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.buildAnnotatedString

import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.times
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.AgentInfo
import dev.leonardo.ocbeacon.domain.model.CommandInfo
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.service.SessionNotificationCoordinator
import dev.leonardo.ocbeacon.MainActivity
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

import android.net.Uri
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory

import android.os.Build
import android.util.Base64
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.leonardo.ocbeacon.BuildConfig
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.components.ProviderIcon
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.agentColorCycle
import dev.leonardo.ocbeacon.ui.theme.QueuedBadgeColor
import dev.leonardo.ocbeacon.ui.theme.QueuedBadgeTextColor
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatTokenCount
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatAssistantErrorMessage
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatDuration
import dev.leonardo.ocbeacon.ui.screens.chat.util.resolveUserCommandLabel
import dev.leonardo.ocbeacon.ui.screens.chat.util.performHaptic
import dev.leonardo.ocbeacon.ui.screens.chat.util.codeHorizontalScroll
import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalCollapseTools
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalExpandReasoning
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalShowTurnDividers
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalImageSaveRequest
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalSessionDiffs
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalSessionStreaming
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalToolExpandedStates
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalToolCardResolver
import dev.leonardo.ocbeacon.ui.screens.chat.util.ImageAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.PreparedAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.decodeDataUrlBytes
import dev.leonardo.ocbeacon.ui.screens.chat.util.decodePartFileBytes
import dev.leonardo.ocbeacon.ui.screens.chat.util.imageThumbnailModel
import dev.leonardo.ocbeacon.ui.screens.chat.util.estimateVisionTokens
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownContent
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.SimpleMarkdownTable
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.looksLikeHtmlPayload
import dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeHtmlForEmbeddedPreview
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ToolCallCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.BashToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.EditToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.ReadToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.SearchToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.TaskToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.TodoListCard
import dev.leonardo.ocbeacon.ui.screens.chat.tools.cards.WriteToolCard
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ModelPickerDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ImageThumbnailRow
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.ImagePreviewDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCard
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.PermissionCard
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatInputBar
import dev.leonardo.ocbeacon.ui.screens.chat.input.ChatInputMode
import dev.leonardo.ocbeacon.ui.screens.chat.util.SlashCommand
import dev.leonardo.ocbeacon.ui.screens.chat.input.rememberAttachmentHandler
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.util.PromptBuilder
import dev.leonardo.ocbeacon.ui.screens.chat.components.MessageCard
import dev.leonardo.ocbeacon.ui.screens.chat.components.MessageCardRole
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatEmptyState
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatErrorState
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatMessageList
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatTopBar
import dev.leonardo.ocbeacon.ui.screens.chat.components.ErrorPayloadContent
import dev.leonardo.ocbeacon.ui.components.indicators.PulsingDotsIndicator
import dev.leonardo.ocbeacon.ui.screens.chat.components.RevertBanner
import dev.leonardo.ocbeacon.ui.screens.chat.terminal.ChatTerminalView
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.RenameSessionDialog
import dev.leonardo.ocbeacon.ui.screens.chat.dialog.SendConfirmDialog
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalOnViewTool
import dev.leonardo.ocbeacon.ui.screens.chat.tools.ViewToolRequest
import dev.leonardo.ocbeacon.ui.screens.chat.util.snapToBottom
import dev.leonardo.ocbeacon.ui.screens.viewer.FileViewerOverlay
import dev.leonardo.ocbeacon.ui.screens.viewer.FileViewerParams
import dev.leonardo.ocbeacon.ui.screens.viewer.FileViewerSource
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens


/**
 * Chat Screen - conversation view with native markdown rendering.
 * Shows messages with streaming text rendered via mikepenz markdown renderer.
 */

private const val TAG_SCROLL = "ChatScroll"

// jumpToBottom / animateScrollToBottom removed — reverseLayout=true anchors at bottom natively.




@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    serverId: String,
    sessionId: String,
    onNavigateBack: () -> Unit,
    onNavigateToSession: (sessionId: String) -> Unit = {},
    onNavigateToChildSession: (String) -> Unit = {},
    onOpenInWebView: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenFile: (filePath: String) -> Unit = {},
    onOpenDirectory: (directoryPath: String) -> Unit = {},
    checkFileExists: suspend (filePath: String) -> Boolean = { true },
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    startInTerminalMode: Boolean = false,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messageState by viewModel.messageListState.collectAsStateWithLifecycle()
    val sessionMeta by viewModel.sessionMetaState.collectAsStateWithLifecycle()
    val interaction by viewModel.interactionState.collectAsStateWithLifecycle()
    val tokenStats by viewModel.tokenStatsState.collectAsStateWithLifecycle()
    val modelConfig by viewModel.modelConfigState.collectAsStateWithLifecycle()
    val directory by viewModel.directoryState.collectAsStateWithLifecycle()
    val contextDetail by viewModel.contextDetailState.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.restoredDraftState.collectAsStateWithLifecycle()
    val draftText by viewModel.draftText.collectAsStateWithLifecycle()
    val draftAttachmentUris by viewModel.draftAttachmentUris.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // Sync inputText once from draft on first composition
    var draftTextInitialized by remember { mutableStateOf(false) }
    if (!draftTextInitialized && draftText.isNotEmpty()) {
        inputText = TextFieldValue(draftText, TextRange(draftText.length))
        draftTextInitialized = true
    } else if (!draftTextInitialized) {
        draftTextInitialized = true
    }
    // Listen for revert events that should restore text to the input field
    LaunchedEffect(Unit) {
        viewModel.revertedDraftEvent.collect { payload ->
            inputText = TextFieldValue(payload.text, TextRange(payload.text.length))
        }
    }
    // listState is hoisted to ViewModel — survives navigation.
    val listState = viewModel.listState

    var autoScrollEnabled by rememberSaveable { mutableStateOf(true) }
    var forceScrollTick by remember { mutableIntStateOf(0) }

    // FileViewer overlay state — replaces navigation to FileViewerNav route.
    var fileViewerRequest by remember { mutableStateOf<FileViewerParams?>(null) }
    val handleOpenFile: (String) -> Unit = { filePath ->
        fileViewerRequest = FileViewerParams(
            serverId = serverId,
            sessionId = sessionId,
            filePath = filePath,
            directory = directory,
            source = FileViewerSource.LIVE
        )
    }

    val isAtBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset < 100
        }
    }

    // IMPORTANT: key on BOTH isScrollInProgress AND isAtBottom.
    // isAtBottom as a key lets this effect re-evaluate when the user returns to
    // the bottom via non-drag means (fling inertia, SSE content push, compensation
    // scroll) — isScrollInProgress alone misses those transitions, leaving
    // autoScrollEnabled stuck stale. This dual-key form is the beta.360-verified
    // behavior; do NOT remove isAtBottom from the key (see
    // docs/research/sse-scroll-stability-iron-laws.md).
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress) {
            autoScrollEnabled = false
        } else if (isAtBottom) {
            autoScrollEnabled = true
        }
    }

    val messageCount = messageState.messages.size
    LaunchedEffect(messageCount) {
        if (messageCount > 0 && autoScrollEnabled && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(forceScrollTick) {
        if (forceScrollTick > 0) {
            listState.snapToBottom()
        }
    }

    val pendingCount = interaction.pendingQuestions.size + interaction.pendingPermissions.size
    LaunchedEffect(pendingCount) {
        if (pendingCount > 0 && autoScrollEnabled) {
            snapshotFlow { messageState.messages.isNotEmpty() }.first { it }
            listState.snapToBottom()
        }
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isTerminalMode by rememberSaveable { mutableStateOf(startInTerminalMode) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val linkUriHandler = rememberLinkUriHandler(
        directory = directory,
        onOpenFile = handleOpenFile,
        onOpenDirectory = onOpenDirectory,
        fileChecker = checkFileExists,
        snackbarHostState = snackbarHostState,
        coroutineScope = coroutineScope,
    )
    val context = LocalContext.current

    // Pre-warm WebView V8 engine on first ChatScreen entry so the first file
    // open isn't slow. One-time per process; throwaway WebView auto-destroys.
    // Delay 500ms to let initial composition + first scroll frame settle.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        dev.leonardo.ocbeacon.ui.screens.viewer.WebViewWarmer.warm(context)
    }
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val view = LocalView.current
    val density = LocalDensity.current
    // isAtBottomBeforeIme removed — reverseLayout=true handles IME natively.

    // Dismiss keyboard only on genuine user scroll (isScrollInProgress),
    // not on programmatic scrolls or layout changes.
    var lastScrollIndex by remember { mutableIntStateOf(listState.firstVisibleItemIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, index) ->
                if (scrolling && index != lastScrollIndex) {
                    keyboardController?.hide()
                    lastScrollIndex = index
                }
            }
    }

    // IME scroll LaunchedEffect removed — reverseLayout=true anchors at bottom,
    // keyboard push content naturally without explicit scroll.

    // @ file mention state
    val fileSearchResults by viewModel.fileSearchResults.collectAsStateWithLifecycle()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsStateWithLifecycle()

    // Settings used directly in ChatScreen
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticFeedback.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsStateWithLifecycle()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsStateWithLifecycle()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsStateWithLifecycle()
    // File diffs for the current session — backs PatchCard line counts (+N -N)
    val sessionDiffs by viewModel.chatRepositoryExposed
        .getSessionDiffsForSession(viewModel.sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showSendConfirmDialog by remember { mutableStateOf(false) }
    // Pending send action: stored so the confirm dialog can trigger it
    var pendingSendAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.NORMAL.name) }
    val isShellMode = inputMode == ChatInputMode.SHELL.name

    // Keep screen on while on chat screen (if enabled in settings)
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Attachment handler (image picker, SAF export, image save, draft restore)
    val attachmentHandler = rememberAttachmentHandler(
        draftAttachmentUris = draftAttachmentUris,
        compressImages = compressImageAttachments,
        imageMaxLongSide = imageAttachmentMaxLongSide,
        imageWebpQuality = imageAttachmentWebpQuality,
        initialSharedImages = initialSharedImages,
        onSharedImagesConsumed = onSharedImagesConsumed,
        onAddDraftAttachment = { viewModel.addDraftAttachment(it) },
        onRemoveDraftAttachment = { viewModel.removeDraftAttachment(it) },
        onExportSession = { ctx, uri, callback -> viewModel.exportSession(ctx, uri, callback) },
        onShowSnackbar = { msg -> snackbarHostState.showSnackbar(msg) },
    )
    val attachments = attachmentHandler.attachments

    // Show errors as snackbar when messages are already loaded + scroll to bottom
    LaunchedEffect(interaction.error) {
        val error = interaction.error
        if (error != null && messageState.messages.isNotEmpty()) {
            listState.snapToBottom()
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    // Notification lifecycle: cancel existing + set active focus on enter, clear on leave.
    // SessionNotificationCoordinator marks this session as active so new notifications
    // for it are suppressed while the user is viewing it.
    LaunchedEffect(viewModel.sessionId) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        viewModel.onSessionFocused(notificationManager)
        if (serverId.isNotBlank() && viewModel.sessionId.isNotBlank()) {
            SessionNotificationCoordinator.activate(serverId, viewModel.sessionId)
        }
    }
    DisposableEffect(viewModel.sessionId) {
        onDispose {
            viewModel.onSessionUnfocused()
            SessionNotificationCoordinator.deactivate()
        }
    }

    // Sync session status when entering a session (REST fallback for missed SSE events)
    LaunchedEffect(viewModel.sessionId) {
        if (viewModel.sessionId.isNotBlank()) {
            viewModel.syncSessionStatus()
        }
    }

    // Refresh session when returning from background (lock screen / app switch).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && viewModel.sessionId.isNotBlank()) {
                viewModel.refreshIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Stable lambdas / values for CompositionLocals — prevents unnecessary recomposition
    // of all consumers when ChatScreen recomposes (e.g. each SSE token).
    val onViewToolLambda = remember(viewModel, serverId, sessionId, directory) {
        { request: ViewToolRequest ->
            viewModel.cacheToolPart(request.part)
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = request.filePath,
                directory = directory,
                source = request.source,
                toolPartIds = listOf(request.part.id)
            )
        }
    }
    val onToggleToolExpandedLambda = remember(viewModel) {
        { toolId: String, defaultExpanded: Boolean -> viewModel.toggleToolExpanded(toolId, defaultExpanded) }
    }
    val sessionDiffsMap = remember(viewModel.sessionId, sessionDiffs) {
        mapOf(viewModel.sessionId to sessionDiffs)
    }

    // CompositionLocalProvider collects settings flows here (sunk from ChatScreen level).
    // ChatScreen itself does NOT read these settings, so setting changes don't trigger
    // ChatScreen recomposition — only this wrapper recomposes.
    ChatSettingsProvider(viewModel = viewModel) {
    CompositionLocalProvider(
        LocalHapticFeedbackEnabled provides hapticEnabled,
        LocalImageSaveRequest provides attachmentHandler.requestSaveImage,
        LocalToolExpandedStates provides messageState.toolExpandedStates,
        LocalOnToggleToolExpanded provides onToggleToolExpandedLambda,
        LocalToolCardResolver provides viewModel.toolCardResolver,
        LocalSessionDiffs provides sessionDiffsMap,
        LocalUriHandler provides linkUriHandler,
        LocalOnViewTool provides onViewToolLambda,
        LocalSessionStreaming provides sessionMeta.isStreaming,
    ) {
    var showQuickNavigate by remember { mutableStateOf(false) }
    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = SpacingTokens.LG.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    actionContentColor = MaterialTheme.colorScheme.primary,
                    action = {
                        TextButton(onClick = { data.dismiss() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.a11y_icon_close),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        },
        topBar = {
            if (!isTerminalMode) {
                Column {
                    ChatTopBar(
                        sessionTitle = sessionMeta.sessionTitle,
                        directory = directory,
                        contextDetail = contextDetail,
                        sessionParentId = sessionMeta.sessionParentId,
                        shareUrl = sessionMeta.shareUrl,
                        contextWindow = modelConfig.contextWindow,
                        lastContextTokens = tokenStats.lastContextTokens,
                        onNavigateBack = onNavigateBack,
                        onTerminalMode = { isTerminalMode = true },
                        onOpenInWebView = onOpenInWebView,
                        onNewSession = {
                            onNavigateToSession("")  // Empty sessionId = lazy creation
                        },
                        onForkSession = {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_fork_failed))
                                    }
                                }
                            }
                        },
                        onCompactSession = {
                            forceScrollTick++
                            viewModel.compactSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                    )
                                }
                            }
                        },
                        onReviewChanges = {
                            forceScrollTick++
                            viewModel.executeCommand("review") { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_command_executed, "review") else context.getString(R.string.chat_command_failed, "review")
                                    )
                                }
                            }
                        },
                        onShare = {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("url", url)))
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_url_copied))
                                    } else {
                                        snackbarHostState.showSnackbar(context.getString(R.string.chat_share_failed))
                                    }
                                }
                            }
                        },
                        onUnshare = {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_unshared) else context.getString(R.string.chat_session_unshare_failed)
                                    )
                                }
                            }
                        },
                        onRename = { showRenameDialog = true },
                        onExport = {
                            val slug = sessionMeta.sessionTitle
                                .take(30)
                                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                                .ifBlank { "session" }
                            attachmentHandler.launchExport("$slug.json")
                        },
                        onOpenWorkspace = onOpenWorkspace,
                        onQuickNavigate = { showQuickNavigate = true },

                    )
                }
            }
        },
        bottomBar = {
            ChatScreenBottomBar(
                viewModel = viewModel,
                sessionMeta = sessionMeta,
                isTerminalMode = isTerminalMode,
                messageState = messageState,
                interaction = interaction,
                modelConfig = modelConfig,
                isShellMode = isShellMode,
                hapticEnabled = hapticEnabled,
                fileSearchResults = fileSearchResults,
                confirmedFilePaths = confirmedFilePaths,
                confirmBeforeSend = confirmBeforeSend,
                attachments = attachments,
                attachmentHandler = attachmentHandler,
                restoredDraft = restoredDraft,
                onNavigateToSession = onNavigateToSession,
                inputText = inputText,
                onInputTextChange = { inputText = it },
                onInputModeChange = { inputMode = it },
                onForceScroll = { forceScrollTick++ },
                onShowModelPicker = { showModelPicker = true },
                onShowRenameDialog = { showRenameDialog = true },
                onShowSendConfirmDialog = { showSendConfirmDialog = true },
                onPendingSendActionSet = { pendingSendAction = it },
                coroutineScope = coroutineScope,
                snackbarHostState = snackbarHostState,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            when {
                isTerminalMode -> {
                    ChatTerminalView(
                        viewModel = viewModel,
                        isTerminalMode = isTerminalMode,
                        onTerminalModeChanged = { isTerminalMode = it },
                        startInTerminalMode = startInTerminalMode,
                        onNavigateBack = onNavigateBack,
                        snackbarHostState = snackbarHostState,
                    )
                }
                interaction.isLoading && messageState.messages.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                interaction.error != null && messageState.messages.isEmpty() -> {
                    ChatErrorState(
                        modifier = Modifier.align(Alignment.Center),
                        error = interaction.error,
                        onRetry = { viewModel.loadMessages() }
                    )
                }
                messageState.messages.isEmpty() && !interaction.isLoading -> {
                    ChatEmptyState(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                     val messageSpacing = if (LocalChatDensity.current == ChatDensity.Compact) 2.dp else 8.dp

                        // messageListState returns oldest-first; normal layout renders
                        // index 0 (oldest) at top, last index (newest) at bottom.
                        val rawMessages = remember(messageState.messages) {
                            messageState.messages.reversed()
                        }

                        // Filter: keep user messages + first assistant in each turn group
                        // (first in reversed = newest in original order = the one with
                        // the latest response text). Previous code checked nextMsg which
                        // kept the OLDEST assistant (often empty/reasoning-only), hiding
                        // the actual response text from the user.
                        val displayItems = remember(rawMessages) {
                            rawMessages.mapIndexedNotNull { index, msg ->
                                when {
                                    msg.isUser -> index to msg
                                    msg.isAssistant -> {
                                        val prevMsg = rawMessages.getOrNull(index - 1)
                                        if (prevMsg?.isAssistant != true) index to msg else null
                                    }
                                    else -> null
                                }
                            }
                        }

    // Stable lambda for LocalOnViewTool — must be remembered because LocalOnViewTool
    // is staticCompositionLocalOf: a new lambda instance forces ALL PartContent
    // consumers to recompose on every ChatScreen recomposition (e.g. each SSE token).
    val onViewToolLambda = remember(viewModel, serverId, sessionId, directory) {
        { request: ViewToolRequest ->
            viewModel.cacheToolPart(request.part)
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = request.filePath,
                directory = directory,
                source = request.source,
                toolPartIds = listOf(request.part.id)
            )
        }
    }



                        if (sessionMeta.sessionParentId == null) {
                            ChatMessageList(
                                listState = listState,
                                messageState = messageState,
                                sessionMeta = sessionMeta,
                                interaction = interaction,
                                rawMessages = rawMessages,
                                displayItems = displayItems,
                                isAtBottom = isAtBottom,
                                isAmoled = isAmoled,
                                messageSpacing = messageSpacing,
                                isMainSession = true,
                                coroutineScope = coroutineScope,
                                snackbarHostState = snackbarHostState,
                                context = context,
                                clipboard = clipboard,
                                keyboardController = keyboardController,
                                viewModel = viewModel,
                                navigateToChildSession = onNavigateToChildSession,
                                onOpenFile = handleOpenFile,
                                onForceScrollToBottom = { forceScrollTick++ },
                                showQuickNavigate = showQuickNavigate,
                                onQuickNavigateDismiss = { showQuickNavigate = false },
                                agents = modelConfig.agents,

                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ChatMessageList(
                                listState = listState,
                                messageState = messageState,
                                sessionMeta = sessionMeta,
                                interaction = interaction,
                                rawMessages = rawMessages,
                                displayItems = displayItems,
                                isAtBottom = isAtBottom,
                                isAmoled = isAmoled,
                                messageSpacing = messageSpacing,
                                isMainSession = false,
                                coroutineScope = coroutineScope,
                                snackbarHostState = snackbarHostState,
                                context = context,
                                clipboard = clipboard,
                                keyboardController = keyboardController,
                                viewModel = viewModel,
                                navigateToChildSession = onNavigateToChildSession,
                                onOpenFile = handleOpenFile,
                                onForceScrollToBottom = { forceScrollTick++ },
                                showQuickNavigate = false,
                                onQuickNavigateDismiss = {},
                                agents = modelConfig.agents,

                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                  }
              }
           }
       }


    // Conditional dialogs — extracted to ChatScreenDialogs
    ChatScreenDialogs(
        showModelPicker = showModelPicker,
        onDismissModelPicker = { showModelPicker = false },
        showRenameDialog = showRenameDialog,
        onDismissRenameDialog = { showRenameDialog = false },
        showSendConfirmDialog = showSendConfirmDialog,
        onConfirmSend = {
            pendingSendAction?.invoke()
            pendingSendAction = null
        },
        onDismissSendConfirm = {
            pendingSendAction = null
        },
        providers = modelConfig.providers,
        selectedProviderId = modelConfig.selectedProviderId,
        selectedModelId = modelConfig.selectedModelId,
        onSelectModel = { providerId, modelId ->
            viewModel.selectModel(providerId, modelId)
        },
        sessionTitle = sessionMeta.sessionTitle,
        onRename = { newTitle ->
            viewModel.renameSession(newTitle) { ok ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        if (ok) context.getString(R.string.chat_session_renamed) else context.getString(R.string.chat_session_rename_failed)
                    )
                }
            }
        },
    )
    } // CompositionLocalProvider
    } // ChatSettingsProvider

    // FileViewer overlay — rendered on top of ChatScreen when requested.
    fileViewerRequest?.let { params ->
        FileViewerOverlay(
            params = params,
            onDismiss = { fileViewerRequest = null }
        )
    }
}

/**
 * Wrapper composable that collects settings flows and provides them via CompositionLocals.
 * Sunk from ChatScreen to prevent settings changes from triggering ChatScreen recomposition.
 * Only this wrapper recomposes when settings change.
 */
@Composable
private fun ChatSettingsProvider(
    viewModel: ChatViewModel,
    content: @Composable () -> Unit,
) {
    val chatDensity by viewModel.chatDensity.collectAsStateWithLifecycle()
    val codeWordWrap by viewModel.codeWordWrap.collectAsStateWithLifecycle()
    val collapseTools by viewModel.collapseTools.collectAsStateWithLifecycle()
    val expandReasoning by viewModel.expandReasoning.collectAsStateWithLifecycle()
    val showTurnDividers by viewModel.showTurnDividers.collectAsStateWithLifecycle()

    val density = when (chatDensity) {
        "compact" -> ChatDensity.Compact
        else -> ChatDensity.Normal
    }

    CompositionLocalProvider(
        LocalChatDensity provides density,
        LocalCollapseTools provides collapseTools,
        LocalExpandReasoning provides expandReasoning,
        LocalShowTurnDividers provides showTurnDividers,
    ) {
        content()
    }
}

