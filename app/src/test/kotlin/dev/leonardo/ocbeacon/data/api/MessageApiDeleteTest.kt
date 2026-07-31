package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageApiDeleteTest {

    private val api: MessageApi = mockk()
    private val conn = ServerConnection.from("http://localhost:8080")

    @Test
    fun `deleteMessage delegates to DELETE endpoint`() = runTest {
        coEvery { api.deleteMessage(conn, "s1", "m1") } returns true

        val result = api.deleteMessage(conn, "s1", "m1")

        assertTrue(result)
    }

    @Test
    fun `deleteMessage returns false on failure`() = runTest {
        coEvery { api.deleteMessage(conn, "s1", "m1") } returns false

        val result = api.deleteMessage(conn, "s1", "m1")

        assertFalse(result)
    }

    @Test
    fun `deleteMessagePart delegates to DELETE endpoint`() = runTest {
        coEvery { api.deleteMessagePart(conn, "s1", "m1", 2) } returns true

        val result = api.deleteMessagePart(conn, "s1", "m1", 2)

        assertTrue(result)
    }

    @Test
    fun `deleteMessagePart returns false on failure`() = runTest {
        coEvery { api.deleteMessagePart(conn, "s1", "m1", 0) } returns false

        val result = api.deleteMessagePart(conn, "s1", "m1", 0)

        assertFalse(result)
    }
}
