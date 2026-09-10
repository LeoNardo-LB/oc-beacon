package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.reference.ReferenceApi
import dev.leonardo.ocbeacon.domain.model.MentionCandidate
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.mergeMentionCandidates
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * DSH @ 引用候选端口实现（#391 切片9）——并行两域（fileReferences/list +
 * sessionReferenceResolver/candidates）后纯合并 [mergeMentionCandidates]。
 *
 * quoted 时不查会话域（省一次 RPC）；任一域失败整体上抛，由仓储层 Result 收编。
 */
class DshReferencePort(
    private val dsh: DshApiClient,
) : ReferenceApi {

    override suspend fun candidates(
        conn: ServerConnection,
        sessionId: String,
        query: String,
        directory: String?,
        quoted: Boolean,
    ): List<MentionCandidate> = coroutineScope {
        val files = async { dsh.fileReferencesList(conn, sessionId, query) }
        val sessions = async {
            if (quoted) emptyList<MentionCandidate.SessionMention>()
            else dsh.sessionReferenceCandidates(conn, sessionId, query)
        }
        mergeMentionCandidates(
            files.await().map { MentionCandidate.FileMention(it) },
            sessions.await(),
            quoted,
        )
    }
}
