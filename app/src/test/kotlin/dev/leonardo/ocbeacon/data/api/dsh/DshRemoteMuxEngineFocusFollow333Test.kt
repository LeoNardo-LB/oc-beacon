package dev.leonardo.ocbeacon.data.api.dsh

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #333 聚焦 follow 兜底（0.1.2 remote.mux 引擎腿）：
 * 窗口外（#319 限界集不含）/连接后从未开流的会话，用户进入 ChatRoute 时经
 * [DshRemoteMuxEngine.requestFollow] 动态开流——follow snapshot（cursor + 尾页
 * records）即转录基线；REST session/page 的 throughSeq 前置（session.list
 * projections.asOfSeq 对无投影缓存的冷会话合法缺席——服务器 summarizeCold/
 * projectionsFor 实证）不再是唯一入口。测试形态对齐 DshWsEventClientTest：
 * [DshWebSocketOpener] 假实现 + TestScope 虚拟时钟（真实握手留 E2E）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshRemoteMuxEngineFocusFollow333Test {

    private class RecordingOpener : DshWebSocketOpener {
        val listeners = mutableListOf<WebSocketListener>()
        val sockets = mutableListOf<WebSocket>()
        val sent = mutableListOf<String>()

        override fun open(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket {
            listeners += listener
            val ws = mockk<WebSocket>()
            every { ws.send(capture(sent)) } returns true
            sockets += ws
            return ws
        }
    }

    private val registry = object : DshMuxAuth {
        override fun cookieHeader(authority: String): String? = "dsh=test-cookie"
        override fun setClientId(authority: String, clientId: String) = Unit
        override fun markAuthFailure(authority: String) = Unit
        override suspend fun awaitCookie(authority: String, intervalMs: Long): String = "dsh=test-cookie"
    }

    private lateinit var opener: RecordingOpener
    private lateinit var engine: DshRemoteMuxEngine

    @Before
    fun setUp() {
        opener = RecordingOpener()
    }

    @After
    fun tearDown() {
        engine.stop()
    }

    private fun newEngine(
        resolve: suspend (String) -> DshFollowTarget? = { sid ->
            DshFollowTarget(sid, sessionAddress(sid))
        },
    ): DshRemoteMuxEngine =
        DshRemoteMuxEngine(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            baseUrl = "http://127.0.0.1:3080",
            registry = registry,
            listFollowTargets = { emptyList() },
            resolveFollowTarget = resolve,
            opener = opener,
        )

    private fun sessionAddress(sid: String): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("kind", JsonPrimitive("session"))
            put("sessionId", JsonPrimitive(sid))
        }

    /** 已发送 open 帧的 (streamId, endpoint) 对（解析型断言，免字符串拼接脆性）。 */
    private fun openFrames(): List<Pair<String, String>> =
        opener.sent.mapNotNull { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        }.filter { obj ->
            (obj["type"] as? JsonPrimitive)?.content == "open"
        }.map { obj ->
            val streamId = (obj["streamId"] as? JsonPrimitive)?.content ?: ""
            val endpoint = (obj["endpoint"] as? JsonPrimitive)?.content ?: ""
            streamId to endpoint
        }

    private fun followOpenFrame(streamId: String): JsonObject? =
        opener.sent.mapNotNull { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        }.firstOrNull { obj ->
            (obj["type"] as? JsonPrimitive)?.content == "open" &&
                (obj["streamId"] as? JsonPrimitive)?.content == streamId
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    /** 已连接状态下：聚焦请求 → 立即补开该会话 follow 流（窗口外会话不在连接期批量集内）。 */
    @Test
    fun `requestFollow while connected opens follow stream for focused session`() = runTest {
        engine = newEngine()
        engine.start { _, _, _ -> }
        runCurrent()
        opener.listeners[0].onOpen(opener.sockets[0], mockk<Response>(relaxed = true))
        runCurrent()
        assertTrue(openFrames().none { it.first == "f:s-old" })

        engine.requestFollow("s-old")
        runCurrent()

        val follow = openFrames().firstOrNull { it.first == "f:s-old" }
        assertEquals("session/follow", follow?.second)
        val frame = followOpenFrame("f:s-old") ?: error("follow open frame missing")
        val args = (frame["payload"] as? JsonObject)?.get("args") as? JsonObject
        val address = (args?.get("request") as? JsonObject)?.get("address") as? JsonObject
        assertEquals("session", address?.str("kind"))
        assertEquals("s-old", address?.str("sessionId"))
    }

    /** 连接前（socket 未开）到达的聚焦请求挂起，onOpen 后随连接补开——请求不丢。 */
    @Test
    fun `requestFollow before connect is retained and drained after connect`() = runTest {
        engine = newEngine()
        engine.start { _, _, _ -> }
        runCurrent()

        engine.requestFollow("s-old")
        runCurrent()
        assertTrue(opener.sent.isEmpty()) // 未连接不发送

        opener.listeners[0].onOpen(opener.sockets[0], mockk<Response>(relaxed = true))
        runCurrent()

        assertEquals("session/follow", openFrames().firstOrNull { it.first == "f:s-old" }?.second)
    }

    /** 解析失败（服务器 session.list 无该会话）不发送 follow 帧——静默放弃，无异常。 */
    @Test
    fun `requestFollow with unresolved target sends no frame`() = runTest {
        engine = newEngine(resolve = { null })
        engine.start { _, _, _ -> }
        runCurrent()
        opener.listeners[0].onOpen(opener.sockets[0], mockk<Response>(relaxed = true))
        runCurrent()

        engine.requestFollow("s-ghost")
        runCurrent()

        assertTrue(openFrames().none { it.first == "f:s-ghost" })
    }

    /** 重复聚焦同一会话幂等（followed 去重），不重复开流。 */
    @Test
    fun `requestFollow is idempotent per session`() = runTest {
        engine = newEngine()
        engine.start { _, _, _ -> }
        runCurrent()
        opener.listeners[0].onOpen(opener.sockets[0], mockk<Response>(relaxed = true))
        runCurrent()

        engine.requestFollow("s-old")
        runCurrent()
        engine.requestFollow("s-old")
        runCurrent()

        assertEquals(1, openFrames().count { it.first == "f:s-old" })
    }
}