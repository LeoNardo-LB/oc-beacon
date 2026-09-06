package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerPaths

interface FileRepository {
    suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>>
    suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent>
    suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int = 50): Result<List<String>>

    /** 列出服务器上的项目（worktree）。委托给 FileApi.listProjects。 */
    suspend fun listProjects(serverId: String): Result<List<Project>>

    /** 探测目录是否存在且可访问（HTTP 2xx → true）。委托给 FileApi.probeDirectory。 */
    suspend fun probeDirectory(serverId: String, directory: String): Result<Boolean>

    /** 获取服务器路径信息（home/worktree 等）。委托给 SystemApi.getServerPaths。 */
    suspend fun getServerPaths(serverId: String): Result<ServerPaths>

    /** 搜索匹配查询的目录（type=directory）。委托给 FileApi.findFiles。 */
    suspend fun findDirectories(serverId: String, directory: String, query: String, limit: Int = 50): Result<List<String>>

    /**
     * 在服务器上创建子目录（原生通道；返回创建后的绝对路径）。
     * W4/D8(2026-09-06 全量 E2E)：DSH = directoryPicker/createDirectory 直达
     * （旧 mkdir 临时会话 shell 通道在该后端双回退必败且 deleteSession 无能力位
     * → 临时会话泄漏）；V1/V2 无原生端点（默认=UnsupportedOperationException，
     * 调用方回落临时会话 shell 通道——该通道在 V1/V2 工作且支持删除清理）。
     */
    suspend fun createDirectory(serverId: String, parentDirectory: String, folderName: String): Result<String> =
        Result.failure(UnsupportedOperationException("file.createDirectory not supported by this backend"))
}
