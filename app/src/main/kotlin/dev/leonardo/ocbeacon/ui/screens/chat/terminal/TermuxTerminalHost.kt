package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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
    // sp -> px（TextUnit.toPx = value * fontScale）
    val fontSizePx = (fontSizeSp * density.fontScale).toInt()

    val client = remember(session) {
        HostClient(
            session = session,
            virtualCtrlDown = virtualCtrlDown,
            virtualFnDown = virtualFnDown,
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

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = contentBottomPadding),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(client)
                client.view = this
                attachSession(session)
                isFocusable = true
                isFocusableInTouchMode = true
                onViewReady(this)
            }
        },
        update = { view ->
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
}

/**
 * TerminalView + TerminalSessionClient 双实现（Termux 单 client 模式）。
 * view 字段由 factory 注入（构造时 view 尚不存在）。
 */
private class HostClient(
    private val session: RemoteTerminalSession,
    private val virtualCtrlDown: () -> Boolean,
    private val virtualFnDown: () -> Boolean,
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

    /** 音量下键 = 虚拟 Ctrl（ChatTerminalView 的按键拦截状态注入 Termux 主路径）。 */
    override fun readControlKey(): Boolean = virtualCtrlDown()

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    /** 音量上键 = 虚拟 Fn。 */
    override fun readFnKey(): Boolean = virtualFnDown()

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        // 本地 emulator 尺寸已定 → 转发服务器 resize（workspace 防抖链）
        val emu = session.getEmulator() ?: return
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
        view?.invalidate()
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