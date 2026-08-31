package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.data.local.ContentSearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentHitNavigationTest {

    private fun hit(sessionId: String, messageId: String, rank: Double?) =
        ContentSearchHit(sessionId = sessionId, messageId = messageId, role = "assistant", snippet = "…x…", created = 1L, rank = rank)

    @Test
    fun `jump target picks lowest bm25 rank as most relevant hit`() {
        val target = ContentHitNavigation.jumpTarget(
            listOf(hit("s1", "m-3", 3.0), hit("s1", "m-1", 1.0), hit("s1", "m-2", 2.0)),
        )
        assertEquals("s1" to "m-1", target)
    }

    @Test
    fun `jump target falls back to first hit when ranks are null`() {
        val target = ContentHitNavigation.jumpTarget(
            listOf(hit("s1", "m-a", null), hit("s1", "m-b", null)),
        )
        assertEquals("s1" to "m-a", target)
    }

    @Test
    fun `jump target is null for empty hits`() {
        assertNull(ContentHitNavigation.jumpTarget(emptyList()))
    }

    @Test
    fun `chat route carries jump to message id`() {
        val route = ContentHitNavigation.chatRoute(serverId = "srv-1", sessionId = "s-1", messageId = "msg-42")
        assertEquals("chat?serverId=srv-1&sessionId=s-1&openTerminal=false&directory=&jumpToMessageId=msg-42", route)
    }

    @Test
    fun `chat route without message id omits jump param`() {
        val route = ContentHitNavigation.chatRoute(serverId = "srv-1", sessionId = "s-1", messageId = null)
        assertEquals("chat?serverId=srv-1&sessionId=s-1&openTerminal=false&directory=", route)
    }
}