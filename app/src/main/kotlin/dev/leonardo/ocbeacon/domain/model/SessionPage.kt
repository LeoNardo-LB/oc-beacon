package dev.leonardo.ocbeacon.domain.model

/**
 * 会话分页结果——携带服务器权威游标。
 *
 * #273（2026-08-31 修复）：V2 opaque cursor（base64 anchor）曾在 API 层被丢弃，
 * VM 伪造 `sessions.last().id` 当游标 → 服务器 400 InvalidCursorError 被静默解析为
 * 空列表 → hasMorePages 永久关闭。本类型把「条目 + 下一页游标」作为不可分离的
 * 分页语义单元向上传递。
 */
data class SessionPage(
    val items: List<Session>,
    /**
     * 服务器下一页游标；null = 服务器明示无更多页（V2 = cursor.next 缺席）。
     * V1 无游标信封——由客户端按「满页才推导下一页」以 last-id 锚合成（与历史行为一致）。
     */
    val nextCursor: String?
)