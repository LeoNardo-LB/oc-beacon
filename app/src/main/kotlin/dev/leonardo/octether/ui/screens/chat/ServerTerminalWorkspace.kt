package dev.leonardo.octether.ui.screens.chat

import android.util.Log
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.data.api.terminal.TerminalApi
import dev.leonardo.octether.data.dto.common.PtySocket
import dev.leonardo.octether.data.terminal.PtyToTermlibAdapter
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.ui.screens.chat.terminal.RecoveryAction
import dev.leonardo.octether.ui.screens.chat.terminal.TerminalTabState
import dev.leonardo.octether.ui.screens.chat.terminal.terminalRecoveryAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.connectbot.terminal.TerminalEmulatorFactory
import java.util.UUID

private const val WORKSPACE_TAG = "ServerTerminalWorkspace"
private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
private const val DEFAULT_TERMINAL_FONT_SIZE_SP = 13f
private const val DEFAULT_ROWS = 24
private const val DEFAULT_COLS = 80
/** Debounce window for coalescing high-frequency PTY resize requests (e.g. pinch-zoom). */
private const val RESIZE_DEBOUNCE_MS = 120L

data class TerminalTabUi(
    val id: String,
    val title: String,
    val state: TerminalTabState,
)

internal class ServerTerminalWorkspace(
    private val api: TerminalApi,
    private val conn: ServerConnection,
) {
    private class RuntimeTab(
        val id: String,
        var title: String,
        val adapter: PtyToTermlibAdapter,
        var fontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP,
        var directory: String? = null,
        var ptyId: String? = null,
        var socket: PtySocket? = null,
        var readerJob: Job? = null,
        var reconnectJob: Job? = null,
        var reconnectAttempt: Int = 0,
        var state: TerminalTabState = TerminalTabState.Starting,
        var lastSize: Pair<Int, Int>? = null,
        // Resize debounce: latest pending (cols, rows) awaiting coalesced send.
        var pendingResize: Pair<Int, Int>? = null,
        // Active debounce coroutine; null when no resize is pending.
        var resizeJob: Job? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = mutableListOf<RuntimeTab>()
    private val lock = Any()
    private var defaultFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP

    private val _tabList = MutableStateFlow<List<TerminalTabUi>>(emptyList())
    val tabList: StateFlow<List<TerminalTabUi>> = _tabList

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    private val _activeVersion = MutableStateFlow(0L)
    val activeVersion: StateFlow<Long> = _activeVersion

    private val _activeState = MutableStateFlow(TerminalTabState.Starting)
    val activeState: StateFlow<TerminalTabState> = _activeState

    private val _activeFontSizeSp = MutableStateFlow(DEFAULT_TERMINAL_FONT_SIZE_SP)
    val activeFontSizeSp: StateFlow<Float> = _activeFontSizeSp

    private val fallbackAdapter: PtyToTermlibAdapter = run {
        val emu = TerminalEmulatorFactory.create(
            initialRows = DEFAULT_ROWS,
            initialCols = DEFAULT_COLS,
            onKeyboardInput = { /* no-op for fallback */ },
        )
        PtyToTermlibAdapter(
            emulator = emu,
            scope = scope,
            writeInput = emu::writeInput,
            onResize = { rows, cols -> emu.resize(rows, cols) },
            onClearScreen = { emu.clearScreen() },
        )
    }

    /**
     * Returns the active tab's adapter, or the fallback when no tab is active.
     */
    fun activeAdapter(): PtyToTermlibAdapter {
        val id = _activeTabId.value ?: return fallbackAdapter
        return synchronized(lock) {
            tabs.firstOrNull { it.id == id }?.adapter ?: fallbackAdapter
        }
    }

    /** Convenience accessor for code that only needs the termlib emulator. */
    fun activeEmulator(): org.connectbot.terminal.TerminalEmulator =
        activeAdapter().emulator!!

    fun ensureActiveTab(cwd: String?, directory: String?, onResult: (Boolean) -> Unit = {}) {
        val hasActive = synchronized(lock) { activeTabLocked() != null }
        if (hasActive) {
            onResult(true)
            return
        }
        createTab(cwd = cwd, directory = directory, onResult = onResult)
    }

    fun createTab(cwd: String?, directory: String?, onResult: (Boolean) -> Unit = {}) {
        val tab = synchronized(lock) {
            val index = tabs.size + 1
            val tabId = UUID.randomUUID().toString()
            // Adapter creation: the onKeyboardInput callback needs the adapter,
            // but the adapter needs the emulator. Resolve via a holder var.
            var adapterRef: PtyToTermlibAdapter? = null
            val emulator = TerminalEmulatorFactory.create(
                initialRows = DEFAULT_ROWS,
                initialCols = DEFAULT_COLS,
                onKeyboardInput = { bytes -> adapterRef?.dispatchKeyboardOutput(bytes) },
            )
            val adapter = PtyToTermlibAdapter(
                emulator = emulator,
                scope = scope,
                writeInput = emulator::writeInput,
                onResize = { rows, cols -> emulator.resize(rows, cols) },
                onClearScreen = { emulator.clearScreen() },
            )
            adapterRef = adapter
            RuntimeTab(
                id = tabId,
                title = "Tab $index",
                adapter = adapter,
                fontSizeSp = defaultFontSizeSp,
                directory = directory,
            ).also {
                tabs.add(it)
                _activeTabId.value = it.id
                publishTabsLocked()
            }
        }
        publishActiveState()

        scope.launch {
            try {
                val info = api.createPty(
                    conn = conn,
                    title = tab.title,
                    cwd = cwd,
                    directory = directory,
                )
                val socket = api.openPtySocket(conn, info.id, cursor = 0, directory = directory)

                synchronized(lock) {
                    tab.ptyId = info.id
                    bindConnectedSocketLocked(tab, socket)
                }

                publishActiveState()
                onResult(true)
            } catch (e: Exception) {
                Log.e(WORKSPACE_TAG, "Failed to create tab", e)
                synchronized(lock) {
                    tabs.removeAll { it.id == tab.id }
                    if (_activeTabId.value == tab.id) {
                        _activeTabId.value = tabs.lastOrNull()?.id
                    }
                    publishTabsLocked()
                }
                publishActiveState()
                onResult(false)
            }
        }
    }

    fun switchTab(tabId: String) {
        synchronized(lock) {
            if (tabs.none { it.id == tabId }) return
            _activeTabId.value = tabId
        }
        publishActiveState()
    }

    fun closeTab(tabId: String) {
        val removed = synchronized(lock) {
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index == -1) return
            val tab = tabs.removeAt(index)
            if (_activeTabId.value == tabId) {
                _activeTabId.value = tabs.getOrNull(index)?.id ?: tabs.lastOrNull()?.id
            }
            publishTabsLocked()
            tab
        }

        removed.adapter.release()
        removed.readerJob?.cancel()
        removed.reconnectJob?.cancel()
        scope.launch {
            try {
                removed.socket?.close()
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "removed.socket.close failed: ${e.message}", e)
            }
            try {
                removed.ptyId?.let { api.removePty(conn, it) }
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "removePty failed: ${e.message}", e)
            }
        }
        publishActiveState()
    }

    fun sendActiveInput(input: String) {
        val socket = synchronized(lock) { activeTabLocked()?.socket } ?: return
        scope.launch {
            try {
                socket.send(input)
            } catch (e: Exception) {
                Log.e(WORKSPACE_TAG, "Failed to write terminal input", e)
            }
        }
    }

    fun clearActiveBuffer() {
        val tab = synchronized(lock) { activeTabLocked() } ?: return
        tab.adapter.clear()
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.adapter.version.value
        }
    }

    fun setActiveFontSize(fontSizeSp: Float) {
        val clamped = fontSizeSp.coerceIn(6f, 20f)
        val tab = synchronized(lock) { activeTabLocked() } ?: run {
            android.util.Log.w("TerminalZoom", "setActiveFontSize: no active tab!")
            return
        }
        if (BuildConfig.DEBUG) android.util.Log.d("TerminalZoom", "setActiveFontSize: clamped=$clamped old=${tab.fontSizeSp} tabId=${tab.id} activeId=${_activeTabId.value} flowId=${System.identityHashCode(_activeFontSizeSp)} workspaceId=${System.identityHashCode(this)}")
        tab.fontSizeSp = clamped
        if (_activeTabId.value == tab.id) {
            _activeFontSizeSp.value = clamped
            if (BuildConfig.DEBUG) android.util.Log.d("TerminalZoom", "setActiveFontSize: StateFlow updated to ${_activeFontSizeSp.value}")
        }
    }

    fun setDefaultFontSize(fontSizeSp: Float) {
        val clamped = fontSizeSp.coerceIn(6f, 20f)
        synchronized(lock) {
            defaultFontSizeSp = clamped
            if (activeTabLocked() == null) {
                _activeFontSizeSp.value = clamped
            }
        }
    }

    fun resizeActive(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val size = cols to rows
        synchronized(lock) {
            val tab = activeTabLocked() ?: return

            if (BuildConfig.DEBUG) android.util.Log.d(
                "TerminalZoom",
                "resizeActive: cols=$cols rows=$rows ptyId=${tab.ptyId} lastSize=${tab.lastSize} state=${tab.state} tabDir=${tab.directory}"
            )

            // termlib's resize takes (rows, cols) — opposite order from the old API.
            // Local emulator resize is immediate so the UI reacts without waiting on the network.
            tab.adapter.resize(rows = rows, cols = cols)
            if (_activeTabId.value == tab.id) {
                _activeVersion.value = tab.adapter.version.value
            }

            // Dedup identical sizes already acknowledged by the server while connected.
            if (tab.lastSize == size && tab.state == TerminalTabState.Connected) {
                if (BuildConfig.DEBUG) android.util.Log.d("TerminalZoom", "resizeActive: dedup, same size and connected")
                return
            }

            // Coalesce high-frequency resize requests (pinch-zoom fires every frame):
            // record the latest size and let [resizeLoop] send a single update after the
            // debounce window, rather than hitting the server on every frame.
            scheduleResizeLocked(tab, size)
        }
    }

    /**
     * Records the pending resize for [tab] and ensures exactly one [resizeLoop] is draining
     * the pending slot. Must be called while holding [lock].
     */
    private fun scheduleResizeLocked(tab: RuntimeTab, size: Pair<Int, Int>) {
        tab.pendingResize = size
        if (tab.resizeJob?.isActive != true) {
            tab.resizeJob = scope.launch { resizeLoop(tab.id) }
        }
    }

    /**
     * Debounce drain loop for PTY resize. Waits [RESIZE_DEBOUNCE_MS], then sends the latest
     * pending size in a single [TerminalApi.updatePtySize] call. If more resizes arrive while
     * the network call is in flight, the loop repeats. Exits once no pending resize remains.
     */
    private suspend fun resizeLoop(tabId: String) {
        while (true) {
            delay(RESIZE_DEBOUNCE_MS)
            val snapshot = synchronized(lock) {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return
                val pending = tab.pendingResize ?: return
                tab.pendingResize = null
                ResizeReq(pending, tab.ptyId, tab.directory, tab.state)
            }
            // Only forward to the server when the socket is live.
            if (snapshot.state != TerminalTabState.Connected || snapshot.ptyId == null) continue
            if (BuildConfig.DEBUG) android.util.Log.d(
                "TerminalZoom",
                "resizeLoop: sending updatePtySize cols=${snapshot.size.first} rows=${snapshot.size.second} dir=${snapshot.directory}"
            )
            try {
                val ok = api.updatePtySize(
                    conn = conn,
                    ptyId = snapshot.ptyId,
                    cols = snapshot.size.first,
                    rows = snapshot.size.second,
                    directory = snapshot.directory,
                )
                if (BuildConfig.DEBUG) android.util.Log.d("TerminalZoom", "resizeLoop: updatePtySize result=$ok")
                if (ok) {
                    synchronized(lock) {
                        tabs.firstOrNull { it.id == tabId }?.lastSize = snapshot.size
                    }
                } else {
                    Log.w(WORKSPACE_TAG, "Resize rejected for tab $tabId")
                }
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Failed to resize tab $tabId: ${snapshot.size.first}x${snapshot.size.second}", e)
            }
        }
    }

    fun reconnectTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        val scheduled = synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return@synchronized false
            // Decide recovery from the tab state and whether the PTY is missing.
            val action = terminalRecoveryAction(tab.state, isMissingPty = tab.ptyId == null)
            when (action) {
                RecoveryAction.None -> return@synchronized true
                RecoveryAction.Reconnect -> {
                    if (tab.reconnectJob?.isActive == true) return@synchronized true
                    tab.reconnectJob = scope.launch {
                        reconnectLoop(tabId = tab.id, immediate = true, onFirstResult = null)
                    }
                    true
                }
                RecoveryAction.Restart -> {
                    // PTY is gone (or never created); recreate it on the same tab.
                    if (tab.reconnectJob?.isActive == true) return@synchronized true
                    tab.reconnectJob = scope.launch {
                        restartLoop(tabId = tab.id)
                    }
                    true
                }
            }
        }
        onResult(scheduled)
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val copy = tabs.toList()
            tabs.clear()
            _activeTabId.value = null
            publishTabsLocked()
            copy
        }
        all.forEach { tab ->
            tab.adapter.release()
            tab.readerJob?.cancel()
            tab.reconnectJob?.cancel()
            scope.launch {
                try {
                    tab.socket?.close()
                } catch (e: Exception) {
                    Log.w(WORKSPACE_TAG, "tab.socket.close failed: ${e.message}", e)
                }
                try {
                    tab.ptyId?.let { api.removePty(conn, it) }
                } catch (e: Exception) {
                    Log.w(WORKSPACE_TAG, "removePty failed: ${e.message}", e)
                }
            }
        }
        publishActiveState()
    }

    private fun activeTabLocked(): RuntimeTab? {
        val id = _activeTabId.value ?: return null
        return tabs.firstOrNull { it.id == id }
    }

    private fun bindConnectedSocketLocked(tab: RuntimeTab, socket: PtySocket) {
        tab.socket = socket
        tab.state = TerminalTabState.Connected
        tab.reconnectAttempt = 0
        tab.reconnectJob?.cancel()
        tab.reconnectJob = null
        tab.readerJob?.cancel()

        // The adapter owns the read loop and writeInput dispatch.
        // We collect version updates and forward them to _activeVersion.
        tab.readerJob = scope.launch {
            val versionJob = scope.launch {
                tab.adapter.version.collect { v ->
                    if (_activeTabId.value == tab.id) {
                        _activeVersion.value = v
                    }
                }
            }
            try {
                tab.adapter.bind(socket)
                // Suspend until the adapter's reader completes (socket closed).
                tab.adapter.awaitReader()
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Tab stream closed: ${tab.id}", e)
            } finally {
                versionJob.cancel()
                onSocketClosed(tab.id, socket)
            }
        }
        publishTabsLocked()
        tab.lastSize?.let { (cols, rows) ->
            scope.launch {
                try {
                    val ptyId = synchronized(lock) { tabs.firstOrNull { it.id == tab.id }?.ptyId } ?: return@launch
                    api.updatePtySize(
                        conn = conn,
                        ptyId = ptyId,
                        cols = cols,
                        rows = rows,
                        directory = tab.directory,
                    )
                } catch (e: Exception) {
                    Log.w(WORKSPACE_TAG, "Failed to apply pending resize for tab ${tab.id}", e)
                }
            }
        }
    }

    private fun onSocketClosed(tabId: String, socket: PtySocket) {
        var shouldReconnect = false
        synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return
            if (tab.socket !== socket) return
            tab.socket = null
            // PTY still exists → a reconnect can reuse it (Reconnecting);
            // no ptyId → PTY is gone, must be recreated (Exited).
            tab.state = if (tab.ptyId != null) TerminalTabState.Reconnecting else TerminalTabState.Exited
            tab.readerJob = null
            tab.adapter.bind(null)
            publishTabsLocked()
            shouldReconnect = tab.ptyId != null && tab.reconnectJob?.isActive != true
            if (shouldReconnect) {
                tab.reconnectJob = scope.launch {
                    reconnectLoop(tabId = tabId, immediate = false, onFirstResult = null)
                }
            }
        }
        publishActiveState()
    }

    private suspend fun reconnectLoop(tabId: String, immediate: Boolean, onFirstResult: ((Boolean) -> Unit)?) {
        var firstAttempt = true
        while (true) {
            val snapshot = synchronized(lock) {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return
                if (tab.state == TerminalTabState.Connected) {
                    tab.reconnectJob = null
                    if (firstAttempt) onFirstResult?.invoke(true)
                    return
                }
                val pty = tab.ptyId
                if (pty == null) {
                    tab.reconnectJob = null
                    if (firstAttempt) onFirstResult?.invoke(false)
                    return
                }
                Triple(pty, tab.directory, tab.reconnectAttempt)
            }

            val delayMs = if (firstAttempt && immediate) {
                0L
            } else {
                RECONNECT_BACKOFF_MS[snapshot.third.coerceIn(0, RECONNECT_BACKOFF_MS.lastIndex)]
            }
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)

            try {
                val socket = api.openPtySocket(conn, snapshot.first, cursor = -1, directory = snapshot.second)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId }
                    if (tab == null || tab.ptyId != snapshot.first) {
                        scope.launch { socket.close() }
                        return@synchronized
                    }
                    bindConnectedSocketLocked(tab, socket)
                }
                publishActiveState()
                if (firstAttempt) onFirstResult?.invoke(true)
                return
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Reconnect failed for tab $tabId", e)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId } ?: return
                    tab.reconnectAttempt += 1
                    publishTabsLocked()
                }
                if (firstAttempt) onFirstResult?.invoke(false)
                firstAttempt = false
            }
        }
    }

    /**
     * Recovery path when the PTY itself is gone: recreates the PTY on [tabId] (preserving the
     * tab, its directory and termlib buffer), then binds a fresh socket. Retries with backoff,
     * marking the tab [TerminalTabState.Disconnected] on failure so the user can retry manually.
     */
    private suspend fun restartLoop(tabId: String) {
        var firstAttempt = true
        while (true) {
            val snapshot = synchronized(lock) {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return
                if (tab.state == TerminalTabState.Connected) {
                    tab.reconnectJob = null
                    return
                }
                tab.state = TerminalTabState.Starting
                publishTabsLocked()
                TabSeed(tab.title, tab.directory, tab.reconnectAttempt)
            }

            val delayMs = if (firstAttempt) {
                0L
            } else {
                RECONNECT_BACKOFF_MS[snapshot.attempt.coerceIn(0, RECONNECT_BACKOFF_MS.lastIndex)]
            }
            if (delayMs > 0) delay(delayMs)

            try {
                val info = api.createPty(
                    conn = conn,
                    title = snapshot.title,
                    cwd = snapshot.directory,
                    directory = snapshot.directory,
                )
                val socket = api.openPtySocket(conn, info.id, cursor = 0, directory = snapshot.directory)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId }
                    if (tab == null) {
                        scope.launch { socket.close() }
                        return@synchronized
                    }
                    tab.ptyId = info.id
                    bindConnectedSocketLocked(tab, socket)
                }
                publishActiveState()
                return
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Restart failed for tab $tabId", e)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId } ?: return
                    tab.state = TerminalTabState.Disconnected
                    tab.reconnectAttempt += 1
                    publishTabsLocked()
                }
                firstAttempt = false
            }
        }
    }

    private fun publishTabsLocked() {
        _tabList.value = tabs.map { TerminalTabUi(it.id, it.title, it.state) }
    }

    private fun publishActiveState() {
        val active = synchronized(lock) { activeTabLocked() }
        if (active == null) {
            _activeState.value = TerminalTabState.Exited
            _activeVersion.value = 0L
            _activeFontSizeSp.value = defaultFontSizeSp
            return
        }
        _activeState.value = active.state
        _activeVersion.value = active.adapter.version.value
        _activeFontSizeSp.value = active.fontSizeSp
    }
}

/** Snapshot of a pending resize request handed off from the lock to [resizeLoop]. */
private data class ResizeReq(
    val size: Pair<Int, Int>,
    val ptyId: String?,
    val directory: String?,
    val state: TerminalTabState,
)

/** Snapshot of tab identity needed to recreate a PTY in [restartLoop]. */
private data class TabSeed(
    val title: String,
    val directory: String?,
    val attempt: Int,
)
