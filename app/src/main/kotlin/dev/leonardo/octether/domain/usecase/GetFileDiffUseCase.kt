package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.VcsDiffMode
import dev.leonardo.octether.domain.repository.VcsRepository
import javax.inject.Inject

/**
 * Use case: get VCS file diff for a directory on a server.
 */
class GetFileDiffUseCase @Inject constructor(
    private val vcsRepository: VcsRepository
) {
    suspend operator fun invoke(
        serverId: String,
        directory: String,
        mode: VcsDiffMode = VcsDiffMode.GIT,
        context: Int = 3
    ) = vcsRepository.getDiff(serverId, directory, mode, context)
}
