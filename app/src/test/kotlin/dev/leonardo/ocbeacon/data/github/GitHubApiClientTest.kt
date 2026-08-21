package dev.leonardo.ocbeacon.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #151 测试缝 2：GitHub API 客户端（Ktor MockEngine）——三端点请求形状与错误映射。 */
class GitHubApiClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun okClient(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        GitHubApiClient(HttpClient(MockEngine { respond(body, status) }), json)

    @Test
    fun `search parses hit from items`() = runTest {
        val body = "{\"total_count\":1,\"items\":[{\"number\":7,\"title\":\"boom\",\"body\":\"b\"}]}"
        val api = okClient(body)
        val hit = api.searchIssueByFingerprint("t", "fp").getOrThrow()
        assertEquals(7, hit!!.number)
    }

    @Test
    fun `search zero results returns null`() = runTest {
        val api = okClient("{\"total_count\":0,\"items\":[]}")
        assertEquals(null, api.searchIssueByFingerprint("t", "fp").getOrThrow())
    }

    @Test
    fun `401 maps to Unauthorized`() = runTest {
        val api = okClient("{}", HttpStatusCode.Unauthorized)
        val err = api.searchIssueByFingerprint("t", "fp").exceptionOrNull()
        assertTrue(err is GitHubApiError.Unauthorized)
    }

    @Test
    fun `403 maps to RateLimited`() = runTest {
        val api = okClient("{}", HttpStatusCode.Forbidden)
        val err = api.createIssue("t", "title", "body", emptyList()).exceptionOrNull()
        assertTrue(err is GitHubApiError.RateLimited)
    }

    @Test
    fun `createIssue parses number`() = runTest {
        val api = okClient("{\"number\":42}", HttpStatusCode.Created)
        assertEquals(42, api.createIssue("t", "ti", "bo", listOf("needs-triage")).getOrThrow())
    }

    @Test
    fun `addComment success on 201`() = runTest {
        val api = okClient("{}", HttpStatusCode.Created)
        assertTrue(api.addComment("t", 7, "c").isSuccess)
    }
}
