package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
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
    private val adapters: ServerAdapterRegistry,
    private val serverRepository: ServerRepository
) : FileRepository {

    override suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>> =
        // withContext(IO)：网络请求 + JSON 解析（V2 大目录如 node_modules 响应可达 MB 级）
        // 必须移出主线程——OpenProjectDialog 的 LaunchedEffect 在 Main 调度器，
        // 旧代码在 Main 上 decode 大 JSON → ANR（性能测试实测 53 秒 .opencode 目录）
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                adapters.ports(conn).requireFile(conn).listDirectory(conn, path, directory).map { FileMapper.toDomain(it) }
            }
        }

    /** W4/D8(2026-09-06)：原生建目录委托（DSH=picker;V1/V2 抛 Unsupported→用例回落）。 */
    override suspend fun createDirectory(serverId: String, parentDirectory: String, folderName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                adapters.ports(conn).requireFile(conn).createDirectory(conn, parentDirectory, folderName)
            }
        }

    // #137（D2-L60）：网络 IO + JSON 解析统一移出主线程（原仅 listDirectory
    // 有 withContext(IO)——其余 6 方法在调用方协程（可能 Main）执行网络请求）
    override suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                FileMapper.toDomain(adapters.ports(conn).requireFile(conn).readFile(conn, path, directory), path)
            }
        }

    override suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                adapters.ports(conn).requireFile(conn).findFiles(conn, query = query, type = "file", directory = directory, limit = limit, dirs = null)
            }
        }

    override suspend fun listProjects(serverId: String): Result<List<Project>> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                adapters.ports(conn).requireFile(conn).listProjects(conn)
            }
        }

    override suspend fun probeDirectory(serverId: String, directory: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                adapters.ports(conn).requireFile(conn).probeDirectory(conn, directory)
            }
        }

    override suspend fun getServerPaths(serverId: String): Result<ServerPaths> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                FileMapper.toDomain(adapters.ports(conn).system.getServerPaths(conn))
            }
        }

    override suspend fun findDirectories(
        serverId: String,
        directory: String,
        query: String,
        limit: Int
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val conn = serverRepository.resolveConnection(serverId)
                adapters.ports(conn).requireFile(conn).findFiles(conn, query = query, type = "directory", directory = directory, limit = limit, dirs = null)
            }
        }
}
