package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import javax.inject.Inject

/**
 * Use Case：获取服务器路径信息（home/worktree 等）。
 * 委托给 [FileRepository.getServerPaths]。
 */
class GetServerPathsUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String): Result<ServerPaths> =
        fileRepository.getServerPaths(serverId)
}
