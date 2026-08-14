package dev.leonardo.ocbeacon.data.terminal

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.data.dto.common.PtySocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator

private const val TAG = "PtyToTermlibAdapter"

/**
 * 将 [PtySocket] 桥接到 termlib 的 [TerminalEmulator]。
 *
 * 数据流：
 *   socket.readLoop(text)  →  writeInput(utf8Bytes)   （通常为 emulator::writeInput）
 *   emulator.onKeyboardInput(bytes)  →  socket.send(utf8String)
 *
 * 线程安全：[bind]、[dispatchKeyboardOutput]、[sendInput]、[release] 可
 * 从任意线程调用。内部状态变更由 [lock] 保护。
 * 读取协程在所提供的 [scope] 的 dispatcher 上运行（通常为
 * ServerTerminalWorkspace 内的 Dispatchers.IO）。
 *
 * 重入性：按 termlib 契约，回调（onKeyboardInput）不得
 * 回调 emulator 方法。此适配器通过将键盘输出仅路由到
 * socket 发送通道来强制执行此约束。
 *
 * P0-1 修复：此类接受 [writeInput] lambda（以及可选的
 * [onResize] / [onClearScreen]），而非直接接收 [TerminalEmulator]。
 * termlib 的 TerminalEmulator 是密封接口，因此跨模块 fake 无法
 * 实现它。生产环境中传递方法引用（例如 `emulator::writeInput`）；
 * 测试中传递捕获 lambda。保留可选的 [emulator] 字段，以便需要
 * 真实 emulator 的调用点（例如 Terminal composable）能取回它。
 *
 * P0-2 修复：[cursorKeysApplicationMode] 跟踪从 PTY 字节流中解析出的
 * DECSET 模式 1（`ESC [ ? 1 h` / `ESC [ ? 1 l`），然后再转发。
 * 该状态机能跨数据块边界存活。
 */
