package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.leonardo.ocbeacon.data.terminal.RemoteTerminalSession
import dev.leonardo.ocbeacon.logging.AppLogger

private const val TAG = "TermuxTerminalHost"

/**
 * Termux [TerminalView] 的 Compose 宿主（#189 换件，替代 termlib Terminal composable）。
 *
 * 职责切分：
 * - 渲染/IME/手势/文本选择/缩放 → Termux TerminalView（成熟组件）
 * - 修饰键注入：音量键虚拟 Ctrl/Fn（ChatTerminalView 状态）经
 *   [TerminalViewClient.readControlKey]/[readFnKey] 进入 Termux 输入主路径
 * - 尺寸：view onSizeChanged → session.updateSize（本地 emulator）；
 *   [onEmulatorSet] 转发 cols/rows → workspace 服务器 resize（防抖链）
 * - 字号：双指缩放阶梯化后回调 [onFontSizeChange]（设置持久化），
 *   [fontSizeSp] 变化时经 setTextSize 下发（sp→px）
 */
@Composable
internal fun TermuxTerminalHost(
    session: RemoteTerminalSession,
    virtualCtrlDown: () -> Boolean,
    virtualFnDown: () -> Boolean,
    /** 键盘条 CTRL 锁存（粘滞到下一个字符键，Termux ExtraKeys 同语义）。 */
    ctrlLatched: () -> Boolean,
    /** 键盘条 ALT 锁存。 */
    altLatched: () -> Boolean,
    /** 锁存被 Termux 输入路径消费后清除（onCodePoint 时机）。 */
    onModifiersConsumed: () -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp,
    onViewReady: (TerminalView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // sp -> px：Compose 原生换算（value * density * fontScale，等价 View 的 sp×scaledDensity）
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }.toInt()

    val client = remember(session) {
        HostClient(
            session = session,
            virtualCtrlDown = virtualCtrlDown,
            virtualFnDown = virtualFnDown,
            ctrlLatched = ctrlLatched,
            altLatched = altLatched,
            onModifiersConsumed = onModifiersConsumed,
            onPaste = onPaste,
            onResize = onResize,
            onFontSizeChange = onFontSizeChange,
        )
    }

    // UI 生命周期内绑定 session 回调（onTextChanged → invalidate 等）
    DisposableEffect(session) {
        session.updateClient(client)
        onDispose { session.updateClient(null) }
    }

    // session 身份变化（fallback → 真实 tab）时强制重建 view——factory 只在创建时跑一次，
    // 不重建会永远绑定 fallback 会话（键盘输入进真空、渲染错绑）。
    key(session) {
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = contentBottomPadding),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                // 无背景自定义 View 在 Compose interop 下命中 PFLAG_SKIP_DRAW
                //（willNotDraw 默认 true 且无 background）→ onDraw 被整体跳过。
                // Termux 原生 XML 路径不触发；AndroidView 路径必须显式关掉。
                setWillNotDraw(false)
                // Termux renderer 不画背景（空单元不绘制）——背景由宿主设置。
                // 原版设在 window decorView；AndroidView 场景设在 view 自身。
                setBackgroundColor(android.graphics.Color.BLACK)
                setTerminalViewClient(client)
                client.view = this
                attachSession(session)
                isFocusable = true
                isFocusableInTouchMode = true
                onViewReady(this)
            }
        },
        update = { view ->
            // remember(session) 重建 client 时 factory 不会重跑——每次重组重绑 view，
            // 否则新 client 的 view 为 null，onTextChanged 的 invalidate 变空操作。
            client.view = view
            if (client.lastTextSizePx != fontSizePx) {
                client.lastTextSizePx = fontSizePx
                client.syncFontSize(fontSizeSp)
                view.setTextSize(fontSizePx)
            }
        },
        onRelease = { view ->
            client.view = null
        },
    )
    } // key(session)
}

/**
 * TerminalView + TerminalSessionClient 双实现（Termux 单 client 模式）。
 * view 字段由 factory 注入（构造时 view 尚不存在）。
 */
