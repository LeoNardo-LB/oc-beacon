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
import kotlinx.coroutines.delay
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
import dev.leonardo.ocbeacon.ui.theme.LocalChatDensity
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalAutoExpandTools
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalExpandReasoning
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalShowTurnDividers
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalHapticFeedbackEnabled
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalImageSaveRequest
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalSessionDiffs
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalSessionStreaming
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalToolExpandedStates
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalOnToggleToolExpanded
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalTaskOutputFetcher
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
import dev.leonardo.ocbeacon.ui.screens.chat.components.dedupeConsecutiveSynthetics
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatErrorState
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.ui.screens.chat.components.ChatMessageList
import dev.leonardo.ocbeacon.ui.screens.chat.components.QueueDock
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
 * 通过 mikepenz markdown 渲染器展示流式文本 turn。
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
    /** 2026-08-16（管理入口）：跳服务器模型管理页 */
    onNavigateToModelFilter: () -> Unit = {},
    onOpenWorkspace: () -> Unit = {},
    onOpenFile: (filePath: String) -> Unit = {},
    onOpenDirectory: (directoryPath: String) -> Unit = {},
    checkFileExists: suspend (filePath: String) -> Boolean = { true },
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    startInTerminalMode: Boolean = false,
    /** 2026-09-01（B1 链）：内容检索跳转目标消息 id（非空时打开即定位，既有异步跳转机制承接）。 */
    initialJumpToMessageId: String? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messageState by viewModel.conversation.messageListState.collectAsStateWithLifecycle()
    val sessionMeta by viewModel.sessionMetaState.collectAsStateWithLifecycle()
    val interaction by viewModel.conversation.interactionState.collectAsStateWithLifecycle()
    val tokenStats by viewModel.tokenStatsState.collectAsStateWithLifecycle()
    val modelConfig by viewModel.modelConfigState.collectAsStateWithLifecycle()
    val directory by viewModel.directoryState.collectAsStateWithLifecycle()
    val contextDetail by viewModel.contextDetailState.collectAsStateWithLifecycle()
    val restoredDraft by viewModel.composer.restoredDraftState.collectAsStateWithLifecycle()
    val draftText by viewModel.composer.draftText.collectAsStateWithLifecycle()
    val serverCapabilities by viewModel.serverCapabilities.collectAsStateWithLifecycle()
    val draftAttachmentUris by viewModel.composer.draftAttachmentUris.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    // 首次组合时从草稿同步一次 inputText。
    // #113（D2-06）：原实现用 remember 布尔标记，在 restoredDraft（DataStore
    // 异步读）完成前首帧即置 true → 冷启动草稿视觉丢失（数据仍在，继续输入
    // 即覆盖）。改为 LaunchedEffect(draftText)：草稿恢复驱动（而非组合首帧），
    // 且只在用户尚未输入（inputText 仍为空）时回填——不覆盖用户早输入。
    var userHasTyped by remember { mutableStateOf(false) }
    LaunchedEffect(draftText) {
        if (!userHasTyped && draftText.isNotEmpty() && inputText.text.isEmpty()) {
            inputText = TextFieldValue(draftText, TextRange(draftText.length))
        }
    }
    // 监听应将文本恢复到输入框的 revert 事件
    LaunchedEffect(Unit) {
        viewModel.composer.revertedDraftEvent.collect { payload ->
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

    // #106 lint 清偿（LocalContextGetResourceValueCall）：snackbar 文案 hoist
    // stringResource（lambda 内不可调用 @Composable）；context 仍供通知服务等使用
    val sessionCompactedMsg = stringResource(R.string.chat_session_compacted)
    val forkFailedMsg = stringResource(R.string.chat_fork_failed)
    val sessionCompactFailedMsg = stringResource(R.string.chat_session_compact_failed)
    val shareUrlCopiedMsg = stringResource(R.string.chat_share_url_copied)
    val shareFailedMsg = stringResource(R.string.chat_share_failed)
    val sessionUnsharedMsg = stringResource(R.string.chat_session_unshared)
    val sessionUnshareFailedMsg = stringResource(R.string.chat_session_unshare_failed)
    val sessionRenamedMsg = stringResource(R.string.chat_session_renamed)
    val sessionRenameFailedMsg = stringResource(R.string.chat_session_rename_failed)
    // 2026-08-19：终端连接失败文案——经 onConnectFailed 在本 scope 展示
    //（ChatTerminalView 随 isTerminalMode=false 离开组合，其本地 scope 不可靠）
    val terminalConnectFailedMsg = stringResource(R.string.chat_terminal_connect_failed)
    // 2026-08-16（压缩完成后才通知·用户需求）：成功通知由 SSE
    // session.compacted 事件驱动（压缩完毕的确切时刻）——HTTP 回调只报失败。
    LaunchedEffect(Unit) {
        viewModel.compactionDoneEvent.collect {
            snackbarHostState.showSnackbar(sessionCompactedMsg)
        }
    }

    // #219（2026-08-25）：V2 压缩失败 snackbar——HTTP 秒回受理，失败只从 SSE
    // session.compaction.failed 到达，此前静默结束（分割线闪一下即消失，用户
    // 无从得知失败；V1 的 HTTP 失败回调在 V2 永不触发）。
    val compactFailedMsg = stringResource(R.string.chat_session_compact_failed)
    LaunchedEffect(Unit) {
        viewModel.compactionFailedEvent.collect { serverErr ->
            val detail = if (serverErr.isNotBlank()) ": " + serverErr.take(80) else ""
            snackbarHostState.showSnackbar(compactFailedMsg + detail)
        }
    }

    // 首次进入 ChatScreen 时预热 WebView V8 引擎，避免第一次打开文件变慢。
    // 每个进程只执行一次；一次性 WebView 会自动销毁。
    // 延迟 500ms 让初始组合与首个滚动帧稳定下来。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(500)
        dev.leonardo.ocbeacon.ui.screens.viewer.WebViewWarmer.warm(context)
    }
    // 堆积/TODO 面板（2026-08-20 设计定稿）：组合即探测 TODO 能力——
    // 不开面板也能亮下角标（V1 恒支持；V2 beta 404 按 baseUrl 记忆缺失）。
    LaunchedEffect(Unit) {
        viewModel.probeTodoCapability()
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
    val fileSearchResults by viewModel.composer.fileSearchResults.collectAsStateWithLifecycle()
    val confirmedFilePaths by viewModel.composer.confirmedFilePaths.collectAsStateWithLifecycle()

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
        onAddDraftAttachment = { viewModel.composer.addDraftAttachment(it) },
        onRemoveDraftAttachment = { viewModel.composer.removeDraftAttachment(it) },
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

    // 2026-08-11 用户要求：发送成功才清空输入区（输入框保留内容直至确认成功）。
    // 发送失败 → 内容保留在输入框 + AlertDialog（sendFailure）。
    // 注意：key 必须用 collectAsState 的**值**（StateFlow 对象做 key 永不变化，
    // tick 递增不触发——实测 BUG，2026-08-11 修复）。
    val sendSuccessTick by viewModel.sendSuccessTick.collectAsStateWithLifecycle()
    LaunchedEffect(sendSuccessTick) {
        if (sendSuccessTick > 0) {
            // E8-1 修复（2026-08-14）：仅当输入框内容仍是本次已发送的文本时才清空。
            // 发送期间用户输入的新内容会被防重复拦截（sendParts isSending 锁）——
            // 无条件清空会静默丢失用户新输入（快速连发 A→B 时 B 丢失）。
            // 不匹配时保留输入框（含草稿/附件），用户可再次发送。
            if (inputText.text == viewModel.lastSentTextSnapshotForClear) {
                inputText = TextFieldValue("")
                viewModel.composer.clearDraft()
                viewModel.composer.clearConfirmedPaths()
                viewModel.composer.clearFileSearch()
                attachmentHandler.clearAttachments()
            }
        }
    }

    // 发送失败 AlertDialog（2026-08-11 用户要求：alert 而非 snackbar）
    val sendFailureMsg by viewModel.sendFailure.collectAsStateWithLifecycle()
    sendFailureMsg?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeSendFailure() },
            title = { Text(stringResource(R.string.chat_send_failed_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeSendFailure() }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // 走查 #2（DSH toast 对位）：会话运行错误 → 一次性 snackbar（Web 无 dialog——
    // 弹窗通道降级为转录内行 + 一次性 snackbar）；持久呈现由转录内错误行承担
    // （sessionErrors → ChatMessageList）。
    val sessionErrorToastMsg by viewModel.sessionErrorToast.collectAsStateWithLifecycle()
    LaunchedEffect(sessionErrorToastMsg) {
        sessionErrorToastMsg?.let { message ->
            viewModel.consumeSessionErrorToast()
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    // ============ 加载过渡 ============
    // （#53：2026-08-10 的 MIN_LOADING_VISIBLE_MS 人为延迟已移除——NavHost 全局
    // fadeIn 过渡提供进入动画；PulsingDots 仅在真正加载时显示，不欺骗感知）

    val lifecycleOwner = LocalLifecycleOwner.current

    // 通知生命周期：进入时取消现有通知并设置活动焦点，离开时清除。
    // onSessionFocused/onSessionUnfocused 通过 SessionFocusHolder 设置焦点，
    // AppNotificationManager 据此抑制当前正在查看的会话的事件通知。
    LaunchedEffect(viewModel.sessionId) {
        viewModel.onSessionFocused()
    }
    DisposableEffect(viewModel.sessionId) {
        onDispose {
            viewModel.onSessionUnfocused()
            // 离开会话时标记已读（打开期间到达的消息也算已读）
            viewModel.markSessionRead()
        }
    }

    // 进入会话时同步会话状态（REST 降级兜底，用于弥补遗漏的 SSE 事件）
    LaunchedEffect(viewModel.sessionId) {
        if (viewModel.sessionId.isNotBlank()) {
            viewModel.syncSessionStatus()
            // 后台活动轮询（active 会话权威来源；幂等，组合即启动）
            viewModel.startTaskPolling()
            viewModel.refreshTaskNow()
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
        { toolId: String, defaultExpanded: Boolean -> viewModel.conversation.toggleToolExpanded(toolId, defaultExpanded) }
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
        // #182：Task 卡片展开时的全量输出拉取（part 优先→降级子智能体会话 transcript）
        LocalTaskOutputFetcher provides { partId, subSessionId ->
            viewModel.fetchFullTaskOutput(partId, subSessionId)
        },
        LocalSessionDiffs provides sessionDiffsMap,
        LocalUriHandler provides linkUriHandler,
        LocalOnViewTool provides onViewToolLambda,
        LocalSessionStreaming provides sessionMeta.isStreaming,
    ) {
    var showQuickNavigate by remember { mutableStateOf(false) }
    // 贴底工具栏 + 四 sheet（2026-08-22 第十轮：任务面板拆解并入）
    val pendingQueue by viewModel.pendingQueue.collectAsStateWithLifecycle()
    // D1③：会话运行错误持久卡（sendMessage 成功/手动 dismiss 清卡）
    val sessionErrors by viewModel.sessionErrors.collectAsStateWithLifecycle()
    val sessionTodos by viewModel.sessionTodos.collectAsStateWithLifecycle()
    val todoCapable by viewModel.todoCapable.collectAsStateWithLifecycle()
    val pendingDrainingSet by viewModel.pendingDraining.collectAsStateWithLifecycle()
    val taskUi by viewModel.taskUiState.collectAsStateWithLifecycle()
    var toolbarSheet by remember { mutableStateOf<ChatToolbarEntry?>(null) }
    // #286：目标状态（GoalSheet 数据源 + FAB/菜单项角标）
    val goalState by viewModel.goalState.collectAsStateWithLifecycle()
    val goalError by viewModel.goalError.collectAsStateWithLifecycle(initialValue = null)
    // （第九轮常驻抽屉已于第十轮退役——改为贴底工具栏 + 四个独立 ModalBottomSheet）
    // #252：shell 输出三级 provider（迁自 TaskSheet：事件输出 → 消息流回填 → REST 拉取）——
    // 前移到 Scaffold 之前供输入栏上方 ShellJobsStrip 复用。
    val shellOutputs = remember { mutableStateMapOf<String, String?>() }
    val allPartsMap by viewModel.chatRepositoryExposed.getAllPartsMap()
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val shellOutputResolver = remember(viewModel.sessionId, allPartsMap) {
        { shell: ShellJob ->
            shell.output
                ?: allPartsMap[viewModel.sessionId].orEmpty().asSequence()
                    .filterIsInstance<Part.Tool>()
                    .filter { it.tool == "shell" || it.tool == "bash" }
                    .filter { part ->
                        val cmd = (part.state as? ToolState.Completed)?.input
                            ?.get("command")?.jsonPrimitive?.contentOrNull
                        cmd == shell.command
                    }
                    .mapNotNull { (it.state as? ToolState.Completed)?.output }
                    .lastOrNull()
                ?: run {
                    if (!shellOutputs.containsKey(shell.id)) {
                        shellOutputs[shell.id] = null
                        viewModel.fetchShellOutput(shell.id) { out ->
                            shellOutputs[shell.id] = out?.output
                        }
                    }
                    shellOutputs[shell.id]
                }
        }
    }
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
                        // #286：环数据源切换投影（projectedTokens??pressureTokens / 投影 window）——
                        // 投影缺席（OpenCode/未上报）走既有 llm.models + token 统计路径
                        projectionUsedTokens = contextDetail.projectionUsedTokens,
                        projectionContextWindow = contextDetail.projectionContextWindow,
                        onNavigateBack = onNavigateBack,
                        onTerminalMode = { isTerminalMode = true },
                        onForkSession = {
                            viewModel.forkSession { session ->
                                if (session != null) {
                                    onNavigateToSession(session.id)
                                } else {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(forkFailedMsg)
                                    }
                                }
                            }
                        },
                        onCompactSession = {
                            scrollController.forceScrollToBottom()
                            viewModel.compactSession { ok ->
                                // 2026-08-16：成功通知移到 SSE compacted 事件
                                //（压缩完毕才提示）；HTTP 回调只报失败
                                if (!ok) {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            sessionCompactFailedMsg
                                        )
                                    }
                                }
                            }
                        },
                        isShareSupported = serverCapabilities.shareSupported,
                        isBackgroundSupported = serverCapabilities.backgroundSessionsSupported,
                        isTerminalSupported = serverCapabilities.terminalSupported,
                        onShare = {
                            viewModel.shareSession { url ->
                                coroutineScope.launch {
                                    if (url != null) {
                                        clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("url", url)))
                                        snackbarHostState.showSnackbar(shareUrlCopiedMsg)
                                    } else {
                                        snackbarHostState.showSnackbar(shareFailedMsg)
                                    }
                                }
                            }
                        },
                        onUnshare = {
                            viewModel.unshareSession { ok ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (ok) sessionUnsharedMsg else sessionUnshareFailedMsg
                                    )
                                }
                            }
                        },
                        onExport = {
                            val slug = sessionMeta.sessionTitle
                                .take(30)
                                .replace(EXPORT_SLUG_INVALID_CHARS_REGEX, "_")
                                .ifBlank { "session" }
                            attachmentHandler.launchExport("$slug.json")
                        },
                        onBackgroundSession = { viewModel.backgroundSession() },
                        onOpenWorkspace = onOpenWorkspace,
                    )
                }
            }
        },
        bottomBar = {
            Column {
                // 2026-09-01（Task 4 QueueDock）：排队收件箱条——ChatScreenBottomBar
                // 上方；空队列不渲染；子代理会话只读（隐藏动作）；steer 仅运行中启用。
                val queueItems by viewModel.queueItems.collectAsStateWithLifecycle()
                val queueRunning = sessionMeta.sessionStatus is SessionStatus.Busy
                val queueReadOnly = sessionMeta.sessionParentId != null
                LaunchedEffect(queueRunning) {
                    viewModel.queueActionResult.collect { resId ->
                        snackbarHostState.showSnackbar(context.getString(resId))
                    }
                }
                QueueDock(
                    items = queueItems,
                    isRunning = queueRunning,
                    isReadOnly = queueReadOnly,
                    onSaveEdit = { itemId, text ->
                        viewModel.updateQueueItem(
                            itemId,
                            dev.leonardo.ocbeacon.domain.model.QueueActionKind.EDIT,
                            text,
                        )
                    },
                    onRemove = { itemId ->
                        viewModel.updateQueueItem(itemId, dev.leonardo.ocbeacon.domain.model.QueueActionKind.REMOVE, null)
                    },
                    onSteer = { itemId ->
                        viewModel.updateQueueItem(itemId, dev.leonardo.ocbeacon.domain.model.QueueActionKind.STEER, null)
                    },
                )
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
                onInputTextChange = { inputText = it; userHasTyped = true },
                onInputModeChange = { inputMode = it },
                onForceScroll = { scrollController.forceScrollToBottom() },
                onShowModelPicker = { showModelPicker = true },
                onShowRenameDialog = { showRenameDialog = true },
                onShowSendConfirmDialog = { showSendConfirmDialog = true },
                onPendingSendActionSet = { pendingSendAction = it },
                coroutineScope = coroutineScope,
                snackbarHostState = snackbarHostState,
                onQuickNavigate = { showQuickNavigate = true },
                )
            }
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
                        terminal = viewModel.terminal,
                        isTerminalMode = isTerminalMode,
                        onTerminalModeChanged = { isTerminalMode = it },
                        startInTerminalMode = startInTerminalMode,
                        onNavigateBack = onNavigateBack,
                        snackbarHostState = snackbarHostState,
                        onConnectFailed = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(terminalConnectFailedMsg)
                            }
                        },
                    )
                }
                // 进入会话加载过渡：仅在真正加载时显示 PulsingDots。
                // （#53：移除 2026-08-10 的 MIN_LOADING_VISIBLE_MS 人为延迟补丁——
                // NavHost 全局 fadeIn 过渡已提供进入过渡感，双过渡叠加是反模式）
                interaction.isLoading && !isTerminalMode && interaction.error == null -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                interaction.error != null && messageState.messages.isEmpty() -> {
                    ChatErrorState(
                        modifier = Modifier.align(Alignment.Center),
                        error = interaction.error,
                        onRetry = { viewModel.conversation.paginationDelegate.loadMessages() }
                    )
                }
                // 新增P2（2026-08-19）：空会话仍有待处理权限/提问时不得落入空态分支
                // ——ChatEmptyState 整块替换消息区，而权限/提问卡是 ChatMessageList
                // 的 LazyColumn item，空会话下永远不可渲染（真实场景：新建会话
                // agent 首轮就要权限/提问，3 分钟无人应答即超时）。有 pending 卡片
                // 时走完整消息列表分支（空消息 + 卡片 item，LazyColumn 正常渲染）。
                messageState.messages.isEmpty() && !interaction.isLoading &&
                    interaction.pendingQuestions.isEmpty() && interaction.pendingPermissions.isEmpty() -> {
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
                        // synthetic 合成通知：2026-08-11 首版曾紧邻 assistant 时
                        // 并入 turn 气泡渲染（isAdjacentToAssistant）；2026-08-12 用户
                        // 决策改为独立气泡（见下方 mapIndexedNotNull 内注释），并入方案已废弃。
                        // 2026-08-12：无文本的 synthetic 空壳（服务器历史遗留，text
                        // 为空）完全过滤——SyntheticNotificationCard 提取不到 text 会
                        // return 空，保留在 displayItems 会形成空行（"返回后消息流
                        // 乱了"的现象之一）。
                        // #243 连续同内容 shell 卡去重（首张 + ×N，其余抑制渲染）
                        val displayItemsPair = remember(rawMessages) {
                            dedupeConsecutiveSynthetics(
                            rawMessages.mapIndexedNotNull { index, msg ->
                                when {
                                    msg.isUser && !msg.isSynthetic -> index to msg
                                    msg.isSynthetic -> {
                                        val hasText = msg.parts
                                            .filterIsInstance<Part.Text>()
                                            .any { it.text.isNotBlank() } ||
                                            (msg.message as? Message.User)?.summary?.body?.isNotBlank() == true
                                        // 2026-08-12 用户决策：synthetic 是独立消息 → 独立气泡
                                        // （与 user 消息同构，ChatMessageList 已按 role 分发
                                        // SYNTHETIC 卡片）。不再邻接判断/嵌入 assistant turn。
                                        if (!hasText) {
                                            null
                                        } else {
                                            index to msg
                                        }
                                    }
                                    msg.isAssistant -> {
                                        val prevMsg = rawMessages.getOrNull(index - 1)
                                        if (prevMsg?.isAssistant != true) index to msg else null
                                    }
                                    else -> null
                                }
                            }
                            )
                        }
                        val displayItems = displayItemsPair.first
                        val syntheticDupCounts = displayItemsPair.second

                    // #137（D2-L65）：此处原重复定义 onViewToolLambda（死代码——
                    // LocalOnViewTool 由外层的定义提供，本内层定义从未被使用）
                    // #175：原主/子智能体会话双调用点合一——20 个公共参数逐字相同，
                    // 4 个差异（isMainSession/showQuickNavigate/onQuickNavigateDismiss/onAgentClick）
                    // 全是同一开关（主/子智能体会话）的投影，条件内移为参数化单调用点。
                    val isMainSession = sessionMeta.sessionParentId == null
                    ChatMessageList(
                        listState = listState,
                        messageState = messageState,
                        sessionMeta = sessionMeta,
                        interaction = interaction,
                        rawMessages = rawMessages,
                        displayItems = displayItems,
                        syntheticDupCounts = syntheticDupCounts,
                        isAtBottomState = scrollController.isAtBottomState,
                        autoScrollState = scrollController.autoScrollState,
                        isAmoled = isAmoled,
                        messageSpacing = messageSpacing,
                        isMainSession = isMainSession,
                        coroutineScope = coroutineScope,
                        snackbarHostState = snackbarHostState,
                        context = context,
                        clipboard = clipboard,
                        keyboardController = keyboardController,
                        viewModel = viewModel,
                        navigateToChildSession = onNavigateToChildSession,
                        onOpenFile = handleOpenFile,
                        onForceScrollToBottom = { scrollController.forceScrollToBottom() },
                        // 子智能体会话不显示快速定位（show=false 时 onDismiss 不可达，可无条件传）
                        showQuickNavigate = if (isMainSession) showQuickNavigate else false,
                        onQuickNavigateDismiss = { showQuickNavigate = false },
                        agents = modelConfig.agents,
                        // 子智能体会话无 agent 选择入口（置 null 隐藏）
                        onAgentClick = if (isMainSession) ({ agentName -> viewModel.modelSelection.selectAgent(agentName) }) else null,
                        // #252：V2 会话级 shell 对话流内嵌卡（TUI 语义）
                        sessionShellJobs = taskUi.shells,
                        shellOutputProvider = shellOutputResolver,
                        // 2026-09-01（Task 3d）：DSH 后台任务降级 Shell 卡（session/jobs 快照）
                        dshJobs = taskUi.dshJobs,
                        // 2026-09-01（走查 #2）：会话运行错误转录内行（消息流内渲染，
                        // 随历史滚动，非悬浮浮层；DSH turn-error 对位）
                        sessionErrorRows = sessionErrors,
                        // 2026-09-01（B1 链）：内容检索跳转目标消息（打开即异步定位）
                        initialJumpTarget = initialJumpToMessageId,
                        modifier = Modifier.fillMaxSize(),
                    )
                  }

              }

              // （2026-08-22 引入的堆积/TODO 常驻抽屉已于第十轮退役——现由右下
              // ChatFabMenu 四入口 + ModalBottomSheet（StackedSheet/TodoSheet/
              // AgentSheet/ShellSheet，见下方 toolbarSheet 分发）承接；更早的模态
              // PendingTodoSheet 亦已退役。）
              // ⬇ 滚动到底部（第二十一轮移左）：底部左侧与右下菜单 FAB 镜像；
              // 声明在 ChatFabMenu 之前——菜单展开时被外点收起层盖住（点它先收菜单）
              // 右下角 FAB Menu：单 FAB 收纳四入口（角标=总数），展开官方交错菜单
              //（堆积/TODO/智能体/Shell）；键盘弹起时被键盘自然盖住
              if (!isTerminalMode) {
                  ChatScrollBottomFab(
                      isAtBottomState = scrollController.isAtBottomState,
                      // 即时吸附（旧 FAB 同语义）——不走 forceScrollTick 路径：
                      // 那是「发送后等新消息增长再滚」的执行器，点 ⬇ 无新消息时
                      // 要等 5s 增长超时才滚（真机日志实锤 grew=-1 后才滚）
                      onClick = { coroutineScope.launch { listState.snapToBottom() } },
                      modifier = Modifier.align(Alignment.BottomStart),
                  )
                  ChatFabMenu(
                      stackedCount = pendingQueue.size,
                      todoPendingCount = sessionTodos.count { it.status == "pending" || it.status == "in_progress" },
                      agentRunningCount = taskUi.runningSubagentCount,
                      shellRunningCount = taskUi.runningShellCount,
                      goalPhase = goalState?.goal?.phase,
                      onOpenEntry = { toolbarSheet = it },
                      // 2026-08-29 基线对齐：菜单 08-27 稳定 API 复刻把按钮钉底（内部
                      // 底距移除）后，与 ⬇ FAB 的 padding(bottom=16dp) 失配 16dp——
                      // 实测图标中心差 48px。此处补对称底距恢复「双 FAB 同基线」。
                      modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp),
                  )

                  // 走查 #2：会话运行错误持久卡浮层已移除——改为转录内错误行
                  // （ChatMessageList LazyColumn item，随历史滚动；DSH turn-error 对位）。
              }
           }

        }

    // #188：默认模型响应式状态（写入即回显）
    val localDefaultModel by viewModel.modelSelection.localDefaultModelFlow
        .collectAsStateWithLifecycle()

    // #286：goal mutation 失败/忙提示（GoalSheet 动作失败 → snackbar）
    val goalErrorContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(goalError) {
        goalError?.let { resId ->
            snackbarHostState.showSnackbar(goalErrorContext.getString(resId))
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
        onSelectModel = { providerId, modelId, variant ->
            viewModel.modelSelection.selectModel(providerId, modelId, variant)
        },
        // #188：响应式默认模型（原一次性 getter 快照 → DataStore 写入后星标/开关永不回显的根因）
        defaultModel = localDefaultModel,
        selectedVariant = modelConfig.selectedVariant,
        onSetDefaultModel = { providerId, modelId ->
            viewModel.toggleDefaultModel(providerId, modelId)
        },
        onManageModels = onNavigateToModelFilter,
        sessionTitle = sessionMeta.sessionTitle,
        onRename = { newTitle ->
            viewModel.renameSession(newTitle) { ok ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        if (ok) sessionRenamedMsg else sessionRenameFailedMsg
                    )
                }
            }
        },
    )

        // 四 sheet（第十轮：工具栏入口的容器——TaskSheet 拆解为 agent/shell 两 sheet）
    // （shell 输出三级 provider 已前移至 Scaffold 之前——#252 ShellJobsStrip 共用）
    toolbarSheet?.let { entry ->
        when (entry) {
            ChatToolbarEntry.STACKED -> StackedSheet(
                queue = pendingQueue,
                isSessionIdle = sessionMeta.sessionStatus !is SessionStatus.Busy &&
                    sessionMeta.sessionStatus !is SessionStatus.Retry,
                isDraining = pendingDrainingSet.contains(viewModel.sessionId),
                onContinue = viewModel::continuePendingQueue,
                onClear = viewModel::clearPendingMessages,
                onEdit = { id, text -> viewModel.editPendingMessage(id, text) },
                onDelete = { id -> viewModel.deletePendingMessage(id) },
                onSendOne = { id, text -> viewModel.sendPendingNow(id, text) },
                onReorder = { ids -> viewModel.reorderPendingMessages(ids) },
                onDismiss = { toolbarSheet = null },
            )
            ChatToolbarEntry.TODO -> TodoSheet(
                todos = sessionTodos,
                onDismiss = { toolbarSheet = null },
            )
            ChatToolbarEntry.AGENT -> AgentSheet(
                state = taskUi,
                onDismiss = { toolbarSheet = null },
                onOpenSubSession = { sessionId -> onNavigateToChildSession(sessionId) },
            )
        // #286：目标面板（GOAL 入口）——GoalSheet（phase 状态机在面板内；goalError → snackbar）
        ChatToolbarEntry.GOAL -> {
            GoalSheet(
                goal = goalState,
                onDismiss = { toolbarSheet = null },
                onCreate = { objective, rounds -> viewModel.createGoal(objective, rounds) },
                onEdit = { objective, rounds -> viewModel.editGoal(objective, rounds) },
                onPause = { viewModel.pauseGoal() },
                onResume = { viewModel.resumeGoal() },
                onClear = { viewModel.clearGoal() },
            )
        }
            ChatToolbarEntry.SHELL -> ShellSheet(
                state = taskUi,
                onDismiss = { toolbarSheet = null },
                onRemoveShell = { id -> viewModel.removeShell(id) },
                shellOutputProvider = shellOutputResolver,
            )
        }
    }


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

/** #106-4：会话导出文件名清理正则——顶层预编译（原 onExport 点击时现场编译）。 */
private val EXPORT_SLUG_INVALID_CHARS_REGEX = Regex("[^a-zA-Z0-9_-]")

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
    val autoExpandTools by viewModel.autoExpandTools.collectAsStateWithLifecycle()
    val expandReasoning by viewModel.expandReasoning.collectAsStateWithLifecycle()
    val showTurnDividers by viewModel.showTurnDividers.collectAsStateWithLifecycle()

    val density = when (chatDensity) {
        "compact" -> ChatDensity.Compact
        else -> ChatDensity.Normal
    }

    CompositionLocalProvider(
        LocalChatDensity provides density,
        LocalAutoExpandTools provides autoExpandTools,
        LocalExpandReasoning provides expandReasoning,
        LocalShowTurnDividers provides showTurnDividers,
    ) {
        content()
    }
}

