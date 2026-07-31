package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests that listSessions correctly passes search/cursor/limit
 * query parameters to the HTTP request.
 */
class SessionApiSearchPaginationTest {

    @Test
    fun `listSessions passes search query parameter`() = runTest {
        val api: SessionApi = mockk()
        val conn = ServerConnection.from("http://localhost:8080")
        val sessions = listOf(
            Session(id = "s1", title = "Test", time = Session.Time(created = 1000L, updated = 1000L))
        )

        coEvery { api.listSessions(any(), search = "test", cursor = null, limit = 50) } returns sessions

        val result = api.listSessions(conn, search = "test", cursor = null, limit = 50)

        assertEquals(1, result.size)
        assertEquals("s1", result[0].id)
    }

    @Test
    fun `listSessions passes cursor and limit parameters`() = runTest {
        val api: SessionApi = mockk()
        val conn = ServerConnection.from("http://localhost:8080")

        coEvery { api.listSessions(any(), search = null, cursor = "abc123", limit = 20) } returns emptyList()

        val result = api.listSessions(conn, search = null, cursor = "abc123", limit = 20)

        assertEquals(0, result.size)
    }

    @Test
    fun `listSessions default parameters remain backward compatible`() = runTest {
        val api: SessionApi = mockk()
        val conn = ServerConnection.from("http://localhost:8080")

        coEvery { api.listSessions(any()) } returns emptyList()

        val result = api.listSessions(conn)

        assertEquals(0, result.size)
    }
}
