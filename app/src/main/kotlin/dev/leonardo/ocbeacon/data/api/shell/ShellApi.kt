package dev.leonardo.ocbeacon.data.api.shell

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ShellJob
import dev.leonardo.ocbeacon.domain.model.ShellOutput

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
