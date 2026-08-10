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
    @param:ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val byServer = mutableMapOf<String, ServerTerminalWorkspace>()

    internal fun workspaceFor(
        serverId: String,
        conn: ServerConnection,
    ): ServerTerminalWorkspace {
        synchronized(lock) {
            return byServer.getOrPut(serverId) { ServerTerminalWorkspace(api, conn, context) }
        }
    }

    /**
     * 更新指定服务器工作区的连接。
     *
     * 用于异步加载服务器配置后回填正确连接（backlog #38）：
     * ChatViewModel 构造时不再 runBlocking 等待 Room 读取，而是先用占位连接
     * 构造 TerminalDelegate（此时无用户 tab），serverConfig 加载完成后调用此方法
     * 将正确连接写入 workspace，后续 PTY API 调用使用正确连接。
     */
    fun updateConn(serverId: String, conn: ServerConnection) {
        synchronized(lock) {
            byServer[serverId]?.conn = conn
        }
    }

    /**
     * 移除并销毁指定服务器的终端工作区（关闭全部 tab、取消协程作用域）。
     * 在服务器断开连接时调用，防止终端模拟器与协程随 [byServer] 无界增长而泄漏。
     */
    fun removeWorkspace(serverId: String) {
        synchronized(lock) {
            byServer.remove(serverId)?.dispose()
        }
    }

    /** 移除并销毁全部终端工作区（断开所有服务器时调用）。 */
    fun removeAllWorkspaces() {
        synchronized(lock) {
            val all = byServer.values.toList()
            byServer.clear()
            all.forEach { it.dispose() }
        }
    }
}
