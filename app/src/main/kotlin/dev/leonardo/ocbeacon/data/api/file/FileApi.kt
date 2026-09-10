package dev.leonardo.ocbeacon.data.api.file

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.data.dto.response.*
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerConnection

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
     * 在服务器上创建子目录（原生通道；返回创建后的绝对路径）。
     * W4/D8：DSH = directoryPicker/createDirectory；V1/V2 无端点（默认抛
     * [UnsupportedOperationException]，仓库层转 Result 由用例回落旧通道）。
     */
    suspend fun createDirectory(conn: ServerConnection, parentDirectory: String, folderName: String): String =
        throw UnsupportedOperationException("file.createDirectory not supported by this backend")

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
