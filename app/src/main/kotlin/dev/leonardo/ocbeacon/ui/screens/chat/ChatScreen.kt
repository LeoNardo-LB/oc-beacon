package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween

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
import dev.leonardo.ocbeacon.MainActivity
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
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
import dev.leonardo.ocbeacon.ui.theme.AppMotion
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
 * 聊天屏幕 —— 会话视图，使用原生 markdown 渲染。
 * 通过 mikepenz markdown 渲染器展示流式文本消息。
 */

private const val TAG_SCROLL = "ChatScroll"

// jumpToBottom / animateScrollToBottom 已移除 —— reverseLayout=true 原生锚定底部。




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
    // 首次组合时从草稿同步一次 inputText
    var draftTextInitialized by remember { mutableStateOf(false) }
    if (!draftTextInitialized && draftText.isNotEmpty()) {
        inputText = TextFieldValue(draftText, TextRange(draftText.length))
        draftTextInitialized = true
    } else if (!draftTextInitialized) {
        draftTextInitialized = true
    }
    // 监听应将文本恢复到输入框的 revert 事件
    LaunchedEffect(Unit) {
        viewModel.revertedDraftEvent.collect { payload ->
            inputText = TextFieldValue(payload.text, TextRange(payload.text.length))
        }
    }
    // listState 提升到 ViewModel —— 在导航切换后依然存活。
    val listState = viewModel.listState

    val scrollController = rememberChatScrollController(
        listState = listState,
        messageCount = messageState.messages.size,
        pendingCount = interaction.pendingQuestions.size + interaction.pendingPermissions.size,
        hasMessages = { messageState.messages.isNotEmpty() },
    )

    // FileViewer 浮层状态 —— 取代到 FileViewerNav 路由的导航。
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

    // 首次进入 ChatScreen 时预热 WebView V8 引擎，避免第一次打开文件变慢。
    // 每个进程只执行一次；一次性 WebView 会自动销毁。
    // 延迟 500ms 让初始组合与首个滚动帧稳定下来。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        dev.leonardo.ocbeacon.ui.screens.viewer.WebViewWarmer.warm(context)
    }
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val view = LocalView.current
    val density = LocalDensity.current
    // isAtBottomBeforeIme 已移除 —— reverseLayout=true 原生处理 IME。

    // 仅在用户真实滚动（isScrollInProgress）时收起键盘，
    // 程序化滚动或布局变化不收起。
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

    // IME 滚动 LaunchedEffect 已移除 —— reverseLayout=true 锚定底部，
    // 键盘自然地推动内容，无需显式滚动。

    // @ 文件提及状态
    val fileSearchResults by viewModel.fileSearchResults.collectAsStateWithLifecycle()
    val confirmedFilePaths by viewModel.confirmedFilePaths.collectAsStateWithLifecycle()

    // ChatScreen 中直接使用的设置
    val confirmBeforeSend by viewModel.confirmBeforeSend.collectAsStateWithLifecycle()
    val hapticEnabled by viewModel.hapticFeedback.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    val compressImageAttachments by viewModel.compressImageAttachments.collectAsStateWithLifecycle()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsStateWithLifecycle()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsStateWithLifecycle()
    // 当前会话的文件 diff —— 为 PatchCard 的行数统计（+N -N）提供数据
    val sessionDiffs by viewModel.chatRepositoryExposed
        .getSessionDiffsForSession(viewModel.sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showSendConfirmDialog by remember { mutableStateOf(false) }
    // 待执行的发送动作：保存起来，让确认对话框可以触发它
    var pendingSendAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var inputMode by rememberSaveable { mutableStateOf(ChatInputMode.NORMAL.name) }
    val isShellMode = inputMode == ChatInputMode.SHELL.name

    // 停留在聊天屏幕时保持屏幕常亮（若已在设置中开启）
    DisposableEffect(keepScreenOn) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 附件处理器（图片选择器、SAF 导出、图片保存、草稿恢复）
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

    // 消息已加载完成时用 snackbar 展示错误，并滚动到底部
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

    // 通知生命周期：进入时取消现有通知并设置活动焦点，离开时清除。
    // onSessionFocused/onSessionUnfocused 通过 SessionFocusHolder 设置焦点，
    // AppNotificationManager 据此抑制当前正在查看的会话的事件通知。
    LaunchedEffect(viewModel.sessionId) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        viewModel.onSessionFocused(notificationManager)
    }
    DisposableEffect(viewModel.sessionId) {
        onDispose {
            viewModel.onSessionUnfocused()
            // 离开会话时标记已读（打开期间到达的消息也算已读）
            viewModel.markSessionRead()
        }
    }

    // 进入会话时同步会话状态（REST 回退，用于弥补遗漏的 SSE 事件）
    LaunchedEffect(viewModel.sessionId) {
        if (viewModel.sessionId.isNotBlank()) {
            viewModel.syncSessionStatus()
        }
    }

    // 从后台返回时刷新会话（锁屏 / 应用切换）。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && viewModel.sessionId.isNotBlank()) {
                viewModel.refreshIfNeeded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // CompositionLocals 的稳定 lambda / 值 —— 防止 ChatScreen 重组时
    //（例如每个 SSE token）所有消费者发生不必要的重组。
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

    // CompositionLocalProvider 在此收集设置流（从 ChatScreen 层级下沉）。
    // ChatScreen 本身不读取这些设置，因此设置变化不会触发
    // ChatScreen 重组 —— 只有这个包装层会重组。
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
                            onNavigateToSession("")  // 空 sessionId = 延迟创建
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
                            scrollController.forceScrollToBottom()
                            viewModel.compactSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) context.getString(R.string.chat_session_compacted) else context.getString(R.string.chat_session_compact_failed)
                                    )
                                }
                            }
                        },
                        onReviewChanges = {
                            scrollController.forceScrollToBottom()
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
                onForceScroll = { scrollController.forceScrollToBottom() },
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

                        // messageListState 返回最旧优先；常规布局将
                        // 索引 0（最旧）渲染在顶部，最后一个索引（最新）在底部。
                        val rawMessages = remember(messageState.messages) {
                            messageState.messages.reversed()
                        }

                        // 过滤：保留用户消息 + 每个 turn 组中的第一条 assistant 消息
                        //（反转后的第一条 = 原始顺序中的最新 = 拥有最新回复文本的那条）。
                        // 之前的代码检查 nextMsg，保留了最旧的 assistant 消息
                        //（通常为空或仅有 reasoning），从而对用户隐藏了实际回复文本。
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

    // LocalOnViewTool 的稳定 lambda —— 必须 remember，因为 LocalOnViewTool
    // 是 staticCompositionLocalOf：新的 lambda 实例会在每次 ChatScreen 重组时
    //（例如每个 SSE token）强制所有 PartContent 消费者重组。
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



                        AnimatedVisibility(
                            visible = !interaction.isLoading,
                            enter = fadeIn(tween(AppMotion.MEDIUM))
                        ) {
                        if (sessionMeta.sessionParentId == null) {
                            ChatMessageList(
                                listState = listState,
                                messageState = messageState,
                                sessionMeta = sessionMeta,
                                interaction = interaction,
                                rawMessages = rawMessages,
                                displayItems = displayItems,
                                isAtBottom = scrollController.isAtBottom,
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
                                onForceScrollToBottom = { scrollController.forceScrollToBottom() },
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
                                isAtBottom = scrollController.isAtBottom,
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
                                onForceScrollToBottom = { scrollController.forceScrollToBottom() },
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
        }


    // 条件对话框 —— 已抽取到 ChatScreenDialogs
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

    // FileViewer 浮层 —— 请求时渲染在 ChatScreen 之上。
    fileViewerRequest?.let { params ->
        FileViewerOverlay(
            params = params,
            onDismiss = { fileViewerRequest = null }
        )
    }
}

/**
 * 包装 composable：收集设置流并通过 CompositionLocals 提供。
 * 从 ChatScreen 下沉，防止设置变化触发 ChatScreen 重组。
 * 设置变化时只有这个包装层会重组。
 */
@Composable
private fun ChatSettingsProvider(
    viewModel: ChatViewModel,
    content: @Composable () -> Unit,
) {
    val chatDensity by viewModel.chatDensity.collectAsStateWithLifecycle()
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

