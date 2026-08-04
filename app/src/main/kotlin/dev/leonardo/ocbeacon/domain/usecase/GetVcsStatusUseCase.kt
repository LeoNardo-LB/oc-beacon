package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.VcsRepository
import javax.inject.Inject

/**
 * Use Case：获取服务器上某目录的 VCS 状态。
 */
class GetVcsStatusUseCase @Inject constructor(
    private val vcsRepository: VcsRepository
) {
    suspend operator fun invoke(serverId: String, directory: String) =
        vcsRepository.getStatus(serverId, directory)
}
