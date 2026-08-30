package dev.leonardo.ocbeacon.data.api.shell

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
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
    private val v1: V1ApiClient,
    private val v2: V2ApiClient,
    private val dsh: DshApiClient,
) : ShellApi {

    /** #276 三分：serverType==Dsh 优先（apiVersion 不参与 DSH 路由，设计 §2.1）。 */
    private fun pick(conn: ServerConnection): ShellApi = when (conn.serverType) {
        dev.leonardo.ocbeacon.domain.model.ServerType.Dsh -> dsh
        else -> if (conn.apiVersion.isV2) v2 else v1
    }

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
