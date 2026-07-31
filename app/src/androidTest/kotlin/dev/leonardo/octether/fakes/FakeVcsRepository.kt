package dev.leonardo.octether.fakes

import javax.inject.Inject
import dev.leonardo.octether.domain.model.VcsBranchInfo
import dev.leonardo.octether.domain.model.VcsChange
import dev.leonardo.octether.domain.model.VcsDiffMode
import dev.leonardo.octether.domain.model.VcsFileDiff
import dev.leonardo.octether.domain.repository.VcsRepository
import javax.inject.Singleton

@Singleton
class FakeVcsRepository @Inject constructor() : VcsRepository {

    var getBranchResult: Result<VcsBranchInfo> = Result.success(VcsBranchInfo(branch = "main", defaultBranch = "main"))
    var getStatusResult: Result<List<VcsChange>> = Result.success(emptyList())
    var getDiffResult: Result<List<VcsFileDiff>> = Result.success(emptyList())

    override suspend fun getBranch(serverId: String, directory: String): Result<VcsBranchInfo> = getBranchResult

    override suspend fun getStatus(serverId: String, directory: String): Result<List<VcsChange>> = getStatusResult

    override suspend fun getDiff(
        serverId: String,
        directory: String,
        mode: VcsDiffMode,
        context: Int
    ): Result<List<VcsFileDiff>> = getDiffResult
}
