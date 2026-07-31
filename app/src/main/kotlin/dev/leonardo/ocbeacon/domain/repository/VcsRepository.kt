package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.VcsBranchInfo
import dev.leonardo.ocbeacon.domain.model.VcsChange
import dev.leonardo.ocbeacon.domain.model.VcsDiffMode
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff

interface VcsRepository {
    suspend fun getBranch(serverId: String, directory: String): Result<VcsBranchInfo>
    suspend fun getStatus(serverId: String, directory: String): Result<List<VcsChange>>
    suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int = 3): Result<List<VcsFileDiff>>
}
