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
 * 两者均不可达 → UNKNOWN（非 V1；checkHealth 保留原 apiVersion）
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
        // 2026-08-14 修复（#132 联动）：两端探测都失败 → UNKNOWN（非 V1）。
        // 旧行为默认 V1 会让 checkHealth 把已知 V2 服务器降级为 V1 → 后续
        // V1 路径请求打到 V2 SPA fallback → HTML 解析错误 + SSE 假死。
        // UNKNOWN 语义：healthy=false + checkHealth 保留原 apiVersion。
        assertEquals(ApiVersion.UNKNOWN, result.version)
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

    @Test
    fun `1x server exposing api health responds but version is 1x → detected as V1`() = runTest {
        // 回归测试：opencode 1.18.18 过渡形态同时暴露 /api/health 与 /global/health，
        // /api/health 返回 {"healthy":true,"version":"1.18.18"}。
        // 旧逻辑只看 healthy → 误判 V2 → V2ApiClient 请求不存在的 /api/* 路径 → HTML 崩溃。
        // 新逻辑：版本交叉验证 version=1.18.18 → 不是 2.x → 回退 V1。
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    """{"healthy":true,"version":"1.18.18"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                "/global/health" -> respond(
                    """{"healthy":true,"version":"1.18.18"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V1, result.version)
        assertEquals("1.18.18", result.serverVersionString)
    }

    @Test
    fun `api health without version field → fallback to V1`() = runTest {
        // 实测形态：opencode 1.18.18 的 /api/health 只返回 {"healthy":true}（无 version）。
        // 无版本信息 → 不能判定为 V2 → 回退 V1。
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    """{"healthy":true}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                "/global/health" -> respond(
                    """{"healthy":true,"version":"1.18.18"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V1, result.version)
    }

    @Test
    fun `api health returns HTML page → not V2, fallback to V1`() = runTest {
        // 防御：SPA fallback 返回 text/html 页面（如 <!doctype html>）。
        // content-type 非 JSON → V2 探测失败 → 回退 V1。
        val html = "<!doctype html><html><body>opencode web ui</body></html>"
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    html,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("text/html"))
                )
                "/global/health" -> respond(
                    """{"healthy":true,"version":"1.18.18"}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V1, result.version)
    }

    @Test
    fun `api health returns 200 JSON but body is not parseable → fallback to V1`() = runTest {
        // 防御：即使 content-type 是 JSON，body 解析失败也不崩溃 → 回退 V1。
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    "not-json-at-all",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
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
    }

    @Test
    fun `V2 prerelease with 0 0 0-next version and pid field → detected as V2`() = runTest {
        // 回归：真实 V2 服务器版本号是 "0.0.0-next-17403"（npm next 预发布），
        // major=0 解析不出 2.x——必须靠 pid 字段识别 V2，否则真 V2 被误判 V1。
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    """{"healthy":true,"version":"0.0.0-next-17403","pid":51955}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V2, result.version)
        assertEquals("0.0.0-next-17403", result.serverVersionString)
    }

    @Test
    fun `V2 without version but with pid field → detected as V2`() = runTest {
        // 兼容：V2 响应即使缺少 version 字段，pid 特征也能识别。
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/health" -> respond(
                    """{"healthy":true,"pid":{"id":123}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"))
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val detector = buildDetector(engine)
        val result = detector.detect("http://localhost:4096")
        assertEquals(ApiVersion.V2, result.version)
    }
}
