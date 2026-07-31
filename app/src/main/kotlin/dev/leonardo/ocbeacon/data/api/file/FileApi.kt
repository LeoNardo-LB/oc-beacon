package dev.leonardo.ocbeacon.data.api.file

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.api.directoryHeader
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import javax.inject.Inject
import javax.inject.Singleton

interface FileApi {
    suspend fun findFiles(
        conn: ServerConnection,
        query: String,
        type: String? = null,
        directory: String? = null,
        limit: Int? = null,
        dirs: String? = null
    ): List<String>

    suspend fun readFile(conn: ServerConnection, path: String, directory: String? = null): FileContentDto

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto>

    /**
     * Probe whether a directory exists and is accessible on the server.
     * Returns true only if the server responds with HTTP 2xx.
     */
    suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean

    suspend fun listDirectory(conn: ServerConnection, path: String = "", directory: String? = null): List<FileNodeDto>

    /**
     * Search for symbols.
     * GET /find/symbol
     */
    suspend fun findSymbols(conn: ServerConnection, query: String, directory: String? = null): List<SymbolInfo>

    /**
     * Get file git status.
     * GET /file/status
     */
    suspend fun getFileStatus(conn: ServerConnection, directory: String? = null): List<FileStatusInfo>

    suspend fun getVcs(conn: ServerConnection, directory: String? = null): VcsBranchDto

    suspend fun getVcsStatus(conn: ServerConnection, directory: String? = null): List<VcsChangeDto>

    suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3, directory: String? = null): List<FileDiffDto>

    suspend fun listProjects(conn: ServerConnection): List<Project>

    suspend fun getCurrentProject(conn: ServerConnection): Project
}

@Singleton
class FileApiImpl @Inject constructor(
    private val apiClient: ApiClient
) : FileApi {

    private val httpClient get() = apiClient.httpClient

    override suspend fun findFiles(
        conn: ServerConnection,
        query: String,
        type: String?,
        directory: String?,
        limit: Int?,
        dirs: String?
    ): List<String> {
        return httpClient.get("${conn.baseUrl}/find/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it) }
            dirs?.let { parameter("dirs", it) }
        }.body()
    }

    override suspend fun readFile(conn: ServerConnection, path: String, directory: String?): FileContentDto {
        return httpClient.get("${conn.baseUrl}/file/content") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }.body()
    }

    override suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> {
        return httpClient.get("${conn.baseUrl}/find") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("pattern", pattern)
        }.body()
    }

    /**
     * Probe whether a directory exists and is accessible on the server.
     * Returns true only if the server responds with HTTP 2xx.
     */
    override suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean {
        val response = httpClient.get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", "")
        }
        return response.status.isSuccess()
    }

    override suspend fun listDirectory(conn: ServerConnection, path: String, directory: String?): List<FileNodeDto> {
        val response = httpClient.get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            return emptyList()
        }
        return response.body()
    }

    /**
     * Search for symbols.
     * GET /find/symbol
     */
    override suspend fun findSymbols(conn: ServerConnection, query: String, directory: String?): List<SymbolInfo> {
        return httpClient.get("${conn.baseUrl}/find/symbol") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("query", query)
        }.body()
    }

    /**
     * Get file git status.
     * GET /file/status
     */
    override suspend fun getFileStatus(conn: ServerConnection, directory: String?): List<FileStatusInfo> {
        return httpClient.get("${conn.baseUrl}/file/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    override suspend fun getVcs(conn: ServerConnection, directory: String?): VcsBranchDto {
        return httpClient.get("${conn.baseUrl}/vcs") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    override suspend fun getVcsStatus(conn: ServerConnection, directory: String?): List<VcsChangeDto> {
        return httpClient.get("${conn.baseUrl}/vcs/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
        }.body()
    }

    override suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int, directory: String?): List<FileDiffDto> {
        return httpClient.get("${conn.baseUrl}/vcs/diff") {
            conn.authHeader?.let { header("Authorization", it) }
            directoryHeader(directory)
            parameter("mode", mode)
            parameter("context", context)
        }.body()
    }

    override suspend fun listProjects(conn: ServerConnection): List<Project> {
        return httpClient.get("${conn.baseUrl}/project") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    override suspend fun getCurrentProject(conn: ServerConnection): Project {
        return httpClient.get("${conn.baseUrl}/project/current") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }
}
