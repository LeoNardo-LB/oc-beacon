package dev.leonardo.ocbeacon.data.api.file

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
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

/**
 * C1-6（2026-08-27，#238 五域收编）：分发层收缩为单点路由 + 逐方法单行委托。
 * [V1ApiClient]/[V2ApiClient] 已直接实现 [FileApi]。
 */
@Singleton
class FileApiImpl @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient,
    private val dsh: DshApiClient,
) : FileApi {

    /** #276 三分：serverType==Dsh 优先（apiVersion 不参与 DSH 路由，设计 §2.1）。 */
    private fun pick(conn: ServerConnection): FileApi = when (conn.serverType) {
        dev.leonardo.ocbeacon.domain.model.ServerType.Dsh -> dsh
        else -> if (conn.apiVersion.isV2) v2 else v1
    }

    override suspend fun findFiles(
        conn: ServerConnection,
        query: String,
        type: String?,
        directory: String?,
        limit: Int?,
        dirs: String?
    ): List<String> = pick(conn).findFiles(conn, query, type, directory, limit, dirs)

    override suspend fun readFile(conn: ServerConnection, path: String, directory: String?): FileContentDto =
        pick(conn).readFile(conn, path, directory)

    override suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatchDto> =
        pick(conn).searchText(conn, pattern)

    override suspend fun probeDirectory(conn: ServerConnection, directory: String): Boolean =
        pick(conn).probeDirectory(conn, directory)

    override suspend fun listDirectory(conn: ServerConnection, path: String, directory: String?): List<FileNodeDto> =
        pick(conn).listDirectory(conn, path, directory)

    override suspend fun findSymbols(conn: ServerConnection, query: String, directory: String?): List<SymbolInfo> =
        pick(conn).findSymbols(conn, query, directory)

    override suspend fun getFileStatus(conn: ServerConnection, directory: String?): List<FileStatusInfo> =
        pick(conn).getFileStatus(conn, directory)

    override suspend fun getVcs(conn: ServerConnection, directory: String?): VcsBranchDto =
        pick(conn).getVcs(conn, directory)

    override suspend fun getVcsStatus(conn: ServerConnection, directory: String?): List<VcsChangeDto> =
        pick(conn).getVcsStatus(conn, directory)

    override suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int, directory: String?): List<FileDiffDto> =
        pick(conn).getVcsDiff(conn, mode, context, directory)

    override suspend fun listProjects(conn: ServerConnection): List<Project> =
        pick(conn).listProjects(conn)

    override suspend fun getCurrentProject(conn: ServerConnection): Project =
        pick(conn).getCurrentProject(conn)
}
