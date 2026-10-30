package dev.leonardo.ocbeacon.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * #569 发送链超时机制测试。
 *
 * 三个钉子：
 * 1. 配置钉：[sendChainTimeout] 把 30s/10s/30s 写进 per-request capability；
 * 2. 行为钉（真 OkHttp engine + 本地冻结服务器）：非 SSE 请求的 requestTimeout
 *    由插件层 killer 实施（OkHttp engine 不映射 requestTimeoutMillis，但插件
 *    到点取消请求执行上下文）——冻结服务器（accept 后零响应，SIGSTOP 等价）
 *    下必须在该时限内失败，而不是无限挂起；
 * 3. SSE 豁免钉：per-request `requestTimeoutMillis = Long.MAX_VALUE` 的长滴流
 *    （总时长远超 client 级 requestTimeout）必须完整读完——SSE 长流保护契约
 *    （浸泡 18min/300K 字符流）不受本修复波及。
 */
class SendChainTimeoutTest {

    // ---------- 1. 配置钉 ----------

    @Test
    fun `sendChainTimeout pins per-request capability config`() {
        val builder = HttpRequestBuilder()
        builder.sendChainTimeout()
        val cfg = builder.getCapabilityOrNull(HttpTimeoutCapability)
        assertNotNull("HttpTimeout capability must be set on the request builder", cfg)
        assertEquals(30_000L, cfg!!.requestTimeoutMillis)
        assertEquals(10_000L, cfg.connectTimeoutMillis)
        assertEquals(30_000L, cfg.socketTimeoutMillis)
    }

    // ---------- 2. 行为钉：冻结服务器 → requestTimeout killer 必须触发 ----------

    @Test
    fun `request timeout killer fires on frozen server with okhttp engine`(): Unit = runBlocking {
        // 冻结服务器模型：内核完成 accept/握手，应用层永不响应（SIGSTOP 等价）
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val held = mutableListOf<java.net.Socket>()
        thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept() }.getOrNull()?.let { held.add(it) }
            }
        }
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                // 与 NetworkModule 同构，仅时间尺度压缩（120s→400ms）
                requestTimeoutMillis = 400
                connectTimeoutMillis = 2_000
                socketTimeoutMillis = 2_000 // socket 宽于 request：killer 应先到
            }
        }
        try {
            val start = System.nanoTime()
            val error = runCatching {
                client.post("http://127.0.0.1:${server.localPort}/api/session/s1/prompt") {
                    setBody("{}")
                }
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertNotNull("frozen server must produce a failure, not hang", error)
            assertTrue(
                "expect HttpRequestTimeoutException (plugin-level killer), got: $error",
                error is HttpRequestTimeoutException,
            )
            assertTrue(
                "killer should fire near requestTimeout(400ms), took ${elapsedMs}ms",
                elapsedMs in 300..2_500,
            )
        } finally {
            client.close()
            server.close()
            held.forEach { runCatching { it.close() } }
        }
    }

    // ---------- 3. SSE 豁免钉：MAX_VALUE 长滴流不受 client 级 requestTimeout 波及 ----------

    @Test
    fun `sse style trickling stream survives client requestTimeout via per-request MAX_VALUE`(): Unit = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                val sock = server.accept()
                sock.soTimeout = 5_000
                // 读掉请求头（GET 无 body，读到空行为止）
                val input = sock.getInputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    if (String(buf, 0, n).contains("\r\n\r\n")) break
                }
                val out = sock.getOutputStream()
                out.write(
                    "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                        .toByteArray(),
                )
                out.flush()
                // 10 块 × 80ms = 800ms 滴流（远超 client 级 300ms requestTimeout）
                repeat(10) {
                    out.write("4\r\nwxyz\r\n".toByteArray())
                    out.flush()
                    Thread.sleep(80)
                }
                out.write("0\r\n\r\n".toByteArray())
                out.flush()
                sock.close()
            }
        }
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 300 // client 级短超时——若无豁免必杀
                connectTimeoutMillis = 2_000
                socketTimeoutMillis = 2_000
            }
        }
        try {
            val received = StringBuilder()
            // SseClient/SseClientV2 同款形态：prepareGet + per-request timeout 豁免
            client.prepareGet("http://127.0.0.1:${server.localPort}/api/event") {
                timeout {
                    requestTimeoutMillis = Long.MAX_VALUE
                    connectTimeoutMillis = 2_000
                    socketTimeoutMillis = 2_000
                }
            }.execute { resp ->
                assertEquals(200, resp.status.value)
                val channel = resp.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    channel.readUTF8Line(1024)?.let { received.appendLine(it) } ?: break
                }
            }
            // 800ms 滴流完整读完（≥8 块，每块一行 "wxyz"）且全程无超时异常——SSE 契约在位
            assertTrue(
                "trickling stream must be fully received, got: ${received}",
                received.count { it == 'w' } >= 8,
            )
        } finally {
            client.close()
            server.close()
        }
    }
}
