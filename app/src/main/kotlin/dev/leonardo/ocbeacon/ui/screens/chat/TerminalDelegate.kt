package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.leonardo.ocbeacon.data.terminal.ServerTerminalWorkspace
import dev.leonardo.ocbeacon.data.terminal.TerminalTabUi
import dev.leonardo.ocbeacon.data.terminal.TerminalTabState
import org.connectbot.terminal.TerminalEmulator

private const val TERMINAL_DELEGATE_TAG = "TerminalDelegate"

/**
 * 管理服务器范围的 [ServerTerminalWorkspace] 及此前内联在 [ChatViewModel] 中的
 * 所有终端标签/输入操作。
 *
 * 注意：刻意不用 `@Singleton`/`@Inject`。它持有每个 ChatViewModel 的运行时上下文
 *（来自 SavedStateHandle 的服务器凭据、ViewModel 的协程作用域、session-directory
 * provider 和会话加载信号），Hilt 无法提供这些。ChatViewModel 直接构造它
 * 并将每个成员作为门面重新暴露，因此 81 个 UI 文件无需改动。
 *
 * 底层 [ServerTerminalWorkspace] 本身通过 [ServerTerminalRegistry]
 *（真正的 `@Singleton`）是服务器范围的，因此终端状态仍然完全如前地存活聊天屏幕重建。
 */
class TerminalDelegate(
    terminalRegistry: ServerTerminalRegistry,
    settingsRepository: SettingsRepository,
    serverId: String,
    conn: ServerConnection,
    private val scope: CoroutineScope,
    private val sessionDirectoryProvider: () -> String?,
    private val sessionLoaded: CompletableDeferred<Unit>,
    /** 门放行后 directory 仍空时的兜底重拉（返回会话 directory；失败抛异常）。 */
    private val reloadDirectory: (suspend () -> String?)? = null,
) {
    private val terminalWorkspace = terminalRegistry.workspaceFor(
        serverId, conn,
    ).also {
        if (BuildConfig.DEBUG) {
            AppLogger.d(
                "TerminalZoom",
                "TerminalDelegate init: workspaceId=${System.identityHashCode(it)} " +
                    "flowId=${System.identityHashCode(it.activeFontSizeSp)} serverId=$serverId " +
                    "delegateId=${System.identityHashCode(this)}"
            )
        }
    }

    init {
        // 将用户的终端字号设置同步到工作区默认值。
        scope.launch {
            settingsRepository.getSettingsFlow().map { it.terminalFontSize }.collect { size ->
                terminalWorkspace.setDefaultFontSize(size)
            }
        }
    }

    val terminalTabs: StateFlow<List<TerminalTabUi>> = terminalWorkspace.tabList
    val activeTerminalTabId: StateFlow<String?> = terminalWorkspace.activeTabId
    /** 活跃终端标签更新时递增 —— 观察它以触发重组。 */
    val terminalVersion: StateFlow<Long> = terminalWorkspace.activeVersion
    val terminalState: StateFlow<TerminalTabState> = terminalWorkspace.activeState
    val terminalFontSizeSp: StateFlow<Float> = terminalWorkspace.activeFontSizeSp
    val terminalEmulator: TerminalEmulator get() = terminalWorkspace.activeEmulator()
    val terminalCursorKeysAppMode: Boolean get() = terminalWorkspace.activeAdapter().cursorKeysApplicationMode.value

    fun openTerminalSession(onResult: (Boolean) -> Unit = {}) {
        scope.launch {
            // 等待 loadSession() 完成以使 sessionDirectory 被填充。
            // 这防止了 PTY 以 directory=null 创建后
            // 再用真实目录尝试 resize 的竞态条件。
            sessionLoaded.await()
            var dir = sessionDirectoryProvider()
            // 2026-08-20（P3 离线终端）：loadSession 失败（如进入会话时断网）会在
            // finally 放行门但 sessionDirectory 保持 null → PTY 落到服务器默认目录。
            // 瞬断恢复窗口（点击时网络已恢复）下由回调补拉一次会话信息兜底；
            // 仍失败（真离线）则维持 null —— createPty 请求本会失败，无错误目录可言。
            if (dir.isNullOrBlank()) {
                dir = runCatching { reloadDirectory?.invoke() }.getOrNull()
                if (BuildConfig.DEBUG) {
                    AppLogger.d(TERMINAL_DELEGATE_TAG, "openTerminalSession: dir null after gate, retry=$dir")
                }
            }
            if (BuildConfig.DEBUG) AppLogger.d(TERMINAL_DELEGATE_TAG, "openTerminalSession: sessionDirectory=$dir")
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
        // 全局终端工作区是服务器范围的，存活聊天屏幕切换。
    }
}
