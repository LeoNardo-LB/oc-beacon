package dev.leonardo.ocbeacon.domain.model

data class CreateSessionOpts(
    val title: String? = null,
    val parentId: String? = null,
    val directory: String? = null,
    /** #311 ①-d：DSH V012 SessionCreateRequest.workspaceId（指定入组 workspace；
     * web connectWorkspace 同款——缺席走服务器 cwd 归属）。其余后端忽略。 */
    val workspaceId: String? = null,
    /** #354：DSH V012 SessionCreateRequest.agentPreset——创建即带预设（会话生而
     * 有之，create 回显 agentPreset → Session.agentPreset 直接入槽）。根治
     * create-then-select 竞态（session.list 基线不回带该字段，事件可竞丢）。 */
    val agentPreset: String? = null,
)
