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
 * Extracted directory-browsing delegate for SessionListViewModel.
 *
 * Owns all server filesystem operations: listing, searching, probing drives,
 * and creating directories. Caches [ServerPaths] for the delegate lifetime.
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

    /** Get server paths, caching the result for the delegate lifetime. */
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

    /** Whether the server runs on Windows (detected from home path backslashes). */
    val isWindowsServer: Boolean
        get() = cachedServerPaths?.home?.contains("\\") == true

    /** Get the server's home directory. Delegates to cached getServerPaths(). */
    suspend fun getHomeDirectory(): String = getServerPaths().home.ifBlank { "/" }

    /** List available Windows drives by probing drive letters in parallel. */
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

    /** List directories in a given path on the server. */
    suspend fun listDirectories(directory: String): List<FileNodeDto> {
        return try {
            val nodes = fileApi.listDirectory(conn, path = "", directory = directory)
            nodes.filter { it.type == "directory" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list directory: $directory", e)
            emptyList()
        }
    }

    /** Search for directories matching a query, scoped to a base directory. */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        return try {
            fileApi.findFiles(conn, query = query, type = "directory", directory = directory, limit = 50)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    /** Create a directory inside the currently browsed path. */
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
