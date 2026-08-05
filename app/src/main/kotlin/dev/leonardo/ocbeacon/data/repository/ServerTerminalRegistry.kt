package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.data.terminal.ServerTerminalWorkspace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端终端工作区注册表——按服务器缓存 [ServerTerminalWorkspace] 实例。
 *
 * 注入到 ChatViewModel，使 UI 层不再直接依赖 [TerminalApi] 或
 * [ServerConnection]。服务器凭据在此处解析。
 */
@Singleton
class ServerTerminalRegistry @Inject constructor(
    private val api: TerminalApi,
    @ApplicationContext private val context: Context,
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
            return byServer.getOrPut(serverId) { ServerTerminalWorkspace(api, conn, context) }
        }
    }
}
