package dev.leonardo.ocbeacon.fakes

import javax.inject.Inject
import dev.leonardo.ocbeacon.domain.model.FileContent
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.ContentType
import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import javax.inject.Singleton

@Singleton
class FakeFileRepository @Inject constructor() : FileRepository {

    var listDirectoryResult: Result<List<FileNode>> = Result.success(emptyList())
    var getFileContentResult: Result<FileContent> = Result.success(
        FileContent(path = "test.txt", type = ContentType.TEXT, content = "")
    )
    var findFilesResult: Result<List<String>> = Result.success(emptyList())
    var listProjectsResult: Result<List<Project>> = Result.success(emptyList())
    var probeDirectoryResult: Result<Boolean> = Result.success(true)
    var getServerPathsResult: Result<ServerPaths> = Result.success(ServerPaths())
    var findDirectoriesResult: Result<List<String>> = Result.success(emptyList())

    override suspend fun listDirectory(
        serverId: String,
        directory: String,
        path: String
    ): Result<List<FileNode>> = listDirectoryResult

    override suspend fun getFileContent(
        serverId: String,
        directory: String,
        path: String
    ): Result<FileContent> = getFileContentResult

    override suspend fun findFiles(
        serverId: String,
        directory: String,
        query: String,
        limit: Int
    ): Result<List<String>> = findFilesResult

    override suspend fun listProjects(serverId: String): Result<List<Project>> = listProjectsResult

    override suspend fun probeDirectory(serverId: String, directory: String): Result<Boolean> =
        probeDirectoryResult

    override suspend fun getServerPaths(serverId: String): Result<ServerPaths> = getServerPathsResult

    override suspend fun findDirectories(
        serverId: String,
        directory: String,
        query: String,
        limit: Int
    ): Result<List<String>> = findDirectoriesResult
}
