package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.mapper.FileMapper
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val api: FileApi,
    private val systemApi: SystemApi,
    private val serverRepository: ServerRepository
) : FileRepository {

    override suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>> =
        // withContext(IO)：网络请求 + JSON 解析（V2 大目录如 node_modules 响应可达 MB 级）
        // 必须移出主线程——OpenProjectDialog 的 LaunchedEffect 在 Main 调度器，
        // 旧代码在 Main 上 decode 大 JSON → ANR（性能测试实测 53 秒 .opencode 目录）
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                api.listDirectory(conn, path, directory).map { FileMapper.toDomain(it) }
            }
        }

    override suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent> =
        runCatchingCancellable {
            val conn = serverRepository.resolveConnection(serverId)
            FileMapper.toDomain(api.readFile(conn, path, directory), path)
        }

    override suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int): Result<List<String>> =
        runCatchingCancellable {
            val conn = serverRepository.resolveConnection(serverId)
            api.findFiles(conn, query = query, type = "file", directory = directory, limit = limit, dirs = null)
        }

    override suspend fun listProjects(serverId: String): Result<List<Project>> =
        runCatchingCancellable {
            val conn = serverRepository.resolveConnection(serverId)
            api.listProjects(conn)
        }

    override suspend fun probeDirectory(serverId: String, directory: String): Result<Boolean> =
        runCatchingCancellable {
            val conn = serverRepository.resolveConnection(serverId)
            api.probeDirectory(conn, directory)
        }

    override suspend fun getServerPaths(serverId: String): Result<ServerPaths> =
        runCatchingCancellable {
            val conn = serverRepository.resolveConnection(serverId)
            FileMapper.toDomain(systemApi.getServerPaths(conn))
        }

    override suspend fun findDirectories(
        serverId: String,
        directory: String,
        query: String,
        limit: Int
    ): Result<List<String>> =
        runCatchingCancellable {
            val conn = serverRepository.resolveConnection(serverId)
            api.findFiles(conn, query = query, type = "directory", directory = directory, limit = limit, dirs = null)
        }
}
