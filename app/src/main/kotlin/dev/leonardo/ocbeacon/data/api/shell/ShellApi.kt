package dev.leonardo.ocbeacon.data.api.shell

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
 * - V1 无此概念（V1 的 shell 是交互式 pty / bash 工具 part）——所有方法 V1 返回空/不支持。
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
    private val v2: V2ApiClient
) : ShellApi {

    override suspend fun listShells(conn: ServerConnection, directory: String?): List<ShellJob> =
        if (conn.apiVersion.isV2) v2.listShells(conn, directory) else emptyList()

    override suspend fun getShell(conn: ServerConnection, shellId: String, directory: String?): ShellJob? =
        if (conn.apiVersion.isV2) v2.getShell(conn, shellId, directory) else null

    override suspend fun getShellOutput(
        conn: ServerConnection,
        shellId: String,
        cursor: Long?,
        limit: Int?,
        directory: String?
    ): ShellOutput? =
        if (conn.apiVersion.isV2) v2.getShellOutput(conn, shellId, cursor, limit, directory) else null

    override suspend fun removeShell(conn: ServerConnection, shellId: String, directory: String?): Boolean =
        if (conn.apiVersion.isV2) v2.removeShell(conn, shellId, directory) else false
}
