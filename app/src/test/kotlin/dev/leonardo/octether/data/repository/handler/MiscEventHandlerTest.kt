package dev.leonardo.octether.data.repository.handler

import dev.leonardo.octether.domain.model.Message
import dev.leonardo.octether.domain.model.SseEvent
import dev.leonardo.octether.domain.model.TimeInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MiscEventHandlerTest {

    private lateinit var handler: MiscEventHandler

    @Before
    fun setup() {
        handler = MiscEventHandler()
    }

    @Test
    fun `handles TodoUpdated`() {
        val todos = listOf(SseEvent.TodoUpdated.Todo("Task 1", "pending", "high"))
        assertTrue(handler.handle(SseEvent.TodoUpdated("s1", todos), "server1"))
        assertEquals(todos, handler.todos.value["s1"])
    }

    @Test
    fun `handles PtyCreated`() {
        assertTrue(handler.handle(SseEvent.PtyCreated(id = "pty_1"), "server1"))
    }

    @Test
    fun `handles CommandExecuted`() {
        assertTrue(handler.handle(
            SseEvent.CommandExecuted(name = "build", sessionId = "s1"), "server1"
        ))
    }

    @Test
    fun `handles LspUpdated`() {
        assertTrue(handler.handle(SseEvent.LspUpdated, "server1"))
    }

    @Test
    fun `returns false for unhandled events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
        assertFalse(handler.handle(SseEvent.MessageUpdated(
            info = Message.User(
                id = "m1", sessionId = "s1",
                time = TimeInfo(created = 1000L)
            )
        ), "server1"))
    }

    @Test
    fun `clearForSession removes todos`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")

        handler.clearForSession("s1")

        assertNull(handler.todos.value["s1"])
    }

    @Test
    fun `clearForServer removes todos for session set`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")
        handler.handle(SseEvent.TodoUpdated("s2", listOf(
            SseEvent.TodoUpdated.Todo("Task 2", "done", "low")
        )), "server1")

        handler.clearForServer(setOf("s1"))

        assertNull(handler.todos.value["s1"])
        assertNotNull(handler.todos.value["s2"])
    }

    @Test
    fun `clearAll resets todos`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")

        handler.clearAll()

        assertTrue(handler.todos.value.isEmpty())
    }
}
