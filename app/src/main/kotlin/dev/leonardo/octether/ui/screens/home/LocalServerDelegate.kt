package dev.leonardo.octether.ui.screens.home

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import dev.leonardo.octether.R
import dev.leonardo.octether.data.repository.LocalServerManager
import dev.leonardo.octether.domain.model.AppSettings
import dev.leonardo.octether.domain.model.ServerConfig
import dev.leonardo.octether.domain.repository.ServerRepository
import dev.leonardo.octether.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val LOCAL_SERVER_NAME = "Local OpenCode"

private data class LocalRuntimeErrorInfo(
    val message: String,
    val fixCommand: String? = null,
    val status: LocalRuntimeStatus = LocalRuntimeStatus.Error,
    val requiresOverlaySettings: Boolean = false,
)

/**
 * Owns the local OpenCode server lifecycle: start, stop, and health polling.
 *
 * Extracted from [HomeViewModel] — holds [localAutoStartTriggered] and the
 * private helpers ([waitForLocalServerReady], [ensureLocalServerExists],
 * [mapLocalRuntimeError]) that are only used by these three operations.
 *
 * NOT `@Singleton`/`@Inject` — constructed directly by [HomeViewModel] with the
 * ViewModel's coroutine scope and cross-cutting callbacks.
 */
