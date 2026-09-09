package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchResult

/**
 * #322：服务器命中（session/search）与本地 FTS 命中的呈现合并纯函数。
 *
 * 裁决：服务器区只展示本地 FTS **未覆盖**的会话——本地行携带消息级跳转
 * （[ContentHitNavigation] jumpToMessageId），同会话的服务器行只有会话级
 * 跳转，重复呈现无增量价值；服务器序（相关度）保留；服务器帧内同 sessionId
 * 重复行去重取首行；空白 sessionId 行丢弃（畸形防御——数据层已滤，双保险）。
 */
object SessionSearchMerge {

    /**
     * 服务器区呈现行：本地已覆盖会话剔除 + 保序去重。
     * #355：[archivedIds] 非空时剔除已归档会话（「已归档不展示」裁决三面之一）。
     */
    fun serverRows(
        server: SessionSearchResult?,
        localHits: List<ContentSearchHit>,
        archivedIds: Set<String> = emptySet(),
    ): List<SessionSearchHit> {
        if (server == null) return emptyList()
        val localSessions = localHits.mapTo(mutableSetOf()) { it.sessionId }
        val seen = mutableSetOf<String>()
        return server.items.filter { hit ->
            hit.sessionId.isNotBlank() &&
                hit.sessionId !in localSessions &&
                hit.sessionId !in archivedIds &&
                seen.add(hit.sessionId)
        }
    }
}
