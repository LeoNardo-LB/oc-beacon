package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ToolRef
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PermissionEventHandlerTest {

    private lateinit var handler: PermissionEventHandler

    @Before
    fun setup() {
        handler = PermissionEventHandler()
    }

    private fun testPermission(id: String, sessionId: String) = SseEvent.PermissionAsked(
        id = id, sessionId = sessionId, permission = "bash"
    )

    @Test
    fun `handles PermissionAsked`() {
        val perm = testPermission("p1", "s1")
        assertTrue(handler.handle(perm, "server1"))
        assertEquals(listOf(perm), handler.permissions.value["s1"])
    }

    @Test
    fun `handles PermissionReplied`() {
        handler.handle(testPermission("p1", "s1"), "server1")
        handler.handle(testPermission("p2", "s1"), "server1")

        handler.handle(SseEvent.PermissionReplied(sessionId = "s1", requestId = "p1"), "server1")

        assertEquals(1, handler.permissions.value["s1"]!!.size)
        assertEquals("p2", handler.permissions.value["s1"]!![0].id)
    }

    @Test
    fun `removePermission removes across all sessions`() {
        handler.handle(testPermission("target", "s1"), "server1")
        handler.handle(testPermission("other", "s1"), "server1")
        handler.handle(testPermission("target", "s2"), "server1")

        handler.removePermission("target")

        assertEquals(1, handler.permissions.value["s1"]!!.size)
        assertEquals("other", handler.permissions.value["s1"]!![0].id)
        assertTrue(handler.permissions.value["s2"]!!.isEmpty())
    }

    @Test
    fun `setPermissions replaces existing`() {
        handler.handle(testPermission("old", "s1"), "server1")
        val newPerm = testPermission("new", "s1")

        handler.setPermissions("s1", listOf(newPerm))

        assertEquals(listOf(newPerm), handler.permissions.value["s1"])
    }

    @Test
    fun `setPermissions with empty list removes session entry`() {
        handler.handle(testPermission("p1", "s1"), "server1")
        handler.setPermissions("s1", emptyList())
        assertFalse(handler.permissions.value.containsKey("s1"))
    }

    @Test
    fun `returns false for non-permission events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
    }

    @Test
    fun `clearForSession removes permissions for single session`() {
        handler.handle(testPermission("p1", "s1"), "server1")
        handler.handle(testPermission("p2", "s2"), "server1")

        handler.clearForSession("s1")

        assertFalse(handler.permissions.value.containsKey("s1"))
        assertTrue(handler.permissions.value.containsKey("s2"))
    }

    @Test
    fun `clearForServer removes permissions for session set`() {
        handler.handle(testPermission("p1", "s1"), "server1")
        handler.handle(testPermission("p2", "s2"), "server1")

        handler.clearForServer(setOf("s1"))

        assertFalse(handler.permissions.value.containsKey("s1"))
        assertTrue(handler.permissions.value.containsKey("s2"))
    }

    @Test
    fun `clearAll resets everything`() {
        handler.handle(testPermission("p1", "s1"), "server1")
        handler.clearAll()
        assertTrue(handler.permissions.value.isEmpty())
    }
}
