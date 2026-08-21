package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.ClipData
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.MainActivity
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.terminal.TerminalTabState
import dev.leonardo.ocbeacon.data.terminal.TerminalTabUi
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/** #106-4：终端粘贴控制字符清理正则——顶层预编译（原每次粘贴现场编译）。 */
private val TERMINAL_CONTROL_CHARS_REGEX = Regex("[\u001B\u0080-\u009F]")

/**
 * 从 ChatScreen 抽取的终端模式视图。
 *
 * 渲染完整终端 UI（Drawer + SessionTerminalInline + KeyboardOverlay）
 * 并管理终端输入处理（粘贴、分块发送、修饰键）。
 */
@Composable
fun ChatTerminalView(
    terminal: dev.leonardo.ocbeacon.ui.screens.chat.TerminalDelegate,
    isTerminalMode: Boolean,
    onTerminalModeChanged: (Boolean) -> Unit,
    startInTerminalMode: Boolean,
    onNavigateBack: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
    /**
     * 2026-08-19（连接失败 snackbar 消失·竞态根治）：PTY 连接失败的 snackbar
     * 展示移交 ChatScreen 的存活 scope。原实现在本视图的
     * rememberCoroutineScope 上 launch showSnackbar 后立即
     * onTerminalModeChanged(false)——本视图离开组合，排队中的 snackbar
     * 协程被 scope.cancel() 杀死（E2E 实证：ENETUNREACH 失败链完整触发、
     * 终端模式正确退出，但用户看不到任何反馈）。
     */
    onConnectFailed: () -> Unit,
) {
    val terminalState by terminal.terminalState.collectAsStateWithLifecycle()
    val terminalTabs by terminal.terminalTabs.collectAsStateWithLifecycle()
    val activeTerminalTabId by terminal.activeTerminalTabId.collectAsStateWithLifecycle()
    val terminalFontSizeSp by terminal.terminalFontSizeSp.collectAsStateWithLifecycle()

    // isTerminalMode 已上提——变更通过 onTerminalModeChanged 处理
    var terminalCtrlLatched by rememberSaveable { mutableStateOf(false) }
    var terminalAltLatched by rememberSaveable { mutableStateOf(false) }
    var terminalVirtualCtrlDown by remember { mutableStateOf(false) }
    var terminalVirtualFnDown by remember { mutableStateOf(false) }
    var suppressFnTildeUntil by remember { mutableStateOf(0L) }
    // #189：Termux View 引用（焦点/软键盘直通——Compose FocusRequester 不穿越 AndroidView）
    var terminalViewRef by remember { mutableStateOf<com.termux.view.TerminalView?>(null) }
    // D2-L52：使用传入的 snackbarHostState（ChatScreen 已承载 SnackbarHost）——
    // 原函数内 remember 遮蔽参数，传入的 host 成为死参数，终端 snackbar 从不显示。
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // #106 lint 清偿：snackbar 文案 hoist stringResource（抽屉重连/新建 tab 的
    // 失败提示共用——这两条路径终端视图保持组合，本地 scope 可靠）
    val terminalConnectFailedMsg = stringResource(R.string.chat_terminal_connect_failed)
    val isAmoled = isAmoledTheme()
    val keyboardController = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboard.current
    val view = LocalView.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var terminalOverlayHeightPx by remember { mutableIntStateOf(0) }
    val terminalDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // ── BackHandler ──────────────────────────────────────────────────
    BackHandler(enabled = isTerminalMode) {
        if (terminalDrawerState.isOpen) {
            coroutineScope.launch { terminalDrawerState.close() }
        } else if (startInTerminalMode) {
            onNavigateBack()
        } else {
            onTerminalModeChanged(false)
        }
    }

    LaunchedEffect(isTerminalMode) {
        if (isTerminalMode) {
            terminal.openTerminalSession { ok ->
                if (!ok) {
                    // snackbar 经 onConnectFailed 在 ChatScreen 的存活 scope 展示
                    //（本视图即将离开组合，本地 scope 会被取消——见参数注释）
                    onConnectFailed()
                    onTerminalModeChanged(false)
                }
            }
        } else {
            terminalCtrlLatched = false
            terminalAltLatched = false
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
            suppressFnTildeUntil = 0L
        }
    }

    // ── 物理按键拦截器（音量键 → Ctrl / Fn）──────────────
    DisposableEffect(isTerminalMode) {
        val activity = context as? MainActivity
        if (isTerminalMode && activity != null) {
            activity.setTerminalKeyInterceptor { event ->
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        terminalVirtualCtrlDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        true
                    }
                    android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                        val wasDown = terminalVirtualFnDown
                        terminalVirtualFnDown = event.action == android.view.KeyEvent.ACTION_DOWN
                        if (BuildConfig.DEBUG) {
                            AppLogger.d("TerminalInput", "VOL_UP: action=${if (event.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"} wasDown=$wasDown nowDown=$terminalVirtualFnDown")
                        }
                        if (wasDown && !terminalVirtualFnDown) {
                            suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
                            if (BuildConfig.DEBUG) {
                                AppLogger.d("TerminalInput", "FN released -> suppressFnTildeUntil set for 3s")
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
        } else {
            activity?.setTerminalKeyInterceptor(null)
        }
        onDispose {
            activity?.setTerminalKeyInterceptor(null)
            terminalVirtualCtrlDown = false
            terminalVirtualFnDown = false
        }
    }

    // ── 强制状态栏为黑色 ──────────────────────────────────
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    DisposableEffect(isTerminalMode) {
        val activity = context as? android.app.Activity
        if (isTerminalMode && activity != null) {
            androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView
            ).isAppearanceLightStatusBars = false
        }
        onDispose {
            val act = context as? android.app.Activity ?: return@onDispose
            androidx.core.view.WindowCompat.getInsetsController(
                act.window, act.window.decorView
            ).isAppearanceLightStatusBars = !isDarkTheme
        }
    }

    // ── 焦点：连接就绪后聚焦终端 View ────────────────────
    LaunchedEffect(isTerminalMode, terminalState) {
        if (isTerminalMode && terminalState == TerminalTabState.Connected) {
            terminalViewRef?.requestFocus()
        }
    }

    // ── 剪贴板粘贴助手 ──────────────────────────────────────
    fun pasteClipboardToTerminal() {
        if (terminalState != TerminalTabState.Connected) return
        coroutineScope.launch {
            val clip = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text ?: return@launch
            if (clip.isEmpty()) return@launch
            val cleaned = clip.toString()
                .replace(TERMINAL_CONTROL_CHARS_REGEX, "")
                .replace("\r\n", "\r")
                .replace('\n', '\r')
            if (cleaned.isNotEmpty()) {
                terminal.sendTerminalInput(cleaned)
            }
        }
    }

    // ── 分块发送器（Ctrl/Alt/Fn 修饰键处理）──────────────
    fun sendTerminalChunk(chunk: String) {
        if (BuildConfig.DEBUG) {
            val codes = chunk.map { String.format("%04x", it.code) }
            val remain = suppressFnTildeUntil - SystemClock.elapsedRealtime()
            AppLogger.d("TerminalInput", "sendTerminalChunk: chunk=$codes fnDown=$terminalVirtualFnDown suppressRemain=${remain}ms")
        }
        if (!terminalVirtualFnDown) {
            val now = SystemClock.elapsedRealtime()
            if (now < suppressFnTildeUntil && chunk.contains('~')) {
                if (BuildConfig.DEBUG) {
                    AppLogger.d("TerminalInput", "SUPPRESSING tilde from chunk='$chunk'")
                }
                val stripped = chunk.replace("~", "")
                suppressFnTildeUntil = 0L
                if (stripped.isEmpty()) return
                @Suppress("NAME_SHADOWING")
                val chunk = stripped
                val ctrlActive2 = terminalCtrlLatched || terminalVirtualCtrlDown
                val altActive2 = terminalAltLatched
                val processed = applyTerminalModifiers(input = chunk, ctrl = ctrlActive2, alt = altActive2)
                if (processed.isEmpty()) return
                terminal.sendTerminalInput(processed)
                if (terminalCtrlLatched) terminalCtrlLatched = false
                if (terminalAltLatched) terminalAltLatched = false
                return
            }
            if (chunk.isNotEmpty() && !chunk.contains('~')) {
                suppressFnTildeUntil = 0L
            }
        }

        val ctrlActive = terminalCtrlLatched || terminalVirtualCtrlDown
        val altActive = terminalAltLatched

        // 兼容 Termux 的快捷键：Ctrl+Alt+V 将剪贴板粘贴到终端。
        if (!terminalVirtualFnDown && ctrlActive && altActive && chunk.length == 1 && chunk[0].lowercaseChar() == 'v') {
            pasteClipboardToTerminal()
            if (terminalCtrlLatched) terminalCtrlLatched = false
            if (terminalAltLatched) terminalAltLatched = false
            return
        }

        val processed = if (terminalVirtualFnDown) {
            val fnResult = applyTermuxFnBindings(chunk, terminal.terminalCursorKeysAppMode)
            if (fnResult.showVolumeUi) {
                val audio = context.getSystemService(AudioManager::class.java)
                audio?.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_SAME,
                    AudioManager.USE_DEFAULT_STREAM_TYPE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
            if (fnResult.toggleKeyboard) {
                if (imeVisible) {
                    keyboardController?.hide()
                } else {
                    terminalViewRef?.requestFocus()
                    terminalViewRef?.context?.getSystemService(
                        android.view.inputmethod.InputMethodManager::class.java
                    )?.showSoftInput(terminalViewRef, 0)
                }
            }
            if (fnResult.output.contains("~")) {
                suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
            }
            fnResult.output
        } else {
            applyTerminalModifiers(
                input = chunk,
                ctrl = ctrlActive,
                alt = altActive
            )
        }
        if (processed.isEmpty()) return
        if (BuildConfig.DEBUG && processed.contains('~')) {
            AppLogger.d("TerminalInput", "SENDING to server: '${processed.map { String.format("%04x", it.code) }}' fnDown=$terminalVirtualFnDown")
        }
        terminal.sendTerminalInput(processed)
        if (terminalCtrlLatched) terminalCtrlLatched = false
        if (terminalAltLatched) terminalAltLatched = false
    }

    // ── 终端 UI ─────────────────────────────────────────────────
    // 相对于内容区域的 IME 内边距。
    val imeBottomRaw = WindowInsets.ime.getBottom(density)
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val imeBottomPx = (imeBottomRaw - navBottom).coerceAtLeast(0).let { adjusted ->
        if (adjusted == 0 && imeBottomRaw > 0) imeBottomRaw else adjusted
    }
    val imeBottomDp = with(density) { imeBottomPx.toDp() }
    val overlayHeightDp = with(density) { terminalOverlayHeightPx.toDp() }

    ModalNavigationDrawer(
        drawerState = terminalDrawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                drawerTonalElevation = 0.dp,
                drawerShape = ShapeTokens.none
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 240.dp, max = 320.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(vertical = SpacingTokens.SM.dp)
                            .imePadding(),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(terminalTabs, key = { it.id }) { tab ->
                                TerminalTabItem(
                                    tab = tab,
                                    selected = tab.id == activeTerminalTabId,
                                    isAmoled = isAmoled,
                                    onReconnect = {
                                        terminal.reconnectTerminalTab(tab.id) { ok ->
                                            if (!ok) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(terminalConnectFailedMsg)
                                                }
                                            }
                                        }
                                    },
                                    onClose = { terminal.closeTerminalTab(tab.id) },
                                    onClick = {
                                        terminal.switchTerminalTab(tab.id)
                                        coroutineScope.launch { terminalDrawerState.close() }
                                    },
                                )
                            }
                        }

                        HorizontalDivider()

                        TerminalDrawerActionsRow(
                            onNewTab = {
                                terminal.createTerminalTab { ok ->
                                    if (!ok) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(terminalConnectFailedMsg)
                                        }
                                    }
                                }
                            },
                            onShowKeyboard = {
                                keyboardController?.show()
                                coroutineScope.launch { terminalDrawerState.close() }
                            },
                        )

                    }

                    if (isAmoled) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.MEDIUM))
                        )
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TermuxTerminalHost(
                session = terminal.terminalSession,
                virtualCtrlDown = { terminalVirtualCtrlDown },
                virtualFnDown = { terminalVirtualFnDown },
                onPaste = ::pasteClipboardToTerminal,
                onResize = { cols, rows ->
                    terminal.resizeTerminal(cols, rows)
                },
                fontSizeSp = terminalFontSizeSp,
                onFontSizeChange = terminal::setTerminalFontSize,
                contentBottomPadding = overlayHeightDp + imeBottomDp,
                onViewReady = { tv -> terminalViewRef = tv },
                modifier = Modifier.fillMaxSize()
            )

            TerminalDrawerEdgeGesture(
                drawerState = terminalDrawerState,
                bottomPadding = overlayHeightDp + imeBottomDp,
                modifier = Modifier.align(Alignment.CenterStart)
            )

            TerminalKeyboardOverlay(
                ctrlLatched = terminalCtrlLatched,
                altLatched = terminalAltLatched,
                cursorApp = terminal.terminalCursorKeysAppMode,
                onToggleDrawer = { coroutineScope.launch { terminalDrawerState.apply { if (isOpen) close() else open() } } },
                onToggleCtrl = { terminalCtrlLatched = !terminalCtrlLatched },
                onToggleAlt = { terminalAltLatched = !terminalAltLatched },
                onSendInput = ::sendTerminalChunk,
                onCtrlC = { terminal.sendTerminalInput("\u0003") },
                onClear = { terminal.clearTerminalBuffer() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f)
                    .fillMaxWidth()
                    .padding(bottom = imeBottomDp)
                    .onSizeChanged { terminalOverlayHeightPx = it.height }
            )

        }
    }
}
