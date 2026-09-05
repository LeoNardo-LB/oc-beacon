package dev.leonardo.ocbeacon.domain.model

/**
 * DSH 服务端内容搜索单会话命中行（session/search，backlog #322）。
 *
 * wire 形状（dsh-api-session-controller types.d.ts SessionSearchItem zod schema）：
 * `{"sessionId":…,"snippet":…}`——snippet ≤240 Unicode 码点（服务器侧
 * SESSION_SEARCH_SNIPPET_MAX_CODE_POINTS 保证，客户端防御性同限截断）。
 *
 * 呈现裁决：服务器命中**无消息级锚点**（wire 只有 sessionId+snippet，无
 * messageId）——UI 只做「会话行 + snippet」呈现、tap 进会话，不做
 * jumpToMessageId 消息级跳转（与本地 FTS 命中 [dev.leonardo.ocbeacon.data.local.ContentSearchHit]
 * 的分工：服务器=全历史广度、本地已加载=消息级深度）。
 */
data class SessionSearchHit(
    val sessionId: String,
    val snippet: String,
)

/**
 * session/search 整帧（#322）：items + hasMore。
 *
 * 上限 20 会话/次（服务器 SESSION_SEARCH_RESULT_LIMIT）；[hasMore]=true 表示
 * 服务器还有更多命中被上限截断——UI 呈现截断提示，不支持翻页（wire 无游标）。
 */
data class SessionSearchResult(
    val items: List<SessionSearchHit>,
    val hasMore: Boolean,
)
