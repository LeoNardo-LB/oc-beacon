package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.FileContent
import dev.leonardo.octether.domain.model.FileNode
import dev.leonardo.octether.domain.model.ContentType
import dev.leonardo.octether.domain.repository.FileRepository
import javax.inject.Singleton

@Singleton
class FakeFileRepository @Inject constructor() : FileRepository {

    var listDirectoryResult: Result<List<FileNode>> = Result.success(emptyList())
    var getFileContentResult: Result<FileContent> = Result.success(
        FileContent(path = "test.txt", type = ContentType.TEXT, content = "")
    )
    var findFilesResult: Result<List<String>> = Result.success(emptyList())

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
}
