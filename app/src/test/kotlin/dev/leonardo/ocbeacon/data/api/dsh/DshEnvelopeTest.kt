package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * DshEnvelope 四象限信封编解码测试（backlog #274 组件 ①）。
 *
 * 黄金样本来自 app/src/test/resources/dsh/（2026-08-31 探针实测形态，
 * 设计文档 docs/specs/2026-08-31-dsh-integration-design.md §1/§5）：
 * - rpc-examples.json：RPC 面（client-request / server-response / client-response）
 * - mux-frames.jsonl：WS 下行帧面（server-request）
 */
class DshEnvelopeTest {

    private val json = Json

    private fun resourceText(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.readBytes().decodeToString()

    /** 从 rpc-examples.json 取单个信封样本的原始 JSON 串（保真：不重排字段）。 */
    private fun fixture(key: String): String =
        (json.parseToJsonElement(resourceText("dsh/rpc-examples.json")) as JsonObject)[key]!!.toString()

    // ============ ClientRequest ============

    @Test
    fun `client request round trips through encode and decode`() {
        val envelope = DshEnvelope.ClientRequest(
            rpcId = "rpc-rt-1",
            method = "session.history",
            payload = buildJsonObject { put("sessionId", "fixture-0001"); put("maxMessages", 2) },
        )
        assertEquals(envelope, DshEnvelope.decode(DshEnvelope.encode(envelope)))
    }

    @Test
    fun `decode fixture sessionListRequest matches wire shape`() {
        val envelope = DshEnvelope.decode(fixture("sessionListRequest"))
        assertEquals(
            DshEnvelope.ClientRequest("rpc-list-1", "session.list", buildJsonObject {}),
            envelope,
        )
    }

    @Test
    fun `newRpcId returns distinct valid UUIDs`() {
        val a = DshEnvelope.newRpcId()
        val b = DshEnvelope.newRpcId()
        assertNotEquals(a, b)
        // rpcId 约定为 UUID 串（§1 实测契约）
        UUID.fromString(a)
        UUID.fromString(b)
    }

    // ============ ServerResponse ============

    @Test
    fun `decode fixture sessionListResponse keeps rpcId echo and ok value`() {
        val envelope = DshEnvelope.decode(fixture("sessionListResponse")) as DshEnvelope.ServerResponse
        // rpcId echo：响应必须回显请求 rpcId（§1 实测契约）
        assertEquals("rpc-list-1", envelope.rpcId)
        val ok = envelope.result as DshRpcResult.Ok
        val item = ok.value!!.jsonObject["items"]!!.jsonArray[0].jsonObject
        assertEquals("fixture-0001", item["sessionId"]!!.jsonPrimitive.content)
        assertEquals(1788109000023L, item["updatedAt"]!!.jsonPrimitive.long)
    }

    @Test
    fun `decode error response extracts code message and details`() {
        val envelope = DshEnvelope.decode(fixture("errorBadRequest")) as DshEnvelope.ServerResponse
        assertEquals("rpc-bad-1", envelope.rpcId)
        val err = envelope.result as DshRpcResult.Err
        assertEquals("bad-request", err.code.wire)
        assertEquals("method mismatch", err.message)
        assertEquals(buildJsonObject {}, err.details)
        assertTrue(err.code.isKnown)
    }

    @Test
    fun `unknown error code is preserved with isKnown false`() {
        // 39 值闭集外的错误码：保留原串容错（不崩、不丢码）
        val envelope = DshEnvelope.decode(fixture("respondReceiptNotPending")) as DshEnvelope.ServerResponse
        val err = envelope.result as DshRpcResult.Err
        assertEquals("not-pending", err.code.wire)
        assertFalse(err.code.isKnown)
    }

    @Test
    fun `error response round trips through encode and decode`() {
        val envelope = DshEnvelope.ServerResponse(
            rpcId = "rpc-err-1",
            result = DshRpcResult.Err(
                code = DshRpcErrorCode.SessionNotFound,
                message = "(not attached)",
                details = buildJsonObject { put("sessionId", "s-1") },
            ),
        )
        assertEquals(envelope, DshEnvelope.decode(DshEnvelope.encode(envelope)))
    }

    @Test
    fun `ok response round trips with null value`() {
        val envelope = DshEnvelope.ServerResponse("rpc-null-1", DshRpcResult.Ok(null))
        assertEquals(envelope, DshEnvelope.decode(DshEnvelope.encode(envelope)))
    }

    // ============ ServerRequest（WS 下行帧） ============

    @Test
    fun `decode all mux fixture frames as server request with payload type matching method`() {
        val lines = resourceText("dsh/mux-frames.jsonl").lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(9, lines.size)
        lines.forEach { line ->
            val envelope = DshEnvelope.decode(line) as DshEnvelope.ServerRequest
            // §1.5 实测：帧 method 即帧型，payload.type 与 method 一致
            assertEquals(envelope.method, (envelope.payload["type"] as JsonPrimitive).content)
        }
    }

    @Test
    fun `server request with payload type mismatching method is rejected`() {
        val frame = """{"type":"server-request","rpcId":"r-1","method":"session/event","payload":{"type":"session/queue"}}"""
        assertNull(DshEnvelope.decode(frame))
    }

    // ============ ClientResponse（respond 回程） ============

    @Test
    fun `decode fixture respondRequest and round trip`() {
        val envelope = DshEnvelope.decode(fixture("respondRequest")) as DshEnvelope.ClientResponse
        assertEquals("11111111-0000-0000-0000-000000000004", envelope.rpcId)
        val ok = envelope.result as DshRpcResult.Ok
        assertEquals("allowed-once", ok.value!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        assertEquals(envelope, DshEnvelope.decode(DshEnvelope.encode(envelope)))
    }

    // ============ 闭集完整性 ============

    @Test
    fun `error code closed set has 39 distinct known wire values`() {
        assertEquals(39, DshRpcErrorCode.ALL.size)
        assertEquals(39, DshRpcErrorCode.ALL.map { it.wire }.toSet().size)
        assertTrue(DshRpcErrorCode.ALL.all { it.isKnown })
        // 抽查闭集成员（§5 补遗行清单首尾 + fromWire 往返）
        assertTrue(DshRpcErrorCode.ALL.contains(DshRpcErrorCode.fromWire("bad-request")))
        assertTrue(DshRpcErrorCode.ALL.contains(DshRpcErrorCode.fromWire("internal")))
        assertEquals(DshRpcErrorCode.SessionNotFound, DshRpcErrorCode.fromWire("session-not-found"))
        assertFalse(DshRpcErrorCode.fromWire("totally-unknown").isKnown)
    }

    // ============ 畸形 JSON 容错 ============

    @Test
    fun `malformed or contract violating json returns null`() {
        val malformed = listOf(
            "not json",
            "",
            "{",
            "{}",                                                                             // 缺 type 判别式
            """{"type":"unknown-kind","rpcId":"r"}""",                                        // 未知信封类型
            """{"type":"client-request","rpcId":"r"}""",                                      // 缺 method/payload
            """{"type":"client-request","rpcId":"r","method":"session.list","payload":null}""", // payload 非对象
            """{"type":"server-response","rpcId":"r"}""",                                     // 缺 result
            """{"type":"server-response","rpcId":"r","result":{}}""",                         // 缺 ok
            """{"type":"server-response","rpcId":"r","result":{"ok":false}}""",               // 缺 error
            """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":{"message":"m"}}}""", // error 缺 code
            """{"type":"server-request","rpcId":"r","method":"session/event","payload":{"type":"session/event"}}{"trailing":"garbage"}""", // 尾随垃圾
        )
        malformed.forEach { text ->
            assertNull("expected null for: " + text, DshEnvelope.decode(text))
        }
    }
}
