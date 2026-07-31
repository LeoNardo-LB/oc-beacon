package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionApiArchiveTest {

    private val api: SessionApi = mockk()
    private val conn = ServerConnection.from("http://localhost:8080")
    private val baseSession = Session(
        id = "s1",
        title = "Test",
        time = Session.Time(created = 1000L, updated = 1000L)
    )

    @Test
    fun `archiveSession calls updateSessionFields with archived true`() = runTest {
        val archived = baseSession.copy(
            time = baseSession.time.copy(archived = 2000L)
        )
        coEvery { api.updateSessionFields(conn, "s1", mapOf("archived" to true)) } returns archived

        val result = api.updateSessionFields(conn, "s1", mapOf("archived" to true))

        assertEquals(2000L, result.time.archived)
    }

    @Test
    fun `unarchiveSession calls updateSessionFields with archived false`() = runTest {
        coEvery { api.updateSessionFields(conn, "s1", mapOf("archived" to false)) } returns baseSession

        val result = api.updateSessionFields(conn, "s1", mapOf("archived" to false))

        assertEquals(null, result.time.archived)
    }
}
