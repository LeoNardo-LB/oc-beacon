package dev.leonardo.octether.domain.model
data class VcsChange(val file: String, val additions: Int, val deletions: Int, val status: VcsStatus)
enum class VcsStatus { ADDED, DELETED, MODIFIED }
data class VcsBranchInfo(val branch: String?, val defaultBranch: String?)
enum class VcsDiffMode(val apiValue: String) { GIT("git"), BRANCH("branch") }
