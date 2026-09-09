package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchHit
import dev.leonardo.ocbeacon.domain.model.SessionSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #355：检索结果「已归档不展示」——服务器命中区归档剔除。
 */
class SessionSearchArchived355Test {

    private fun hit(sid: String) = dev.leonardo.ocbeacon.domain.model.SessionSearchHit(
        sessionId = sid, snippet = "s",
    )

    private fun local(sid: String) = ContentSearchHit(
        sessionId = sid, messageId = "m", snippet = "s", rank = 1.0, role = "user", created = 1L,
    )

    @Test
    fun `archived sessions removed from server rows`() {
        val server = SessionSearchResult(
            items = listOf(hit("s-live"), hit("s-archived"), hit("s-archived2")),
            hasMore = false,
        )
        val rows = SessionSearchMerge.serverRows(
            server = server,
            localHits = emptyList(),
            archivedIds = setOf("s-archived", "s-archived2"),
        )
        assertEquals(listOf("s-live"), rows.map { it.sessionId })
    }

    @Test
    fun `empty archived set keeps all rows`() {
        val server = SessionSearchResult(
            items = listOf(hit("s-a"), hit("s-b")),
            hasMore = false,
        )
        val rows = SessionSearchMerge.serverRows(server, emptyList(), archivedIds = emptySet())
        assertEquals(listOf("s-a", "s-b"), rows.map { it.sessionId })
    }
}
