package dev.leonardo.ocbeacon.domain.model

data class CreateSessionOpts(
    val title: String? = null,
    val parentId: String? = null,
    val directory: String? = null,
    /** #311 ①-d：DSH V012 SessionCreateRequest.workspaceId（指定入组 workspace；
     * web connectWorkspace 同款——缺席走服务器 cwd 归属）。其余后端忽略。 */
    val workspaceId: String? = null,
)
