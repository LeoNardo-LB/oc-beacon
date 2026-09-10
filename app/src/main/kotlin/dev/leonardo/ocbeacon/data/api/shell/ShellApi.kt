package dev.leonardo.ocbeacon.data.api.shell

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台 shell 命令 API（V2 专属）。
 *
 * V2 `POST /api/shell` 启动的非交互后台命令：
 * - stdout/stderr 合并捕获到文件，可分页读取
 * - 生命周期：running → exited（exit code）或 remove 终止
 * - V1 无此概念——常量降级（emptyList/null/null/false）已下沉至
 *   [V1ApiClient] 的 ShellApi 实现（C1-8，2026-08-27 #238 五域收编）。
 */
interface ShellApi {
    suspend fun listShells(conn: ServerConnection, directory: String? = null): List<ShellJob>

    suspend fun getShell(conn: ServerConnection, shellId: String, directory: String? = null): ShellJob?

    suspend fun getShellOutput(
        conn: ServerConnection,
        shellId: String,
        cursor: Long? = null,
        limit: Int? = null,
        directory: String? = null
    ): ShellOutput?

    suspend fun removeShell(conn: ServerConnection, shellId: String, directory: String? = null): Boolean
}

@Singleton
class ShellApiImpl @Inject constructor(
    private val adapters: ServerAdapterRegistry,
) : ShellApi {

    /**
     * #391 切片 1：路由改走适配器注册表（唯一 seam）。可选端口缺席 = 该类型不提供
     * 该能力；抛错语义与迁移前具体客户端的降级实现一致。
     */
    private fun pick(conn: ServerConnection): ShellApi =
        adapters.ports(conn).shell
            ?: throw UnsupportedServerCapability("shell", conn.serverType.name)

    override suspend fun listShells(conn: ServerConnection, directory: String?): List<ShellJob> =
        pick(conn).listShells(conn, directory)

    override suspend fun getShell(conn: ServerConnection, shellId: String, directory: String?): ShellJob? =
        pick(conn).getShell(conn, shellId, directory)

    override suspend fun getShellOutput(
        conn: ServerConnection,
        shellId: String,
        cursor: Long?,
        limit: Int?,
        directory: String?
    ): ShellOutput? = pick(conn).getShellOutput(conn, shellId, cursor, limit, directory)

    override suspend fun removeShell(conn: ServerConnection, shellId: String, directory: String?): Boolean =
        pick(conn).removeShell(conn, shellId, directory)
}
