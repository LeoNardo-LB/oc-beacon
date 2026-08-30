package dev.leonardo.ocbeacon.data.api.dsh

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * DshWsEventClient 双 WS 下行客户端测试（backlog #274 组件 ④；设计文档 §1.6）。
 *
 * 项目无 mockwebserver 依赖（不得自行加 gradle 依赖）→ 降级方案：
 * - 帧处理器 / 退避序列 / 状态聚合 = 纯逻辑单测（黄金样本 mux-frames.jsonl）
 * - 引擎编排（OkHttp 监听器 → 解码 → 回调 / 重连 / stop 幂等）= 注入
 *   [DshWebSocketOpener] 假实现 + TestScope 虚拟时钟驱动退避 delay
 * - 真实 WS 握手（Host 栅栏 / pingInterval 活性）留 E2E（§6 真机计划）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshWsEventClientTest {

    // ============ DshBackoff：指数退避序列（§1.6-5 官方参考参数） ============

    @Test
    fun `backoff doubles from 500ms caps at 10s with half-open jitter`() {
        // 抖动公式（任务契约）：cap/2 + rand × cap/2 → 结果落在 [raw/2, raw)
        val zero = DshBackoff(random = { 0.0 })
        val expected = listOf(250L, 500L, 1000L, 2000L, 4000L, 5000L, 5000L, 5000L)
        assertEquals(expected, (0..7).map { zero.delayMs(it) })

        val mid = DshBackoff(random = { 0.5 })
        assertEquals(375L, mid.delayMs(0))       // 250 + 0.5×250
        assertEquals(7500L, mid.delayMs(5))      // 封顶 10000 → 5000 + 0.5×5000

        val nearMax = DshBackoff(random = { 0.999999 })
        assertTrue(nearMax.delayMs(0) < 500L)
        assertTrue(nearMax.delayMs(0) >= 250L)
        assertTrue(nearMax.delayMs(9) < 10_000L)
        assertTrue(nearMax.delayMs(9) >= 5_000L)
    }

    // ============ 帧处理器（纯逻辑） ============

    @Test
    fun `frame handler forwards decoded server request method payload and rpcId`() {
        val golden = """{"type":"server-request","rpcId":"f-1","method":"session/subscribed","payload":{"type":"session/subscribed","sessionId":"fixture-0001","lastSeq":15}}"""
        var method: String? = null
        var payload: kotlinx.serialization.json.JsonObject? = null
        var rpcId: String? = null
        // #276 接线注意①：rpcId 透传（question 回程路由依赖）
        val handled = handleDshWsFrame(golden) { m, p, r -> method = m; payload = p; rpcId = r }
        assertTrue(handled)
        assertEquals("session/subscribed", method)
        assertEquals(15L, payload!!["lastSeq"]!!.jsonPrimitive.long)
        assertEquals("f-1", rpcId)
    }

    @Test
    fun `frame handler drops malformed frames and wrong envelope types`() {
        val frames = listOf(
            "not-json-garbage",
            "",
            """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":{}}}""",
            """{"type":"server-request","rpcId":"r","method":"session/event","payload":{"type":"session/queue"}}""",
        )
        frames.forEach { text ->
            var called = false
            val handled = handleDshWsFrame(text) { _, _, _ -> called = true }
            assertFalse("畸形帧必须被丢弃: " + text, handled)
            assertFalse(called)
        }
    }

    // ============ 状态聚合（取最差） ============

    @Test
    fun `aggregate takes worst of stream states`() {
        assertEquals(DshWsConnectionState.Connected, aggregateDshWsState(listOf(DshWsConnectionState.Connected, DshWsConnectionState.Connected)))
        assertEquals(DshWsConnectionState.Connecting, aggregateDshWsState(listOf(DshWsConnectionState.Connected, DshWsConnectionState.Connecting)))
        assertEquals(DshWsConnectionState.Disconnected, aggregateDshWsState(listOf(DshWsConnectionState.Connected, DshWsConnectionState.Disconnected)))
        assertEquals(DshWsConnectionState.Disconnected, aggregateDshWsState(listOf(DshWsConnectionState.Connecting, DshWsConnectionState.Disconnected)))
        assertEquals(DshWsConnectionState.Disconnected, aggregateDshWsState(emptyList()))
    }

    // ============ OkHttp 配置（§1.6-3 服务端零心跳 → 客户端自证活性必配） ============

    @Test
    fun `default okhttp client has 25s ping interval`() {
        val engine = DshWsEventEngine(scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals(TimeUnit.SECONDS.toMillis(25), engine.client.pingIntervalMillis.toLong())
        engine.stop()
    }

    // ============ 引擎编排（注入 opener + 虚拟时钟） ============

    private class RecordingOpener : DshWebSocketOpener {
        val urls = mutableListOf<String>()
        val listeners = mutableListOf<WebSocketListener>()
        override fun open(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket {
            // OkHttp HttpUrl 把 ws/wss 规范化存储为 http/https（newWebSocket 仍走 WS 升级），
            // 故记录 path@host:port 而非完整 scheme
            urls += request.url.encodedPath + "@" + request.url.host + ":" + request.url.port
            listeners += listener
            return mockk(relaxed = true)
        }
    }

    @Test
    fun `start opens both mux and host websockets with ws scheme`() = runTest {
        val opener = RecordingOpener()
        val engine = DshWsEventEngine(scope = backgroundScope, backoff = DshBackoff(random = { 0.0 }), opener = opener)
        engine.start("http://127.0.0.1:3080") { _, _, _ -> }
        runCurrent()
        assertEquals(
            listOf("/api/events.mux@127.0.0.1:3080", "/api/events.host@127.0.0.1:3080"),
            opener.urls,
        )
        engine.stop()
    }

    @Test
    fun `state connects when both open and degrades to worst on failure`() = runTest {
        val opener = RecordingOpener()
        val engine = DshWsEventEngine(scope = backgroundScope, backoff = DshBackoff(random = { 0.0 }), opener = opener)
        engine.start("http://127.0.0.1:3080") { _, _, _ -> }
        runCurrent()
        assertEquals(DshWsConnectionState.Connecting, engine.connectionState.value)

        opener.listeners[0].onOpen(mockk(), mockk(relaxed = true))
        runCurrent()
        assertEquals(DshWsConnectionState.Connecting, engine.connectionState.value) // 单流打开仍 Connecting

        opener.listeners[1].onOpen(mockk(), mockk(relaxed = true))
        runCurrent()
        assertEquals(DshWsConnectionState.Connected, engine.connectionState.value)

        // mux 流失败 → 聚合取最差 = Disconnected
        opener.listeners[0].onFailure(mockk(), RuntimeException("reset"), null)
        runCurrent()
        assertEquals(DshWsConnectionState.Disconnected, engine.connectionState.value)
        engine.stop()
    }

    @Test
    fun `text frames on mux listener are forwarded to onFrame callback`() = runTest {
        val opener = RecordingOpener()
        val received = mutableListOf<Pair<String, Long>>()
        val engine = DshWsEventEngine(scope = backgroundScope, backoff = DshBackoff(random = { 0.0 }), opener = opener)
        engine.start("http://127.0.0.1:3080") { method, payload, _ ->
            received += method to payload["lastSeq"]!!.jsonPrimitive.long
        }
        runCurrent()
        val golden = """{"type":"server-request","rpcId":"f-1","method":"session/subscribed","payload":{"type":"session/subscribed","sessionId":"fixture-0001","lastSeq":15}}"""
        opener.listeners[0].onMessage(mockk(), golden)
        opener.listeners[1].onMessage(mockk(), "}}malformed{{")
        assertEquals(listOf("session/subscribed" to 15L), received) // 畸形帧丢弃不影响好帧
        engine.stop()
    }

    @Test
    fun `failed stream reconnects same endpoint after backoff delay`() = runTest {
        val opener = RecordingOpener()
        val engine = DshWsEventEngine(scope = backgroundScope, backoff = DshBackoff(random = { 0.0 }), opener = opener)
        engine.start("http://127.0.0.1:3080") { _, _, _ -> }
        runCurrent()
        assertEquals(2, opener.urls.size)

        opener.listeners[0].onFailure(mockk(), RuntimeException("eof"), null)
        runCurrent()
        opener.urls.clear()
        opener.listeners.clear()

        // 退避 250ms（500×2⁰×0.5，rand=0）：249ms 时不重连，250ms 时重连同一流
        advanceTimeBy(249)
        runCurrent()
        assertEquals(0, opener.urls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("/api/events.mux@127.0.0.1:3080"), opener.urls)
        engine.stop()
    }

    @Test
    fun `stop cancels reconnection and is idempotent`() = runTest {
        val opener = RecordingOpener()
        val engine = DshWsEventEngine(scope = backgroundScope, backoff = DshBackoff(random = { 0.0 }), opener = opener)
        engine.start("http://127.0.0.1:3080") { _, _, _ -> }
        runCurrent()
        opener.listeners[0].onFailure(mockk(), RuntimeException("eof"), null)
        runCurrent()

        engine.stop()
        engine.stop() // 幂等：二次 stop 不抛不重连

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, opener.urls.size) // 初始双流之外零重开
        assertEquals(DshWsConnectionState.Disconnected, engine.connectionState.value)
    }

    @Test
    fun `repeated start stops previous generation first`() = runTest {
        val opener = RecordingOpener()
        val engine = DshWsEventEngine(scope = backgroundScope, backoff = DshBackoff(random = { 0.0 }), opener = opener)
        engine.start("http://127.0.0.1:3080") { _, _, _ -> }
        runCurrent()
        engine.start("http://127.0.0.1:3080") { _, _, _ -> } // 先 stop 旧代 → 无残留重连
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(4, opener.urls.size) // 恰好两代 × 双流
        engine.stop()
    }
}
