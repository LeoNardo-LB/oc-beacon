package dev.leonardo.ocbeacon.ui.screens.sessions

import android.util.Log
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.dto.response.FileNodeDto
import dev.leonardo.ocbeacon.data.dto.response.ServerPaths
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.usecase.DeleteSessionUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

private const val TAG = "DirectoryManager"

/**
 * 从 SessionListViewModel 抽取的目录浏览委托。
 *
 * 持有所有服务器文件系统操作：列表、搜索、探测盘符、
 * 创建目录。在委托生命周期内缓存 [ServerPaths]。
 */
class DirectoryManager(
    private val fileApi: FileApi,
    private val sessionApi: SessionApi,
    private val systemApi: SystemApi,
    private val terminalApi: TerminalApi,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val conn: ServerConnection,
    private val serverId: String,
) {

    private var cachedServerPaths: ServerPaths? = null

    /** 获取服务器路径，结果在委托生命周期内缓存。 */
    suspend fun getServerPaths(): ServerPaths {
        if (cachedServerPaths == null) {
            cachedServerPaths = try {
                systemApi.getServerPaths(conn)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get server paths", e)
                ServerPaths()
            }
            if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) Log.d(TAG, "Server home directory: ${cachedServerPaths!!.home}")
        }
        return cachedServerPaths!!
    }

    /** 服务器是否运行在 Windows 上（通过主目录路径中的反斜杠检测）。 */
    val isWindowsServer: Boolean
        get() = cachedServerPaths?.home?.contains("\\") == true

    /** 获取服务器主目录。委托给已缓存的 getServerPaths()。 */
    suspend fun getHomeDirectory(): String = getServerPaths().home.ifBlank { "/" }

    /** 通过并行探测盘符列出可用的 Windows 盘符。 */
    suspend fun listWindowsDrives(): List<FileNodeDto> = coroutineScope {
        ('C'..'Z').map { letter ->
            async {
                val drivePath = "$letter:\\"
                try {
                    val response = fileApi.probeDirectory(conn, drivePath)
                    if (response) {
                        FileNodeDto(
                            name = "$letter:",
                            path = drivePath,
                            type = "directory",
                            absolute = drivePath,
                        )
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** 列出服务器上指定路径中的目录。 */
    suspend fun listDirectories(directory: String): List<FileNodeDto> {
        return try {
            val nodes = fileApi.listDirectory(conn, path = "", directory = directory)
            nodes.filter { it.type == "directory" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list directory: $directory", e)
            emptyList()
        }
    }

    /** 在基础目录范围内搜索匹配查询的目录。 */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        return try {
            fileApi.findFiles(conn, query = query, type = "directory", directory = directory, limit = 50)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    /** 在当前浏览的路径下创建目录。 */
    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> {
        val sanitized = folderName.trim().trim('/').replace(Regex("/+"), "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
            }

            val tempSession = sessionApi.createSession(
                conn = conn,
                title = "mkdir",
                directory = parentDirectory,
            )

            try {
                val escaped = sanitized.replace("'", "'\"'\"'")
                val command = "mkdir -p -- '$escaped'"

                val runShellOk = runCatching {
                    terminalApi.runShellCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = command,
                        agent = "build",
                        directory = parentDirectory,
                    )
                }.getOrElse { false }

                if (!runShellOk) {
                    val executeOk = sessionApi.executeCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = "bash",
                        arguments = "-lc \"$command\"",
                        directory = parentDirectory,
                    )
                    if (!executeOk) {
                        throw IllegalStateException("Failed to create directory")
                    }
                }
            } finally {
                runCatching { deleteSessionUseCase(serverId, tempSession.id) }
            }

            repeat(6) {
                if (directoryExists(targetDirectory)) {
                    return@runCatching targetDirectory
                }
                delay(200)
            }

            throw IllegalStateException("Directory was not created")
        }
    }

    private suspend fun directoryExists(directory: String): Boolean {
        return try {
            fileApi.listDirectory(conn, path = "", directory = directory)
            true
        } catch (_: Exception) {
            false
        }
    }
}
