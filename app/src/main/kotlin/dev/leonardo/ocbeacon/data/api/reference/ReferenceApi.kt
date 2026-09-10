package dev.leonardo.ocbeacon.data.api.reference

import dev.leonardo.ocbeacon.domain.model.MentionCandidate
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * @ 引用候选解析端口（#391 切片9）。
 *
 * 端口在场 = 该类型可提供引用候选；取数策略（DSH 两域 RPC + 纯合并 / OpenCode
 * findFiles 单域包装）封装在端口实现内，调用方只取合并后的候选列表，不做服务器
 * 类型判断。端口命名不暗示实现来源。
 */
interface ReferenceApi {
    suspend fun candidates(
        conn: ServerConnection,
        sessionId: String,
        query: String,
        directory: String?,
        quoted: Boolean,
    ): List<MentionCandidate>
}
