package dev.leonardo.ocbeacon.data.api.file

import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection
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
     * 探测目录在服务器上是否存在且可访问。
     * 仅当服务器响应 HTTP 2xx 时返回 true。
     */
    suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean

    suspend fun listDirectory(conn: ServerConnection, path: String = "", directory: String? = null): List<FileNodeDto>

    /**
     * 搜索符号。
     * GET /find/symbol
     */
    suspend fun findSymbols(conn: ServerConnection, query: String, directory: String? = null): List<SymbolInfo>

    /**
     * 获取文件 git 状态。
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
    private val v1: V1ApiClient,
    private val v2: V2ApiClient
) : FileApi {

    override suspend fun findFiles(
        conn: ServerConnection,
        query: String,
        type: String?,
        directory: String?,
        limit: Int?,
        dirs: String?
    ): List<String> =
        if (conn.apiVersion.isV2) v2.findFiles(conn, query, type, directory, limit, dirs)
        else v1.findFiles(conn, query, type, directory, limit, dirs)

    override suspend fun readFile(conn: ServerConnection, path: String, directory: String?): FileContentDto =
        if (conn.apiVersion.isV2) v2.readFile(conn, path, directory) else v1.readFile(conn, path, directory)

    override suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> =
        if (conn.apiVersion.isV2) v2.searchText(conn, pattern) else v1.searchText(conn, pattern)

    override suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean =
        if (conn.apiVersion.isV2) v2.probeDirectory(conn, directory) else v1.probeDirectory(conn, directory)

    override suspend fun listDirectory(conn: ServerConnection, path: String, directory: String?): List<FileNodeDto> =
        if (conn.apiVersion.isV2) v2.listDirectory(conn, path, directory)
        else v1.listDirectory(conn, path, directory)

    override suspend fun findSymbols(conn: ServerConnection, query: String, directory: String?): List<SymbolInfo> =
        if (conn.apiVersion.isV2) v2.findSymbols(conn, query, directory) else v1.findSymbols(conn, query, directory)

    override suspend fun getFileStatus(conn: ServerConnection, directory: String?): List<FileStatusInfo> =
        if (conn.apiVersion.isV2) v2.getFileStatus(conn, directory) else v1.getFileStatus(conn, directory)

    override suspend fun getVcs(conn: ServerConnection, directory: String?): VcsBranchDto =
        if (conn.apiVersion.isV2) v2.getVcs(conn, directory) else v1.getVcs(conn, directory)

    override suspend fun getVcsStatus(conn: ServerConnection, directory: String?): List<VcsChangeDto> =
        if (conn.apiVersion.isV2) v2.getVcsStatus(conn, directory) else v1.getVcsStatus(conn, directory)

    override suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int, directory: String?): List<FileDiffDto> =
        if (conn.apiVersion.isV2) v2.getVcsDiff(conn, mode, context, directory)
        else v1.getVcsDiff(conn, mode, context, directory)

    override suspend fun listProjects(conn: ServerConnection): List<Project> =
        if (conn.apiVersion.isV2) v2.listProjects(conn) else v1.listProjects(conn)

    override suspend fun getCurrentProject(conn: ServerConnection): Project =
        if (conn.apiVersion.isV2) v2.getCurrentProject(conn) else v1.getCurrentProject(conn)
}
