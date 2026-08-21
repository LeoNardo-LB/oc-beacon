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

private const val TAG = "PtyToTermlibAdapter"

/**
 * 将 [PtySocket]（WebSocket PTY 传输）桥接到终端模拟器后端。
 *
 * #189 换件后为通用 PTY 桥（历史名保留以减小 diff）：
 *   PTY 输出:  socket.readLoop(text) → [onPtyOutput](utf8Bytes)（termux emulator::append）
 *   键盘输入:  sendInput(text) → socket.send（远程回显模型，见 RemoteTerminalSession）
 *
 * DECSET 光标键模式跟踪已删除——termux TerminalEmulator/KeyHandler 内部
 * 完整处理 application cursor key mode。
 *
 * 线程安全：[bind]、[sendInput]、[release] 可从任意线程调用；内部状态由
 * [lock] 保护。读取协程运行在 [scope]（ServerTerminalWorkspace 的 IO scope）。
 */
class PtyToTermlibAdapter(
    private val scope: CoroutineScope,
    private val onPtyOutput: (ByteArray, Int, Int) -> Unit,
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
                val target = synchronized(lock) { socket } ?: run {
                    AppLogger.w(TAG, "sendInput dropped: no socket bound (len=" + text.length + ")")
                    continue
                }
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
                    // 单 chunk 解析异常不杀 reader（回调链 bug 只丢一帧，不中断流）
                    runCatching { onPtyOutput(bytes, 0, bytes.size) }
                        .onFailure { e ->
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            AppLogger.e(TAG, "pty output handler failed (chunk dropped)", e)
                        }
                    _version.value++
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "reader loop ended", e)
            }
        }
        synchronized(lock) { readerJob = job }
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
}
