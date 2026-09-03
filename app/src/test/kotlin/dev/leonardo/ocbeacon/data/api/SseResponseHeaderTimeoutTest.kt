package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.api.v2.SseClientV2
import dev.leonardo.ocbeacon.data.api.v2.SSE_SOCKET_TIMEOUT_MS as V2_SOCKET_TIMEOUT
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * #305-net 回归锁：SSE「响应头等待」死区（2026-09-03 黑洞 E2E 定罪）。
 *
 * 根因：socketTimeoutMillis=Long.MAX_VALUE 时，网络黑洞/半开隧道下 `execute`
 * 等响应头永久挂起 → 重连协程挂死 → 单飞门（Reconnect already in progress）
 * 被永久占用 → 服务器永不自动重连（真机实证：黑洞挂死 9min+ 零 attempt，
 * 恢复隧道 8min 不重连）。修复=socketTimeout 封顶（缺省 45s，> 流内心跳 40s）。
 *
 * 缝：真 ServerSocket 黑洞（accept 后永不响应）+ 真 Ktor/OkHttp client——
 * 注入小超时值验证机制；缺省值正确性由下方常量断言 + 真机 E2E 覆盖。
 */
class SseResponseHeaderTimeoutTest {

    private val httpClient = HttpClient(OkHttp)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.close()
        httpClient.close()
    }

    /** 起黑洞：accept 连接后持有、永不写任何字节。 */
    private fun blackHoleUrl(): String {
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = s.accept() /* 持有不响应 */
                }
            } catch (_: Exception) {
            }
        }
        return "http://127.0.0.1:${s.localPort}"
    }

    /**
     * 机制锁：注入 socketTimeoutMs=500 → 黑洞下 connectToEvents 必须在超时后
     * 以失败终结（而非挂死到外层 withTimeout——那正是修复前的死区形态）。
     */
    // 真实时钟（runBlocking）：socket 超时是 OkHttp engine 的墙钟行为，
    // runTest 虚拟时间会让外层 withTimeout 瞬时触发（假红）。
    @Test
    fun `v2 sse flow fails after socket timeout when response headers never arrive`() = runBlocking {
        val url = blackHoleUrl()
        val client = SseClientV2(json, httpClient)
        val conn = ServerConnection(baseUrl = url, authHeader = null)

        val outcome = runCatching {
            withTimeout(8_000) {
                client.connectToEvents(conn, socketTimeoutMs = 500).collect { }
            }
        }
        // 挂死形态（修复前）= 外层 TimeoutCancellationException——必须不是它
        assertTrue(
            "flow 应因 socket 超时失败，实际：${outcome.exceptionOrNull()}",
            outcome.isFailure && outcome.exceptionOrNull() !is TimeoutCancellationException,
        )
    }

    @Test
    fun `v1 sse flow fails after socket timeout when response headers never arrive`() = runBlocking {
        val url = blackHoleUrl()
        val client = SseClient(httpClient, json)
        val conn = ServerConnection(baseUrl = url, authHeader = null)

        val outcome = runCatching {
            withTimeout(8_000) {
                client.connectToGlobalEvents(conn, socketTimeoutMs = 500).collect { }
            }
        }
        assertTrue(
            "flow 应因 socket 超时失败，实际：${outcome.exceptionOrNull()}",
            outcome.isFailure && outcome.exceptionOrNull() !is TimeoutCancellationException,
        )
    }

    /** 缺省值防退化：必须 > 流内心跳 40s（否则误杀静默流）、且远小于无限。 */
    @Test
    fun `default socket timeout stays bounded above heartbeat window`() {
        assertTrue(V2_SOCKET_TIMEOUT > 40_000L)
        assertTrue(V2_SOCKET_TIMEOUT <= 120_000L)
        assertTrue(SSE_SOCKET_TIMEOUT_MS > 40_000L)
        assertTrue(SSE_SOCKET_TIMEOUT_MS <= 120_000L)
    }
}
