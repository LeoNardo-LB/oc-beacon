package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.mapper.FileMapper
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val api: FileApi,
    private val serverRepository: ServerRepository
) : FileRepository {

    override suspend fun listDirectory(serverId: String, directory: String, path: String): Result<List<FileNode>> =
        runCatching {
            val conn = serverRepository.resolveConnection(serverId)
            api.listDirectory(conn, path, directory).map { FileMapper.toDomain(it) }
        }

    override suspend fun getFileContent(serverId: String, directory: String, path: String): Result<FileContent> =
        runCatching {
            val conn = serverRepository.resolveConnection(serverId)
            FileMapper.toDomain(api.readFile(conn, path, directory), path)
        }

    override suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int): Result<List<String>> =
        runCatching {
            val conn = serverRepository.resolveConnection(serverId)
            api.findFiles(conn, query = query, type = "file", directory = directory, limit = limit, dirs = null)
        }
}
