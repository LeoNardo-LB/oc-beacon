package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.repository.FileRepository
import javax.inject.Inject

/**
 * Use Case：探测目录是否存在且可访问。
 * 委托给 [FileRepository.probeDirectory]。
 */
class ProbeDirectoryUseCase @Inject constructor(
    private val fileRepository: FileRepository
) {
    suspend operator fun invoke(serverId: String, directory: String): Result<Boolean> =
        fileRepository.probeDirectory(serverId, directory)
}
