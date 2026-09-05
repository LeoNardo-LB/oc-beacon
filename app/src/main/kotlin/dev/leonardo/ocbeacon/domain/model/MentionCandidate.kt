package dev.leonardo.ocbeacon.domain.model

/**
 * #310⑤/#321 @ 引用候选领域模型（DSH fileReferences/sessionReferenceResolver 域）。
 *
 * wire 契约钉死 2026-09-05（docs/research/2026-09-05-310-wire-contracts.md §⑤，
 * 服务端 dsh-session-reference/dsh-file-reference types.d.ts 取证）：
 * - 'fileReferences/list' (agentId,query) → FileReferenceCandidate[] [{path,kind}]（#321 落点）
 * - 'sessionReferenceResolver/candidates' (agentId,query) → SessionReferenceMentionCandidate[]
 *   [{sessionId,label,cwd?,sameWorkspace,createdAt,mention}]
 * - web mod34:113-114 并行合并两域（quoted===true 跳过会话候选）。
 *
 * [SessionMention.mention] 是服务器权威规范串 `@[label](dsh-session:…)`——序列化
 * 进 prompt 草稿用，客户端不重组；[createdAt] 现阶段丢弃（合并排序按 sameWorkspace）。
 */
sealed interface MentionCandidate {

    /** 文件/目录候选（#321）：[path] 为用户可见路径（prompt 与文件工具同形）。 */
    data class FileMention(val path: String) : MentionCandidate

    /** 会话候选（#310⑤）：cwd 亲和由服务器判定（[sameWorkspace]），本端只读。 */
    data class SessionMention(
        val sessionId: String,
        val label: String,
        val cwd: String? = null,
        val sameWorkspace: Boolean,
        /** 规范 `@[label](dsh-session:…)` 串——发送草稿时原样嵌入。 */
        val mention: String,
    ) : MentionCandidate
}

/**
 * 纯合并（web mod34:113-114 先例，单测 MentionCandidateTest 钉死）：
 * - quoted=true（`@"` 引号形态）只文件候选（引号内只搜文件，无会话）；
 * - 否则会话在前、[MentionCandidate.SessionMention.sameWorkspace] 优先
 *  （稳定排序——组内保持服务器 cwd 亲和序），文件随后保序。
 */
fun mergeMentionCandidates(
    files: List<MentionCandidate.FileMention>,
    sessions: List<MentionCandidate.SessionMention>,
    quoted: Boolean,
): List<MentionCandidate> = if (quoted) {
    files.toList()
} else {
    sessions.sortedByDescending { it.sameWorkspace } + files
}
