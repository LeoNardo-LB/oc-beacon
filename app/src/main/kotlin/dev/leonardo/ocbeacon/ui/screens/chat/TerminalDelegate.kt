package dev.leonardo.ocbeacon.ui.screens.chat

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.ui.screens.chat.terminal.TerminalTabState
import org.connectbot.terminal.TerminalEmulator

private const val TERMINAL_DELEGATE_TAG = "TerminalDelegate"

/**
 * Owns the server-scoped [ServerTerminalWorkspace] and all terminal tab/input operations
 * previously inlined in [ChatViewModel].
 *
 * Extracted in Phase 3 Task 1a as the delegate-extraction pilot.
 *
 * NOTE: Intentionally NOT `@Singleton`/`@Inject`. It holds per-ChatViewModel runtime context
 * (server credentials from SavedStateHandle, the ViewModel's coroutine scope, a session-directory
 * provider and the session-loaded signal) that Hilt cannot supply. ChatViewModel constructs it
 * directly and re-exposes every member as a facade, so the 81 UI files are unchanged.
 *
 * The underlying [ServerTerminalWorkspace] is itself server-scoped via [ServerTerminalRegistry]
 * (a true `@Singleton`), so terminal state still survives chat-screen recreation exactly as before.
 */
internal class TerminalDelegate(
    terminalRegistry: ServerTerminalRegistry,
    settingsRepository: SettingsRepository,
    serverId: String,
    serverUrl: String,
    username: String,
    password: String?,
    private val scope: CoroutineScope,
    private val sessionDirectoryProvider: () -> String?,
    private val sessionLoaded: CompletableDeferred<Unit>,
) {
    private val terminalWorkspace = terminalRegistry.workspaceFor(
        serverId, serverUrl, username, password,
    ).also {
        if (BuildConfig.DEBUG) {
            Log.d(
                "TerminalZoom",
                "TerminalDelegate init: workspaceId=${System.identityHashCode(it)} " +
                    "flowId=${System.identityHashCode(it.activeFontSizeSp)} serverId=$serverId " +
                    "delegateId=${System.identityHashCode(this)}"
            )
        }
    }

    init {
        // Sync the user's terminal font-size setting into the workspace default.
        scope.launch {
            settingsRepository.getSettingsFlow().map { it.terminalFontSize }.collect { size ->
                terminalWorkspace.setDefaultFontSize(size)
            }
        }
    }

    val terminalTabs: StateFlow<List<TerminalTabUi>> = terminalWorkspace.tabList
    val activeTerminalTabId: StateFlow<String?> = terminalWorkspace.activeTabId
    /** Incremented on active terminal tab updates — observe to trigger recomposition. */
    val terminalVersion: StateFlow<Long> = terminalWorkspace.activeVersion
    val terminalState: StateFlow<TerminalTabState> = terminalWorkspace.activeState
    val terminalFontSizeSp: StateFlow<Float> = terminalWorkspace.activeFontSizeSp
    val terminalEmulator: TerminalEmulator get() = terminalWorkspace.activeEmulator()
    val terminalCursorKeysAppMode: Boolean get() = terminalWorkspace.activeAdapter().cursorKeysApplicationMode.value

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            // Wait for loadSession() to finish so sessionDirectory is populated.
            // This prevents the race condition where the PTY is created with directory=null
            // and then resize is attempted with the real directory.
            sessionLoaded.await()
            val dir = sessionDirectoryProvider()
            if (BuildConfig.DEBUG) Log.d(TERMINAL_DELEGATE_TAG, "openTerminalSession: sessionDirectory=$dir")
            terminalWorkspace.ensureActiveTab(cwd = dir, directory = dir, onResult = onResult)
        }
    }

    fun createTerminalTab(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            sessionLoaded.await()
            val dir = sessionDirectoryProvider()
            terminalWorkspace.createTab(cwd = dir, directory = dir, onResult = onResult)
        }
    }

    fun switchTerminalTab(tabId: String) {
        terminalWorkspace.switchTab(tabId)
    }

    fun closeTerminalTab(tabId: String) {
        terminalWorkspace.closeTab(tabId)
    }

    fun reconnectTerminalTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        terminalWorkspace.reconnectTab(tabId, onResult)
    }

    fun setTerminalFontSize(fontSizeSp: Float) {
        terminalWorkspace.setActiveFontSize(fontSizeSp)
    }

    fun sendTerminalInput(input: String) {
        terminalWorkspace.sendActiveInput(input)
    }

    fun clearTerminalBuffer() {
        terminalWorkspace.clearActiveBuffer()
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        terminalWorkspace.resizeActive(cols, rows)
    }

    fun closeTerminalSession() {
        // Global terminal workspaces are server-scoped and survive chat screen changes.
    }
}
