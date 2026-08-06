package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.FileRepository
import javax.inject.Inject

/**
 * Use Case：在基础目录范围内搜索匹配查询的目录（type=directory）。
 * 委托给 [FileRepository.findDirectories]。
 */
class SearchDirectoriesUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(
        serverId: String,
        directory: String,
        query: String,
        limit: Int = 50
    ): Result<List<String>> =
        fileRepository.findDirectories(serverId, directory, query, limit)
}
