package dev.leonardo.ocbeacon.ui.navigation.routes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNavTest {

    @Test
    fun `route pattern includes jump to message id arg`() {
        assertTrue(ChatNav.routePattern.contains("jumpToMessageId={jumpToMessageId}"))
    }

    @Test
    fun `create route with jump to message id appends encoded param`() {
        val route = ChatNav.createRoute(
            serverId = "srv-1",
            sessionId = "s-1",
            jumpToMessageId = "msg-42",
        )
        assertEquals("chat?serverId=srv-1&sessionId=s-1&openTerminal=false&directory=&jumpToMessageId=msg-42", route)
    }

    @Test
    fun `create route without jump omits the param entirely`() {
        val route = ChatNav.createRoute(serverId = "srv-1", sessionId = "s-1")
        assertTrue("jumpToMessageId" !in route)
    }
}