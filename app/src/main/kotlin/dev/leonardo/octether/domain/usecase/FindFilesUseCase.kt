package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.repository.FileRepository
import javax.inject.Inject

class FindFilesUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String, directory: String, query: String, limit: Int = 50) =
        fileRepository.findFiles(serverId, directory, query, limit)
}
