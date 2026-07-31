package dev.leonardo.octether.data.api

import dev.leonardo.octether.data.api.file.FileApi
import dev.leonardo.octether.data.api.file.FileApiImpl
import dev.leonardo.octether.data.dto.response.FileDiffDto
import dev.leonardo.octether.data.dto.response.VcsBranchDto
import dev.leonardo.octether.data.dto.response.VcsChangeDto
import dev.leonardo.octether.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileApiVcsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildApi(engine: MockEngine): FileApi {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return FileApiImpl(ApiClient(httpClient, json))
    }

    private val conn = ServerConnection.from(
        "http://localhost:4096", "opencode", "secret"
    )

    @Test
    fun `getVcsStatus parses 3 changes - added, modified, deleted`() = runTest {
        val responseBody = """[
            {"file":"app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt","additions":12,"deletions":3,"status":"modified"},
            {"file":"README.md","additions":5,"deletions":0,"status":"added"},
            {"file":"old/Unused.kt","additions":0,"deletions":20,"status":"deleted"}
        ]"""

        val engine = MockEngine { request ->
            assertEquals("/vcs/status", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }
        val api = buildApi(engine)

        val result = api.getVcsStatus(conn)
        assertEquals(3, result.size)
        assertEquals("modified", result[0].status)
        assertEquals("OpenCodeApi.kt", result[0].file.substringAfterLast("/"))
        assertEquals(12, result[0].additions)
        assertEquals(3, result[0].deletions)
        assertEquals("added", result[1].status)
        assertEquals(5, result[1].additions)
        assertEquals(0, result[1].deletions)
        assertEquals("deleted", result[2].status)
        assertEquals(0, result[2].additions)
        assertEquals(20, result[2].deletions)
    }

    @Test
    fun `getVcsDiff passes mode and context params, directory header`() = runTest {
        val responseBody = """[
            {"file":"app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt","patch":"@@ -1,3 +1,4 @@\n+import kotlinx.serialization.SerialName","additions":1,"deletions":0,"status":"modified"}
        ]"""

        val engine = MockEngine { request ->
            assertEquals("/vcs/diff", request.url.encodedPath)
            assertEquals("git", request.url.parameters["mode"])
            assertEquals("3", request.url.parameters["context"])
            assertTrue(
                "Directory header should contain workspace path",
                request.headers["x-opencode-directory"] == "D%3A%5CDevelop%5Cproject"
            )
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }
        val api = buildApi(engine)

        val result = api.getVcsDiff(conn, mode = "git", context = 3, directory = "D:\\Develop\\project")
        assertEquals(1, result.size)
        assertEquals("modified", result[0].status)
        assertTrue(result[0].patch!!.contains("SerialName"))
        assertEquals(1, result[0].additions)
        assertEquals(0, result[0].deletions)
    }

    @Test
    fun `getVcs parses branch with default_branch via SerialName`() = runTest {
        val responseBody = """{"branch":"feat/workspace","default_branch":"master"}"""

        val engine = MockEngine { request ->
            assertEquals("/vcs", request.url.encodedPath)
            respond(responseBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"))
            )
        }
        val api = buildApi(engine)

        val result = api.getVcs(conn)
        assertEquals("feat/workspace", result.branch)
        assertEquals("master", result.defaultBranch)
    }
}
