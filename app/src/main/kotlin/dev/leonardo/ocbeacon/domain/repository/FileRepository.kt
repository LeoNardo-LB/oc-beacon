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
}