internal class LocalServerDelegate(
    private val application: Application,
    private val scope: CoroutineScope,
    private val localServerManager: LocalServerManager,
    private val updateSettingsUseCase: UpdateSettingsUseCase,
    private val serverRepository: ServerRepository,
    private val uiState: MutableStateFlow<HomeUiState>,
    private val currentSettingsProvider: () -> AppSettings,
    private val connectToServer: (String) -> Unit,
    private val disconnectFromServer: (String) -> Unit,
) {

    private var localAutoStartTriggered = false

    fun refreshLocalRuntimeState() {
        scope.launch {
            val termuxInstalled = localServerManager.isTermuxInstalled()
            if (!termuxInstalled) {
                uiState.update {
                    it.copy(
                        termuxInstalled = false,
                        localRuntimeStatus = LocalRuntimeStatus.Unavailable,
                        localRuntimeMessage = null,
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                        setupCommand = null,
                    )
                }
                return@launch
            }

            val serverUsername = uiState.value.localServerUsername.trim().ifBlank { "opencode" }
            val serverPassword = uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
            val healthy = localServerManager.isServerHealthy(
                username = serverUsername,
                password = serverPassword,
            )
            if (healthy) {
                // Server is running — mark setup as done (in case flag was never set)
                updateSettingsUseCase(currentSettingsProvider().copy(localSetupCompleted = true))
                uiState.update {
                    it.copy(
                        termuxInstalled = true,
                        localRuntimeStatus = LocalRuntimeStatus.Running,
                        localRuntimeMessage = null,
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                        setupCommand = null,
                    )
                }
                // Auto-create local server entry and connect
                val localServer = ensureLocalServerExists()
                if (!uiState.value.connectedServerIds.contains(localServer.id) &&
                    !uiState.value.connectingServerIds.contains(localServer.id)
                ) {
                    connectToServer(localServer.id)
                }
                return@launch
            }

            // Server not healthy — check if setup was ever completed
            val setupDone = currentSettingsProvider().localSetupCompleted
            uiState.update {
                it.copy(
                    termuxInstalled = true,
                    localRuntimeStatus = if (setupDone) LocalRuntimeStatus.Stopped else LocalRuntimeStatus.NeedsSetup,
                    localRuntimeMessage = null,
                    localRuntimeFixCommand = null,
                    localRuntimeNeedsOverlaySettings = false,
                    setupCommand = if (!setupDone) localServerManager.getSetupCommand() else null,
                )
            }

            if (setupDone && !localAutoStartTriggered &&
                currentSettingsProvider().localServerRunInBackground &&
                currentSettingsProvider().localServerAutoStart
            ) {
                localAutoStartTriggered = true
                startLocalServer(application)
            }
        }
    }

    fun startLocalServer(callerContext: Context) {
        uiState.update {
            it.copy(
                localRuntimeStatus = LocalRuntimeStatus.Starting,
                localRuntimeMessage = null,
                localRuntimeFixCommand = null,
                localRuntimeNeedsOverlaySettings = false,
            )
        }

        scope.launch {
            if (!localServerManager.isTermuxInstalled()) {
                uiState.update {
                    it.copy(
                        termuxInstalled = false,
                        localRuntimeStatus = LocalRuntimeStatus.Unavailable,
                        localRuntimeMessage = null,
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                    )
                }
                return@launch
            }

            val proxyUrl = uiState.value.localProxyUrl.trim().takeIf {
                uiState.value.localProxyEnabled && it.isNotBlank()
            }
            val noProxyList = uiState.value.localProxyNoProxy
            val hostName = if (uiState.value.localServerAllowLan) "0.0.0.0" else "127.0.0.1"
            val serverUsername = uiState.value.localServerUsername.trim().takeIf { it.isNotBlank() }
            val serverPassword = uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
            val runInBackground = uiState.value.localServerRunInBackground
            val startResult = localServerManager.startServer(
                callerContext = callerContext,
                proxyUrl = proxyUrl,
                noProxyList = noProxyList,
                hostName = hostName,
                serverUsername = serverUsername,
                serverPassword = serverPassword,
                runInBackground = runInBackground,
            )
            if (startResult.isFailure) {
                val errorInfo = mapLocalRuntimeError(startResult.exceptionOrNull()?.message)
                if (errorInfo.status == LocalRuntimeStatus.NeedsSetup) {
                    updateSettingsUseCase(currentSettingsProvider().copy(localSetupCompleted = false))
                }
                uiState.update {
                    it.copy(
                        termuxInstalled = true,
                        localRuntimeStatus = errorInfo.status,
                        localRuntimeMessage = errorInfo.message,
                        localRuntimeFixCommand = errorInfo.fixCommand,
                        localRuntimeNeedsOverlaySettings = errorInfo.requiresOverlaySettings,
                        setupCommand = if (errorInfo.status == LocalRuntimeStatus.NeedsSetup) {
                            localServerManager.getSetupCommand()
                        } else null,
                    )
                }
                return@launch
            }

            val startupTimeoutMs = uiState.value.localServerStartupTimeoutSec.coerceIn(10, 120) * 1000L
            val ready = waitForLocalServerReady(
                timeoutMs = startupTimeoutMs,
                username = serverUsername ?: "opencode",
                password = serverPassword,
            )
            if (!ready) {
                uiState.update {
                    it.copy(
                        termuxInstalled = true,
                        localRuntimeStatus = LocalRuntimeStatus.Error,
                        localRuntimeMessage = s(R.string.home_local_error_timeout),
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                    )
                }
                return@launch
            }

            updateSettingsUseCase(currentSettingsProvider().copy(localSetupCompleted = true))
            val localServer = ensureLocalServerExists()
            uiState.update {
                it.copy(
                    termuxInstalled = true,
                    localRuntimeStatus = LocalRuntimeStatus.Running,
                    localRuntimeMessage = null,
                    localRuntimeFixCommand = null,
                    localRuntimeNeedsOverlaySettings = false,
                )
            }

            if (!uiState.value.connectedServerIds.contains(localServer.id) &&
                !uiState.value.connectingServerIds.contains(localServer.id)
            ) {
                connectToServer(localServer.id)
            }
        }
    }

    fun stopLocalServer(callerContext: Context) {
        uiState.update {
            it.copy(
                localRuntimeStatus = LocalRuntimeStatus.Stopping,
                localRuntimeMessage = null,
                localRuntimeFixCommand = null,
                localRuntimeNeedsOverlaySettings = false,
            )
        }

        scope.launch {
            val stopResult = localServerManager.stopServer(callerContext)
            if (stopResult.isFailure) {
                val errorInfo = mapLocalRuntimeError(stopResult.exceptionOrNull()?.message)
                uiState.update {
                    it.copy(
                        localRuntimeStatus = LocalRuntimeStatus.Error,
                        localRuntimeMessage = errorInfo.message,
                        localRuntimeFixCommand = errorInfo.fixCommand,
                        localRuntimeNeedsOverlaySettings = errorInfo.requiresOverlaySettings,
                    )
                }
                return@launch
            }

            val localServerId = uiState.value.servers.firstOrNull {
                it.url == LocalServerManager.LOCAL_SERVER_URL
            }?.id
            if (localServerId != null) {
                disconnectFromServer(localServerId)
            }

            repeat(6) {
                delay(1000)
                val username = uiState.value.localServerUsername.trim().ifBlank { "opencode" }
                val password = uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
                if (!localServerManager.isServerHealthy(username = username, password = password)) {
                    uiState.update {
                        it.copy(
                            localRuntimeStatus = LocalRuntimeStatus.Stopped,
                            localRuntimeMessage = null,
                            localRuntimeFixCommand = null,
                            localRuntimeNeedsOverlaySettings = false,
                        )
                    }
                    return@launch
                }
            }

            uiState.update {
                it.copy(
                    localRuntimeStatus = LocalRuntimeStatus.Stopped,
                    localRuntimeMessage = s(R.string.home_local_message_stop_sent),
                    localRuntimeFixCommand = null,
                    localRuntimeNeedsOverlaySettings = false,
                )
            }
        }
    }

    // ── Private helpers ──

    private suspend fun waitForLocalServerReady(
        timeoutMs: Long = 30000L,
        username: String,
        password: String?,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (localServerManager.isServerHealthy(username = username, password = password)) {
                return true
            }
            delay(1500)
        }
        return false
    }

    private suspend fun ensureLocalServerExists(): ServerConfig {
        val desiredUsername = uiState.value.localServerUsername.trim().ifBlank { "opencode" }
        val desiredPassword = uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }

        val existing = uiState.value.servers.firstOrNull {
            it.url == LocalServerManager.LOCAL_SERVER_URL
        }
        if (existing != null) {
            if (existing.username != desiredUsername || existing.password != desiredPassword) {
                val updated = existing.copy(
                    username = desiredUsername,
                    password = desiredPassword,
                )
                serverRepository.updateServer(updated)
                return updated
            }
            return existing
        }

        val newServer = ServerConfig(
            id = UUID.randomUUID().toString(),
            url = LocalServerManager.LOCAL_SERVER_URL,
            username = desiredUsername,
            password = desiredPassword,
            name = LOCAL_SERVER_NAME,
            autoConnect = false,
        )
        serverRepository.addServer(newServer)
        return newServer
    }

    private fun mapLocalRuntimeError(rawMessage: String?): LocalRuntimeErrorInfo {
        val raw = rawMessage.orEmpty()
        val lower = raw.lowercase()
        return when {
            "allow-external-apps" in lower -> {
                LocalRuntimeErrorInfo(
                    message = s(R.string.home_local_error_termux_blocked_external),
                    fixCommand = "mkdir -p ~/.termux && (grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null && sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties || echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings",
                    status = LocalRuntimeStatus.NeedsSetup,
                )
            }

            "display over other apps" in lower || "draw over other apps" in lower -> {
                LocalRuntimeErrorInfo(
                    message = s(R.string.home_local_error_termux_overlay_permission),
                    requiresOverlaySettings = true,
                )
            }

            "run_command" in lower && "without permission" in lower -> {
                LocalRuntimeErrorInfo(s(R.string.home_local_error_run_command_permission))
            }

            "app is in background" in lower -> {
                LocalRuntimeErrorInfo(s(R.string.home_local_error_background_launch))
            }

            "regular file not found" in lower && "opencode-local" in lower -> {
                LocalRuntimeErrorInfo(
                    message = s(R.string.home_local_error_not_installed),
                    status = LocalRuntimeStatus.NeedsSetup,
                )
            }

            raw.isNotBlank() -> LocalRuntimeErrorInfo(raw)
            else -> LocalRuntimeErrorInfo(s(R.string.home_local_error_launch_failed))
        }
    }

    private fun s(@StringRes id: Int): String = application.getString(id)
}
