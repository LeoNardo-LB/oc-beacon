package dev.leonardo.ocbeacon.data.terminal

import android.util.Log
import dev.leonardo.ocbeacon.data.dto.common.PtySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulator

private const val TAG = "PtyToTermlibAdapter"

/**
 * Bridges a [PtySocket] to a termlib [TerminalEmulator].
 *
 * Data flow:
 *   socket.readLoop(text)  →  writeInput(utf8Bytes)   (typically emulator::writeInput)
 *   emulator.onKeyboardInput(bytes)  →  socket.send(utf8String)
 *
 * Thread safety: [bind], [dispatchKeyboardOutput], [sendInput], [release] may
 * be called from any thread. Internal state mutations are guarded by [lock].
 * The reader coroutine runs on the supplied [scope]'s dispatcher (typically
 * Dispatchers.IO inside ServerTerminalWorkspace).
 *
 * Reentrancy: per termlib's contract, callbacks (onKeyboardInput) must NOT
 * call back into emulator methods. This adapter enforces that by routing
 * keyboard output only to the socket send channel.
 *
 * P0-1 fix: this class accepts a [writeInput] lambda (plus optional
 * [onResize] / [onClearScreen]) instead of taking a [TerminalEmulator]
 * directly. termlib's TerminalEmulator is a sealed interface, so a
 * cross-module fake cannot implement it. In production, pass method
 * references (e.g. `emulator::writeInput`); in tests, pass a capturing
 * lambda. The optional [emulator] field is kept so call sites that need
 * the real emulator (e.g. the Terminal composable) can retrieve it.
 *
 * P0-2 fix: [cursorKeysApplicationMode] tracks DECSET mode 1
 * (`ESC [ ? 1 h` / `ESC [ ? 1 l`) parsed from the PTY byte stream before
 * forwarding. The state machine survives chunk boundaries.
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

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    // P0-2: DECSET mode 1 (cursor keys application mode) tracking.
    private val _cursorKeysApplicationMode = MutableStateFlow(false)
    val cursorKeysApplicationMode: StateFlow<Boolean> = _cursorKeysApplicationMode.asStateFlow()

    // State machine for parsing `ESC [ ? 1 h` / `ESC [ ? 1 l` across chunks.
    private var ckmState: CursorKeyModeParseState = CursorKeyModeParseState.IDLE

    private enum class CursorKeyModeParseState {
        IDLE, ESC, CSI, QUESTION, ONE,
    }

    /**
     * Bind a new socket, replacing any prior binding. Idempotent: calling
     * bind(null) is equivalent to release() minus the socket.close() call.
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

        val job = scope.launch {
            try {
                socket.readLoop { chunk ->
                    val bytes = chunk.toByteArray(Charsets.UTF_8)
                    scanForCursorKeyMode(bytes, 0, bytes.size)
                    writeInput(bytes, 0, bytes.size)
                    _version.value++
                }
            } catch (e: Exception) {
                Log.w(TAG, "reader loop ended", e)
            }
        }
        synchronized(lock) { readerJob = job }
    }

    /**
     * Called by the emulator's onKeyboardInput callback. Forwards the bytes
     * to the bound socket as a UTF-8 string. Safe to call from any thread;
     * the actual send is launched on [scope] to avoid blocking the emulator's
     * callback thread.
     *
     * Public for testability — in production this is invoked from inside
     * TerminalEmulatorFactory.create(onKeyboardInput = ...).
     */
    fun dispatchKeyboardOutput(bytes: ByteArray) {
        val target = synchronized(lock) { socket } ?: return
        val text = bytes.toString(Charsets.UTF_8)
        scope.launch {
            try {
                target.send(text)
            } catch (e: Exception) {
                Log.e(TAG, "failed to send keyboard output", e)
            }
        }
    }

    /**
     * Push text directly to the socket (bypasses the emulator). Used by the
     * Ctrl-C / clear / Fn-key toolbar actions that already produce ANSI
     * escape sequences.
     */
    fun sendInput(text: String) {
        val target = synchronized(lock) { socket } ?: return
        scope.launch {
            try {
                target.send(text)
            } catch (e: Exception) {
                Log.e(TAG, "failed to send input", e)
            }
        }
    }

    /**
     * Resize the emulator. termlib takes rows first, then cols — the same
     * order this method expects. No-op if no resize sink was supplied.
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
     * Test seam: bump the version counter as if writeInput completed.
     * Production callers never need this; the reader loop bumps automatically.
     */
    internal fun notifyWriteInputComplete() {
        _version.value++
    }

    /**
     * Cancel the reader and close the socket. Idempotent.
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
        if (priorSocket != null) {
            scope.launch {
                try { priorSocket.close() } catch (e: Exception) { Log.w(TAG, "priorSocket.close failed: ${e.message}", e) }
            }
        }
    }

    /**
     * Suspend until the current reader job completes (normally or via socket
     * close). Returns immediately if no reader is active. This lets a caller
     * that owns the connection lifecycle (e.g. ServerTerminalWorkspace's
     * per-tab readerJob) await the adapter's read loop without polling.
     *
     * P1-5 fix: replaces the original `delay(Long.MAX_VALUE)` pattern that
     * prevented reconnect from triggering when the socket closed.
     */
    suspend fun awaitReader() {
        val job = synchronized(lock) { readerJob } ?: return
        try {
            job.join()
        } catch (e: Exception) {
            // CancellationException from external cancel propagates; swallow
            // other exceptions (the reader logs them itself).
            Log.w(TAG, "awaitReader swallowed: ${e.message}", e)
        }
    }

    /**
     * Minimal state-machine scanner for `ESC [ ? 1 h` (DECSET 1 / application)
     * and `ESC [ ? 1 l` (DECRST 1 / normal). State persists across calls so
     * escape sequences that span chunk boundaries are still recognised.
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