class PtyToTermlibAdapter(
    val emulator: TerminalEmulator? = null,
    private val scope: CoroutineScope,
    private val writeInput: (ByteArray, Int, Int) -> Unit,
    private val onResize: ((rows: Int, cols: Int) -> Unit)? = null,
    private val onClearScreen: (() -> Unit)? = null,
) {
    private val lock = Any()
    private var socket: PtySocket? = null
    private var readerJob: Job? = null
    // #116（D2-20）：单发送 actor——fire-and-forget launch 并发 send 会乱序
    //（快速键盘输入/多线程回调）；Channel 单消费者协程保证发送顺序
    // 生命周期：bind 启动 / release 停止（重连时重启）——不在构造时启动
    //（测试 scope 下无限循环协程会导致 UncompletedCoroutinesError）
    private val sendChannel = Channel<String>(Channel.BUFFERED)
    private var senderJob: Job? = null

    private fun startSender() {
        if (senderJob?.isActive == true) return
        senderJob = scope.launch {
            for (text in sendChannel) {
                val target = synchronized(lock) { socket } ?: continue
                try {
                    target.send(text)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AppLogger.e(TAG, "failed to send to pty socket", e)
                }
            }
        }
    }

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    // P0-2：DECSET 模式 1（光标键应用模式）跟踪。
    private val _cursorKeysApplicationMode = MutableStateFlow(false)
    val cursorKeysApplicationMode: StateFlow<Boolean> = _cursorKeysApplicationMode.asStateFlow()

    // 用于跨数据块解析 `ESC [ ? 1 h` / `ESC [ ? 1 l` 的状态机。
    private var ckmState: CursorKeyModeParseState = CursorKeyModeParseState.IDLE

    private enum class CursorKeyModeParseState {
        IDLE, ESC, CSI, QUESTION, ONE,
    }

    /**
     * 绑定新 socket，替换任何先前的绑定。幂等：调用
     * bind(null) 等价于 release()，但不调用 socket.close()。
     */
    fun bind(socket: PtySocket?) {
        val priorJob: Job?
        synchronized(lock) {
            priorJob = readerJob
            this.socket = socket
            readerJob = null
        }
        priorJob?.cancel()
        if (socket == null) return
        startSender()

        val job = scope.launch {
            try {
                socket.readLoop { chunk ->
                    val bytes = chunk.toByteArray(Charsets.UTF_8)
                    scanForCursorKeyMode(bytes, 0, bytes.size)
                    writeInput(bytes, 0, bytes.size)
                    _version.value++
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "reader loop ended", e)
            }
        }
        synchronized(lock) { readerJob = job }
    }

    /**
     * 由 emulator 的 onKeyboardInput 回调调用。将字节作为 UTF-8 字符串
     * 转发到已绑定的 socket。可从任意线程安全调用；
     * 实际发送在 [scope] 上启动，以避免阻塞 emulator 的回调线程。
     *
     * 公开以支持测试——生产环境中它从
     * TerminalEmulatorFactory.create(onKeyboardInput = ...) 内部调用。
     */
    fun dispatchKeyboardOutput(bytes: ByteArray) {
        // #116（D2-20）：入队发送 actor（串行保序；原 fire-and-forget 并发乱序）
        sendChannel.trySend(bytes.toString(Charsets.UTF_8))
    }

    /**
     * 将文本直接推送到 socket（绕过 emulator）。用于
     * 已产生 ANSI 转义序列的 Ctrl-C / clear / Fn 键工具栏操作。
     */
    fun sendInput(text: String) {
        // #116（D2-20）：入队发送 actor（串行保序）
        sendChannel.trySend(text)
    }

    /**
     * 调整 emulator 大小。termlib 先取 rows，再取 cols——与此方法
     * 期望的顺序一致。若未提供 resize 接收器则为空操作。
     */
    fun resize(rows: Int, cols: Int) {
        if (rows <= 0 || cols <= 0) return
        onResize?.invoke(rows, cols)
        _version.value++
    }

    fun clear() {
        onClearScreen?.invoke()
        _version.value++
    }

    /**
     * 测试接缝：像 writeInput 完成那样递增版本计数器。
     * 生产调用方永不需要此方法；读取循环会自动递增。
     */
    internal fun notifyWriteInputComplete() {
        _version.value++
    }

    /**
     * 取消读取器并关闭 socket。幂等。
     */
    fun release() {
        val (priorJob, priorSocket) = synchronized(lock) {
            val j = readerJob
            val s = socket
            readerJob = null
            socket = null
            j to s
        }
        priorJob?.cancel()
        // #116（D2-20）：停止发送 actor；bind 重连时 startSender 重启
        senderJob?.cancel()
        senderJob = null
        if (priorSocket != null) {
            scope.launch {
                try { priorSocket.close() } catch (e: Exception) { AppLogger.w(TAG, "priorSocket.close failed: ${e.message}", e) }
            }
        }
    }

    /**
     * 挂起直到当前读取 job 完成（正常完成或 socket 关闭）。
     * 无活跃读取器时立即返回。这让拥有连接生命周期的调用方
     *（例如 ServerTerminalWorkspace 的按 tab readerJob）无需轮询即可
     * 等待适配器的读取循环。
     *
     * P1-5 修复：替代了原先阻止 socket 关闭时触发重连的
     * `delay(Long.MAX_VALUE)` 模式。
     */
    suspend fun awaitReader() {
        val job = synchronized(lock) { readerJob } ?: return
        try {
            job.join()
        } catch (e: Exception) {
            // 外部取消产生的 CancellationException 会传播；吞掉
            // 其他异常（读取器自身会记录它们）。
            if (e is CancellationException) throw e
            AppLogger.w(TAG, "awaitReader swallowed: ${e.message}", e)
        }
    }

    /**
     * 用于 `ESC [ ? 1 h`（DECSET 1 / application）和
     * `ESC [ ? 1 l`（DECRST 1 / normal）的最小状态机扫描器。
     * 状态跨调用持久化，因此跨越数据块边界的转义序列仍能被识别。
     */
    private fun scanForCursorKeyMode(bytes: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            ckmState = when (ckmState) {
                CursorKeyModeParseState.IDLE -> when (b) {
                    0x1B -> CursorKeyModeParseState.ESC // ESC
                    else -> CursorKeyModeParseState.IDLE
                }
                CursorKeyModeParseState.ESC -> when (b) {
                    '['.code -> CursorKeyModeParseState.CSI
                    else -> CursorKeyModeParseState.IDLE
                }
                CursorKeyModeParseState.CSI -> when (b) {
                    '?'.code -> CursorKeyModeParseState.QUESTION
                    else -> CursorKeyModeParseState.IDLE
                }
                CursorKeyModeParseState.QUESTION -> when (b) {
                    '1'.code -> CursorKeyModeParseState.ONE
                    else -> CursorKeyModeParseState.IDLE
                }
                CursorKeyModeParseState.ONE -> when (b) {
                    'h'.code -> {
                        _cursorKeysApplicationMode.value = true
                        CursorKeyModeParseState.IDLE
                    }
                    'l'.code -> {
                        _cursorKeysApplicationMode.value = false
                        CursorKeyModeParseState.IDLE
                    }
                    else -> CursorKeyModeParseState.IDLE
                }
            }
            i++
        }
    }
}
