package dev.leonardo.ocbeacon.data.api.message

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageApiCursorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun apiWith(engine: MockEngine): MessageApiImpl {
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val apiClient = ApiClient(httpClient = client, json = json)
        return MessageApiImpl(apiClient)
    }

    private val conn = ServerConnection.from("http://test.local", username = "u", password = "p")

    @Test
    fun listMessages_passesLimitAndBeforeAsQueryParams() = runTest {
        var requestedUrl: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond(
                content = "[]",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = apiWith(engine)
        val cursor = "eyJpZCI6Im1zZ18xIiwidGltZSI6MTIzfQ"

        api.listMessages(conn, "ses_1", limit = 50, before = cursor)

        assertTrue(requestedUrl!!.contains("limit=50"))
        assertTrue(requestedUrl!!.contains("before=$cursor"))
    }

    @Test
    fun listMessages_parsesNextCursorHeader() = runTest {
        val nextCursor = "eyJpZCI6Im1zZ18yIiwidGltZSI6NDU2fQ"
        val engine = MockEngine { request ->
            respond(
                content = "[]",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "X-Next-Cursor" to listOf(nextCursor),
                    "Link" to listOf("<http://test.local/session/ses_1/message?limit=50&before=$nextCursor>; rel=\"next\""),
                ),
            )
        }
        val api = apiWith(engine)

        val page = api.listMessages(conn, "ses_1", limit = 50, before = null)

        assertEquals(nextCursor, page.nextCursor)
        assertTrue(page.messages.isEmpty())
    }

    @Test
    fun listMessages_noNextCursorHeader_returnsNull() = runTest {
        val engine = MockEngine { request ->
            respond(
                content = "[]",
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = apiWith(engine)

        val page = api.listMessages(conn, "ses_1", limit = 50, before = null)

        assertNull(page.nextCursor)
    }
}
