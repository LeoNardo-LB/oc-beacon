package dev.leonardo.ocbeacon.domain.model
/** GET /vcs/diff 返回项。与既有 FileDiff(before/after, SSE 用) 不同——本类含 unified diff patch。 */
data class VcsFileDiff(
    val file: String,
    val patch: String?,
    val additions: Int,
    val deletions: Int,
    val status: VcsStatus?
)
