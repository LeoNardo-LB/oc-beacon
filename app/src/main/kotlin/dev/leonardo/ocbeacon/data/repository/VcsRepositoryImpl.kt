package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.mapper.VcsMapper
import dev.leonardo.ocbeacon.domain.model.VcsDiffMode
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.VcsRepository
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

@Singleton
class VcsRepositoryImpl @Inject constructor(
    private val api: FileApi,
    private val serverRepository: ServerRepository
) : VcsRepository {

    override suspend fun getBranch(serverId: String, directory: String) = runCatchingCancellable {
        val conn = serverRepository.resolveConnection(serverId)
        VcsMapper.toDomain(api.getVcs(conn, directory))
    }

    override suspend fun getStatus(serverId: String, directory: String) = runCatchingCancellable {
        val conn = serverRepository.resolveConnection(serverId)
        api.getVcsStatus(conn, directory).map { VcsMapper.toDomain(it) }
    }

    override suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int) = runCatchingCancellable {
        val conn = serverRepository.resolveConnection(serverId)
        api.getVcsDiff(conn, mode.apiValue, context, directory).map { VcsMapper.toDomain(it) }
    }
}
