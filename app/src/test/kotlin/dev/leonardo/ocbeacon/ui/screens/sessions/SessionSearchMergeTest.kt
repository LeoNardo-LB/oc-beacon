package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #322：服务器命中 ↔ 本地 FTS 命中合并纯函数测试。
 *
 * 呈现裁决：服务器区只展示本地 FTS 未覆盖的会话——本地行携带消息级跳转
 * （ContentHitNavigation），同会话重复行没有增量价值；服务器序（相关度）保留。
 */
class SessionSearchMergeTest {

    private fun localHit(sessionId: String) = ContentSearchHit(
        sessionId = sessionId,
        messageId = "msg-" + sessionId,
        role = "user",
        snippet = "local",
        created = 0L,
        rank = null,
    )

    private fun serverHit(sessionId: String, snippet: String = "server-" + sessionId) =
        SessionSearchHit(sessionId = sessionId, snippet = snippet)

    @Test
    fun `null server result yields empty rows`() {
        assertEquals(emptyList<SessionSearchHit>(), SessionSearchMerge.serverRows(null, listOf(localHit("s-1"))))
    }

    @Test
    fun `rows covered by local hits are dropped`() {
        val server = SessionSearchResult(
            items = listOf(serverHit("s-1"), serverHit("s-2"), serverHit("s-3")),
            hasMore = false,
        )
        val rows = SessionSearchMerge.serverRows(server, listOf(localHit("s-2"), localHit("s-3")))
        assertEquals(listOf("s-1"), rows.map { it.sessionId })
    }

    @Test
    fun `server order preserved and duplicate session ids deduped to first`() {
        val server = SessionSearchResult(
            items = listOf(
                serverHit("s-1", "first"),
                serverHit("s-2", "second"),
                serverHit("s-1", "dupe-later"),
            ),
            hasMore = false,
        )
        val rows = SessionSearchMerge.serverRows(server, emptyList())
        assertEquals(listOf("s-1", "s-2"), rows.map { it.sessionId })
        assertEquals("first", rows[0].snippet)
    }

    @Test
    fun `blank session id rows are dropped`() {
        val server = SessionSearchResult(
            items = listOf(serverHit(" "), serverHit("s-1")),
            hasMore = false,
        )
        val rows = SessionSearchMerge.serverRows(server, emptyList())
        assertEquals(listOf("s-1"), rows.map { it.sessionId })
    }
}