private class HostClient(
    private val session: RemoteTerminalSession,
    private val virtualCtrlDown: () -> Boolean,
    private val virtualFnDown: () -> Boolean,
    private val ctrlLatched: () -> Boolean,
    private val altLatched: () -> Boolean,
    private val onModifiersConsumed: () -> Unit,
    private val onPaste: () -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
    private val onFontSizeChange: (Float) -> Unit,
) : TerminalViewClient, TerminalSessionClient {

    @Volatile var view: TerminalView? = null

    private var fontSizeSp = 13f
    /** update 块去重用（renderer 字段不可达）。 */
    @Volatile var lastTextSizePx: Int = -1

    fun syncFontSize(sp: Float) { fontSizeSp = sp }

    // ---- TerminalViewClient ----

    override fun onScale(scale: Float): Float {
        // Termux app 同款阶梯缩放：粗调步进，钳位 [6, 20]sp
        val stepped = if (scale > 1f) fontSizeSp + 1f else fontSizeSp - 1f
        val clamped = stepped.coerceIn(6f, 20f)
        if (clamped != fontSizeSp) {
            fontSizeSp = clamped
            onFontSizeChange(clamped)
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        // 轻点唤起软键盘（Termux TermuxActivity 同模式）
        val v = view ?: return
        v.requestFocus()
        val imm = v.context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(v, 0)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** 中文/组合输入需要 char-based IME 通道（termlib 时代 bug 的根因之一即此项缺失）。 */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    /** 虚拟 Ctrl = 音量下键按住 ∨ 键盘条 CTRL 锁存（粘滞至下一字符）。 */
    override fun readControlKey(): Boolean = virtualCtrlDown() || ctrlLatched()

    override fun readAltKey(): Boolean = altLatched()

    override fun readShiftKey(): Boolean = false

    /** 音量上键 = 虚拟 Fn。 */
    override fun readFnKey(): Boolean = virtualFnDown()

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?): Boolean {
        // 字符键已带修饰发出（readControlKey/readAltKey 的快照在此刻被消费）——
        // 清除键盘条粘滞锁存（Termux ExtraKeys 的 sticky 语义：作用一次即灭）。
        if (ctrlLatched() || altLatched()) onModifiersConsumed()
        return false
    }

    override fun onEmulatorSet() {
        // 本地 emulator 尺寸已定 → 转发服务器 resize（workspace 防抖链）
        val emu = session.getEmulator() ?: return
        syncBackground()
        onResize(emu.mColumns, emu.mRows)
    }

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.invalidate()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        AppLogger.i(TAG, "terminal session finished")
    }

    override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {}

    override fun onPasteTextFromClipboard(s: TerminalSession?) {
        onPaste()
    }

    override fun onBell(s: TerminalSession) {}

    override fun onColorsChanged(s: TerminalSession) {
        syncBackground()
        view?.invalidate()
    }

    /** emulator 背景色（OSC 11 可变）到 view 背景（renderer 只画前景文本）。 */
    private fun syncBackground() {
        val emu = session.getEmulator() ?: return
        view?.setBackgroundColor(emu.mColors.mCurrentColors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND])
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        view?.invalidate()
    }

    override fun setTerminalShellPid(s: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int? = 6 /* TC_CURSOR_BLINK */

    // ---- 日志（转 AppLogger，维度 3 可观测） ----

    override fun logError(tag: String?, message: String?) { AppLogger.e(TAG, "[" + tag + "] " + message) }
    override fun logWarn(tag: String?, message: String?) { AppLogger.w(TAG, "[" + tag + "] " + message) }
    override fun logInfo(tag: String?, message: String?) { AppLogger.i(TAG, "[" + tag + "] " + message) }
    override fun logDebug(tag: String?, message: String?) { AppLogger.d(TAG, "[" + tag + "] " + message) }
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        AppLogger.e(TAG, "[" + tag + "] " + message, e ?: IllegalStateException("(null throwable)"))
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        AppLogger.e(TAG, "[" + tag + "]", e ?: IllegalStateException("(null throwable)"))
    }
}