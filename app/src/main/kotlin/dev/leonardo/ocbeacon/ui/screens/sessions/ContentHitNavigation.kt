package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchHit
import dev.leonardo.ocbeacon.ui.navigation.routes.ChatNav

/**
 * 内容检索命中 → 跳转定位导航（2026-09-01 B1 链）：
 * 命中组（同会话多条）取最优一条（FTS bm25 rank 升序 = 最相关；LIKE 降级路径按时间序首条 = 最新），
 * 沿 ChatNav jumpToMessageId 参数线程传递，Chat 打开即定位到该消息。
 */
object ContentHitNavigation {

    /** 命中组的跳转目标（sessionId, messageId）；空命中返回 null。 */
    fun jumpTarget(hits: List<ContentSearchHit>): Pair<String, String>? =
        hits.minByOrNull { it.rank ?: Double.MAX_VALUE }?.let { it.sessionId to it.messageId }

    /** 内容检索命中 → Chat 路由（携带 jumpToMessageId，锁定目标会话）。 */
    fun chatRoute(serverId: String, sessionId: String, messageId: String?): String =
        ChatNav.createRoute(serverId, sessionId, jumpToMessageId = messageId)
}