package dev.leonardo.octether.data.repository.handler

import dev.leonardo.octether.domain.model.*
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionEventHandlerTest {

    private lateinit var handler: SessionEventHandler

    @Before
    fun setup() {
        handler = SessionEventHandler()
    }

    private fun testSession(id: String) = Session(
        id = id,
        title = "Test $id",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    @Test
    fun `handles SessionCreated`() = runTest {
        val session = testSession("s1")
        val event = SseEvent.SessionCreated(session)

        val handled = handler.handle(event, "server1")

        assertTrue(handled)
        assertEquals(listOf(session), handler.sessions.value)
    }

    @Test
    fun `handles SessionUpdated - update existing`() = runTest {
        val session = testSession("s1")
        handler.handle(SseEvent.SessionCreated(session), "server1")

        val updated = session.copy(title = "Updated")
        handler.handle(SseEvent.SessionUpdated(updated), "server1")

        assertEquals(listOf(updated), handler.sessions.value)
    }

    @Test
    fun `handles SessionUpdated - upsert new`() = runTest {
        val updated = testSession("s1")
        handler.handle(SseEvent.SessionUpdated(updated), "server1")

        assertEquals(listOf(updated), handler.sessions.value)
    }

    @Test
    fun `handles SessionDeleted`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")

        handler.handle(SseEvent.SessionDeleted(testSession("s1")), "server1")

        assertEquals(1, handler.sessions.value.size)
        assertEquals("s2", handler.sessions.value[0].id)
    }

    @Test
    fun `handles SessionStatus - acknowledged, no local status state`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        // SessionStatus is acknowledged (returns true) but no longer tracked locally —
        // SessionStateService is the single source of truth for status.
        val handled = handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")
        assertTrue(handled)
    }

    @Test
    fun `handles SessionIdle - acknowledged, no local status state`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        val handled = handler.handle(SseEvent.SessionIdle("s1"), "server1")
        assertTrue(handled)
    }

    @Test
    fun `handles SessionDiff`() = runTest {
        val diffs = listOf(FileDiff(file = "test.kt", status = "modified"))
        handler.handle(SseEvent.SessionDiff("s1", diffs), "server1")

        assertEquals(diffs, handler.sessionDiffs.value["s1"])
    }

    @Test
    fun `handles VcsBranchUpdated`() = runTest {
        handler.handle(SseEvent.VcsBranchUpdated("main"), "server1")
        assertEquals("main", handler.vcsBranch.value)
    }

    @Test
    fun `handles ProjectUpdated`() = runTest {
        val project = Project(id = "p1", name = "Test", worktree = "/test")
        handler.handle(SseEvent.ProjectUpdated(project), "server1")
        assertEquals(project, handler.projectInfo.value)
    }

    @Test
    fun `returns false for non-session events`() {
        val handled = handler.handle(SseEvent.MessageUpdated(
            info = Message.User(
                id = "m1", sessionId = "s1",
                time = TimeInfo(created = 1000L)
            )
        ), "server1")
        assertFalse(handled)
    }

    @Test
    fun `clearForServer removes only target server sessions`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server2")

        handler.clearForServer("server1")

        assertEquals(1, handler.sessions.value.size)
        assertEquals("s2", handler.sessions.value[0].id)
    }

    @Test
    fun `clearAll resets everything`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.VcsBranchUpdated("main"), "server1")

        handler.clearAll()

        assertTrue(handler.sessions.value.isEmpty())
        assertNull(handler.vcsBranch.value)
        assertNull(handler.projectInfo.value)
    }

    @Test
    fun `setSessions merges correctly`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        handler.setSessions("server1", listOf(testSession("s1").copy(title = "Updated"), testSession("s2")))

        assertEquals(2, handler.sessions.value.size)
        assertEquals("Updated", handler.sessions.value.find { it.id == "s1" }?.title)
    }

    @Test
    fun `trackSession registers serverId mapping`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")

        val serverSessionMap = handler.serverSessions.value
        assertEquals(setOf("s1", "s2"), serverSessionMap["server1"])
    }

    @Test
    fun `handles ServerHeartbeat`() = runTest {
        assertTrue(handler.handle(SseEvent.ServerHeartbeat, "server1"))
    }

    @Test
    fun `handles ServerConnected`() = runTest {
        assertTrue(handler.handle(SseEvent.ServerConnected, "server1"))
    }

    @Test
    fun `handles SessionCompacted`() = runTest {
        assertTrue(handler.handle(SseEvent.SessionCompacted("s1"), "server1"))
    }

    @Test
    fun `handles SessionError`() = runTest {
        assertTrue(handler.handle(SseEvent.SessionError("s1", "error msg"), "server1"))
    }

    @Test
    fun `clearForServer with no sessions removes server entry`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.clearForServer("server1")

        assertFalse(handler.serverSessions.value.containsKey("server1"))
    }
}
