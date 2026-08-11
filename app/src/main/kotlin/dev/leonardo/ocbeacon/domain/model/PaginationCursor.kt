package dev.leonardo.ocbeacon.domain.model

/**
 * 翻页游标 —— 决定下一次 loadOlderMessages 从哪个边界继续读。
 *
 * 收敛 MessagePaginationDelegate 曾散落的三个游标字段
 *（archiveCursorCreated / networkCursorId / networkCursorCreated，#56 TD-1）。
 *
 * 语义（与重构前逐条对齐）：
 * - [HotStart]：无游标 —— 从热表最老边界开始。进入会话时重置、
 *   网络读尽后回落（use case 内部回落到热表最老）。
 * - [Archive]：归档时间游标 —— 继续读更早的归档桶。归档读取不落热表
 *   → 热表最老不变；若始终用热表最老作 before 会读到同一批归档桶（死循环）。
 *   此游标持久化"已显示到哪"，使下次翻页能继续读更早的归档。
 * - [Network]：网络分页游标（ID + created）—— 归档已读尽后直接走网络。
 *   需要 ID：use case 的网络 before 编码 = CursorCodec.encode(id, created)，
 *   游标消息不在热表（窗口外不落库），必须记住 ID 才能编码。
 */
sealed class PaginationCursor {
    /** 无游标：热表最老边界（首次翻页 / 会话重载 / 网络读尽回落）。 */
    data object HotStart : PaginationCursor()

    /** 归档时间游标：继续读更早归档（beforeCreated 参数）。 */
    data class Archive(val created: Long) : PaginationCursor()

    /** 网络游标：归档已读尽，跳过归档直接网络（beforeId + beforeCreated 编码）。 */
    data class Network(val id: String, val created: Long) : PaginationCursor()
}
