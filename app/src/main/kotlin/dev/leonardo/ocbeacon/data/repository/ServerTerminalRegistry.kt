package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.ui.screens.chat.ServerTerminalWorkspace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Server-side terminal workspace registry — caches [ServerTerminalWorkspace] instances per server.
 *
 * Injected into ChatViewModel so the UI layer no longer depends on [TerminalApi] or
 * [ServerConnection] directly.  Server credentials are resolved here instead.
 */
@Singleton
class ServerTerminalRegistry @Inject constructor(
    private val api: TerminalApi,
) {
    private val lock = Any()
    private val byServer = mutableMapOf<String, ServerTerminalWorkspace>()

    internal fun workspaceFor(
        serverId: String,
        serverUrl: String,
        username: String,
        password: String?,
    ): ServerTerminalWorkspace {
        val conn = ServerConnection.from(serverUrl, username, password)
        synchronized(lock) {
            return byServer.getOrPut(serverId) { ServerTerminalWorkspace(api, conn) }
        }
    }
}
