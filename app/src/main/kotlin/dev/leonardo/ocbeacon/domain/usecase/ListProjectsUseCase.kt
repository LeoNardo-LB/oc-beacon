package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.Project
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import javax.inject.Inject

/**
 * Use Case：列出服务器上的项目（worktree）。
 * 委托给 [FileRepository.listProjects]。
 */
class ListProjectsUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String): Result<List<Project>> =
        fileRepository.listProjects(serverId)
}
