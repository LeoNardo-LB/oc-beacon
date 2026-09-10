package dev.leonardo.ocbeacon.data.adapter.opencode

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.reference.ReferenceApi
import dev.leonardo.ocbeacon.domain.model.MentionCandidate
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * OpenCode @ 引用候选端口实现（#391 切片9）。
 *
 * 单域策略：沿用既有 findFiles 现参数形（type=null / dirs=true / limit=15，与
 * DraftInputDelegate.searchFilesForMention 同形），包装 [MentionCandidate.FileMention]
 * ——既有 @ 文件补全零回归；无会话域候选。
 */
class OpenCodeReferencePort(
    private val file: FileApi,
) : ReferenceApi {

    override suspend fun candidates(
        conn: ServerConnection,
        sessionId: String,
        query: String,
        directory: String?,
        quoted: Boolean,
    ): List<MentionCandidate> {
        val paths = file.findFiles(
            conn, query,
            directory = directory,
            limit = 15,
            dirs = "true",
        )
        return paths.map { MentionCandidate.FileMention(it) }
    }
}
