package dev.leonardo.octether.domain.repository

import dev.leonardo.octether.domain.model.VcsBranchInfo
import dev.leonardo.octether.domain.model.VcsChange
import dev.leonardo.octether.domain.model.VcsDiffMode
import dev.leonardo.octether.domain.model.VcsFileDiff

interface VcsRepository {
    suspend fun getBranch(serverId: String, directory: String): Result<VcsBranchInfo>
    suspend fun getStatus(serverId: String, directory: String): Result<List<VcsChange>>
    suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int = 3): Result<List<VcsFileDiff>>
}
