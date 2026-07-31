package dev.leonardo.octether.data.api

import dev.leonardo.octether.data.api.session.SessionApi
import dev.leonardo.octether.domain.model.ServerConnection
import dev.leonardo.octether.domain.model.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionApiImportTest {

    private val api: SessionApi = mockk()
    private val conn = ServerConnection.from("http://localhost:8080")
    private val importedSession = Session(
        id = "imported-1",
        title = "Imported Session",
        time = Session.Time(created = 2000L, updated = 2000L)
    )

    @Test
    fun `importSession delegates to api and returns Session`() = runTest {
        coEvery { api.importSession(conn, "https://share.example.com/s/abc123") } returns importedSession

        val result = api.importSession(conn, "https://share.example.com/s/abc123")

        assertEquals("imported-1", result.id)
        assertEquals("Imported Session", result.title)
    }

    @Test
    fun `importSession handles different share URLs`() = runTest {
        val session2 = Session(
            id = "imported-2",
            title = "Another Import",
            time = Session.Time(created = 3000L, updated = 3000L)
        )
        coEvery { api.importSession(conn, "https://oc.app/share/xyz") } returns session2

        val result = api.importSession(conn, "https://oc.app/share/xyz")

        assertEquals("imported-2", result.id)
    }
}
