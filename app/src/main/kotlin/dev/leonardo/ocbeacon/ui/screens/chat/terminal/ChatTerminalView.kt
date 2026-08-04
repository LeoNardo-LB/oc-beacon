package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import android.content.ClipData
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
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
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.MainActivity
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 从 ChatScreen 抽取的终端模式视图。
 *
 * 渲染完整终端 UI（Drawer + SessionTerminalInline + KeyboardOverlay）
 * 并管理终端输入处理（粘贴、分块发送、修饰键）。
 */
@Composable
fun ChatTerminalView(
    viewModel: dev.leonardo.ocbeacon.ui.screens.chat.ChatViewModel,
    isTerminalMode: Boolean,
    onTerminalModeChanged: (Boolean) -> Unit,
    startInTerminalMode: Boolean,
    onNavigateBack: () -> Unit,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val terminalTabs by viewModel.terminalTabs.collectAsStateWithLifecycle()
    val activeTerminalTabId by viewModel.activeTerminalTabId.collectAsStateWithLifecycle()
    val terminalFontSizeSp by viewModel.terminalFontSizeSp.collectAsStateWithLifecycle()

    // isTerminalMode 已上提——变更通过 onTerminalModeChanged 处理
    var terminalCtrlLatched by rememberSaveable { mutableStateOf(false) }
    var terminalAltLatched by rememberSaveable { mutableStateOf(false) }
    var terminalVirtualCtrlDown by remember { mutableStateOf(false) }
    var terminalVirtualFnDown by remember { mutableStateOf(false) }
    var suppressFnTildeUntil by remember { mutableStateOf(0L) }
    val terminalFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
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
            viewModel.openTerminalSession { ok ->
                if (!ok) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                    }
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
                            Log.d("TerminalInput", "VOL_UP: action=${if (event.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"} wasDown=$wasDown nowDown=$terminalVirtualFnDown")
                        }
                        if (wasDown && !terminalVirtualFnDown) {
                            suppressFnTildeUntil = SystemClock.elapsedRealtime() + 3_000L
                            if (BuildConfig.DEBUG) {
                                Log.d("TerminalInput", "FN released -> suppressFnTildeUntil set for 3s")
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

    // ── 焦点请求器 ─────────────────────────────────────────
    LaunchedEffect(isTerminalMode, terminalState) {
        if (isTerminalMode && terminalState == TerminalTabState.Connected) {
            terminalFocusRequester.requestFocus()
        }
    }

    // ── 剪贴板粘贴助手 ──────────────────────────────────────
    fun pasteClipboardToTerminal() {
        if (terminalState != TerminalTabState.Connected) return
        coroutineScope.launch {
            val clip = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text ?: return@launch
            if (clip.isEmpty()) return@launch
            val cleaned = clip.toString()
                .replace(Regex("[\u001B\u0080-\u009F]"), "")
                .replace("\r\n", "\r")
                .replace('\n', '\r')
            if (cleaned.isNotEmpty()) {
                viewModel.sendTerminalInput(cleaned)
            }
        }
    }

    // ── 分块发送器（Ctrl/Alt/Fn 修饰键处理）──────────────
    fun sendTerminalChunk(chunk: String) {
        if (BuildConfig.DEBUG) {
            val codes = chunk.map { String.format("%04x", it.code) }
            val remain = suppressFnTildeUntil - SystemClock.elapsedRealtime()
            Log.d("TerminalInput", "sendTerminalChunk: chunk=$codes fnDown=$terminalVirtualFnDown suppressRemain=${remain}ms")
        }
        if (!terminalVirtualFnDown) {
            val now = SystemClock.elapsedRealtime()
            if (now < suppressFnTildeUntil && chunk.contains('~')) {
                if (BuildConfig.DEBUG) {
                    Log.d("TerminalInput", "SUPPRESSING tilde from chunk='$chunk'")
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
                viewModel.sendTerminalInput(processed)
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
            val fnResult = applyTermuxFnBindings(chunk, viewModel.terminalCursorKeysAppMode)
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
                    terminalFocusRequester.requestFocus()
                    keyboardController?.show()
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
            Log.d("TerminalInput", "SENDING to server: '${processed.map { String.format("%04x", it.code) }}' fnDown=$terminalVirtualFnDown")
        }
        viewModel.sendTerminalInput(processed)
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
                                        viewModel.reconnectTerminalTab(tab.id) { ok ->
                                            if (!ok) {
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
                                                }
                                            }
                                        }
                                    },
                                    onClose = { viewModel.closeTerminalTab(tab.id) },
                                    onClick = {
                                        viewModel.switchTerminalTab(tab.id)
                                        coroutineScope.launch { terminalDrawerState.close() }
                                    },
                                )
                            }
                        }

                        HorizontalDivider()

                        TerminalDrawerActionsRow(
                            onNewTab = {
                                viewModel.createTerminalTab { ok ->
                                    if (!ok) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(context.getString(R.string.chat_terminal_connect_failed))
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
            SessionTerminalInline(
                emulator = viewModel.terminalEmulator,
                focusRequester = terminalFocusRequester,
                onSendInput = ::sendTerminalChunk,
                onPaste = ::pasteClipboardToTerminal,
                onResize = { cols, rows ->
                    viewModel.resizeTerminal(cols, rows)
                },
                fontSizeSp = terminalFontSizeSp,
                onFontSizeChange = viewModel::setTerminalFontSize,
                contentBottomPadding = overlayHeightDp + imeBottomDp,
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
                cursorApp = viewModel.terminalCursorKeysAppMode,
                onToggleDrawer = { coroutineScope.launch { terminalDrawerState.apply { if (isOpen) close() else open() } } },
                onToggleCtrl = { terminalCtrlLatched = !terminalCtrlLatched },
                onToggleAlt = { terminalAltLatched = !terminalAltLatched },
                onSendInput = ::sendTerminalChunk,
                onCtrlC = { viewModel.sendTerminalInput("\u0003") },
                onClear = { viewModel.clearTerminalBuffer() },
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
