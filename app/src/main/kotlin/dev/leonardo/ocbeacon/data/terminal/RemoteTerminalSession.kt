package dev.leonardo.ocbeacon.data.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.nio.charset.StandardCharsets

/**
 * 远程 PTY 版的 Termux 终端会话（#189 换件）。
 *
 * Termux 原版 TerminalSession 绑定本地子进程（JNI.createSubprocess + 读/写/等待三线程）；
 * OC Beacon 的 PTY 在 OpenCode 服务器侧（WebSocket 传输），因此以本类实现
 * [TerminalSession] 桥接口 + [TerminalOutput]（emulator 输出回调）：
 *
 *   PTY 输出:  bridge.readLoop → [feedPtyOutput] → emulator.append（VT 解析）
 *   键盘输入:  TerminalView.write/writeCodePoint → bridge.sendInput → WebSocket
 *              （远程回显模型——回显由服务器 shell 负责，无本地回显路径）
 *   尺寸:      view onSizeChanged → [updateSize]（emulator 懒初始化/resize；
 *              服务器侧 resize 由 ServerTerminalWorkspace 防抖链负责，此处只管本地）
 *
 * emulator 的响应序列（DA/DSR 等）经 [write] 回送 PTY；title/剪贴板/bell 经
 * [TerminalSessionClient] 回调 UI。client 可在 view attach 后经 [updateClient] 注入。
 *
 * 线程安全：readLoop 在 bridge 的 scope（IO）；view 调用在主线程。emulator 内部
 * 缓冲的并发由其自身保护（Termux 本地版读线程同样并发喂 append，同模型）。
 */
class RemoteTerminalSession(
    private val bridge: PtyToTermlibAdapter,
    transcriptRows: Int = TRANSCRIPT_ROWS_DEFAULT,
) : TerminalOutput(), TerminalSession {

    private val lock = Any()
    @Volatile private var emulatorField: TerminalEmulator? = null
    @Volatile private var clientField: TerminalSessionClient? = null
    private val transcriptRows = transcriptRows.coerceAtLeast(MIN_TRANSCRIPT_ROWS)

    /** UI attach 时注入回调（onTextChanged → view.invalidate 等）。 */
    fun updateClient(client: TerminalSessionClient?) {
        synchronized(lock) {
            clientField = client
            emulatorField?.updateTerminalSessionClient(client ?: NoopSessionClient)
        }
    }

    // ============ TerminalSession（TerminalView 调用面） ============
    // write(String) 由 TerminalOutput 的 final 实现满足（转 write(byte[]) → bridge）。

    override fun writeCodePoint(requireControl: Boolean, codePoint: Int) {
        // 移植自 Termux TerminalSession.writeCodePoint：UTF-8 编码 + 可选 ESC 前缀（Alt）
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw IllegalArgumentException("Invalid code point: $codePoint")
        }
        val buffer = ByteArray(5)
        var pos = 0
        if (requireControl) buffer[pos++] = 27
        when {
            codePoint <= 0x7F -> buffer[pos++] = codePoint.toByte()
            codePoint <= 0x7FF -> {
                buffer[pos++] = (0xC0 or (codePoint shr 6)).toByte()
                buffer[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            codePoint <= 0xFFFF -> {
                buffer[pos++] = (0xE0 or (codePoint shr 12)).toByte()
                buffer[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                buffer[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            else -> {
                buffer[pos++] = (0xF0 or (codePoint shr 18)).toByte()
                buffer[pos++] = (0x80 or ((codePoint shr 12) and 0x3F)).toByte()
                buffer[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                buffer[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
        }
        bridge.sendInput(String(buffer, 0, pos, StandardCharsets.UTF_8))
    }

    override fun getEmulator(): TerminalEmulator? = emulatorField

    /** 最近一次 view 上报的 cell 尺寸（emulator 既有时的 resize 沿用）。 */
    @Volatile private var lastCellWidthPx: Int = 8
    @Volatile private var lastCellHeightPx: Int = 16

    override fun updateSize(columns: Int, rows: Int, fontWidthPx: Int, fontLineSpacingPx: Int) {
        if (fontWidthPx > 0) lastCellWidthPx = fontWidthPx
        if (fontLineSpacingPx > 0) lastCellHeightPx = fontLineSpacingPx
        val cellW = if (fontWidthPx > 0) fontWidthPx else lastCellWidthPx
        val cellH = if (fontLineSpacingPx > 0) fontLineSpacingPx else lastCellHeightPx
        synchronized(lock) {
            val existing = emulatorField
            if (existing == null) {
                emulatorField = TerminalEmulator(
                    /* session = */ this,
                    /* columns = */ columns,
                    /* rows = */ rows,
                    /* cellWidthPixels = */ cellW,
                    /* cellHeightPixels = */ cellH,
                    /* transcriptRows = */ transcriptRows,
                    /* client = */ clientField ?: NoopSessionClient,
                )
            } else {
                existing.resize(columns, rows, cellW, cellH)
            }
        }
    }

    // ============ TerminalOutput（emulator 输出回调） ============

    /** emulator 响应序列（DA/DSR/鼠标上报等）→ PTY。 */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        bridge.sendInput(String(data, offset, count, StandardCharsets.UTF_8))
    }

    override fun titleChanged(oldTitle: String, newTitle: String) {
        clientField?.onTitleChanged(this)
    }

    override fun onCopyTextToClipboard(text: String) {
        clientField?.onCopyTextToClipboard(this, text)
    }

    override fun onPasteTextFromClipboard() {
        clientField?.onPasteTextFromClipboard(this)
    }

    override fun onBell() {
        clientField?.onBell(this)
    }

    override fun onColorsChanged() {
        clientField?.onColorsChanged(this)
    }

    // ============ PTY 输出喂入（bridge readLoop 调用） ============

    /** PTY 字节流 → VT 解析。任意线程；emulator.append 自带同步。 */
    fun feedPtyOutput(bytes: ByteArray, offset: Int, count: Int) {
        val emu = emulatorField ?: return
        emu.append(bytes, count)
    }

    companion object {
        const val TRANSCRIPT_ROWS_DEFAULT = 2000
        const val MIN_TRANSCRIPT_ROWS = 100
    }
}

/** 无 UI 期的空回调（emulator 构造需要非空 client）。 */
internal object NoopSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}