package dev.leonardo.ocbeacon.data.api.version

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
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
import org.junit.Assert.*
import org.junit.Test

/**
 * ApiVersionDetector 测试——验证版本探测逻辑：
 * V2 服务器 → 检测为 V2
 * V1 服务器 → 检测为 V1
 * 两者均不可达 → UNKNOWN（回退 V1）
 */
class ApiVersionDetectorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildDetector(engine: MockEngine): ApiVersionDetector {
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        return ApiVersionDetector(ApiClient(httpClient, json))
    }

    @Test
    fun `detects V2 when api health responds`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    """{"healthy":true,"version":"2.0.1","pid":{"id":123}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V2, result.version)
        assertEquals("2.0.1", result.serverVersionString)
    }

    @Test
    fun `detects V1 when api health fails but global health works`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond("", HttpStatusCode.NotFound)
                "/global/health" -> respond(
                    """{"healthy":true,"version":"1.2.0"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V1, result.version)
        assertEquals("1.2.0", result.serverVersionString)
    }

    @Test
    fun `falls back to UNKNOWN when both endpoints fail`() = runTest {
        val engine = MockEngine { _ ->
            respond("", HttpStatusCode.InternalServerError)
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://unreachable:9999")
        // 两端探测都失败 → 默认 V1（向后兼容）
        // 注意：detect() 在两者均不可达时返回 V1 作为安全默认值，
        // 而非 UNKNOWN（确保旧服务器仍能工作）
        assertEquals(ApiVersion.V1, result.version)
    }

    @Test
    fun `ApiVersion fromVersionString parses major version`() {
        assertEquals(ApiVersion.V2, ApiVersion.fromVersionString("2.0.1"))
        assertEquals(ApiVersion.V2, ApiVersion.fromVersionString("2.0.0-beta.1"))
        assertEquals(ApiVersion.V1, ApiVersion.fromVersionString("1.2.0"))
        assertEquals(ApiVersion.V1, ApiVersion.fromVersionString("1.15.3"))
        assertEquals(ApiVersion.UNKNOWN, ApiVersion.fromVersionString(null))
        assertEquals(ApiVersion.UNKNOWN, ApiVersion.fromVersionString(""))
        assertEquals(ApiVersion.UNKNOWN, ApiVersion.fromVersionString("abc"))
    }

    @Test
    fun `V2 health response with healthy=false triggers V1 fallback`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    """{"healthy":false,"version":"2.0.0"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                "/global/health" -> respond(
                    """{"healthy":true,"version":"1.0.0"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        // V2 healthy=false → V2 探测失败 → 回退 V1
        assertEquals(ApiVersion.V1, result.version)
    }
}
