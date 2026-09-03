package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.1.2 remote.mux 层单测（#318；journal 2026-09-04-dsh-012-adaptation §2.4-2.5）：
 * - [DshMuxCodec]：item/error/end 三型 + 畸形拒收；
 * - [DshMuxSynthesizer]：0.1.2 值 → 0.1.1 帧词汇合成表（emit/waterfall/cancel/
 *   follow snapshot/增量/control baseline）——帧面契约的回归钉。
 * 纯逻辑：不涉 socket（真实握手留 E2E，对齐 DshWsEventClientTest 头注释纪律）。
 */
class DshRemoteMuxEngineTest {

    private data class SynthFrame(val method: String, val payload: JsonObject, val rpcId: String)

    private fun synthesizer(frames: MutableList<SynthFrame>, ready: MutableList<String>): DshMuxSynthesizer =
        DshMuxSynthesizer(
            onFrame = { m, p, r -> frames += SynthFrame(m, p, r) },
            onReady = { ready += it },
        )

    private fun item(streamId: String, valueJson: String): DshMuxCodec.Item? =
        DshMuxCodec.decode("""{"type":"item","streamId":"$streamId","value":$valueJson}""") as? DshMuxCodec.Item

    // ---- DshMuxCodec -------------------------------------------------------

    @Test
    fun codec_decodesItemErrorEnd() {
        val item = DshMuxCodec.decode("""{"type":"item","streamId":"evt","value":{"type":"ready","clientId":"c1"}}""")
        assertTrue(item is DshMuxCodec.Item && item.streamId == "evt")
        val err = DshMuxCodec.decode("""{"type":"error","streamId":"f:x","error":{"code":"e"}}""")
        assertTrue(err is DshMuxCodec.StreamError && err.streamId == "f:x")
        val end = DshMuxCodec.decode("""{"type":"end","streamId":"f:x"}""")
        assertTrue(end is DshMuxCodec.End)
    }

    @Test
    fun codec_rejectsMalformed() {
        assertNull(DshMuxCodec.decode("not json"))
        assertNull(DshMuxCodec.decode("""{"type":"item"}""")) // 无 streamId
        assertNull(DshMuxCodec.decode("""{"type":"other","streamId":"x"}"""))
    }

    // ---- $events：ready / emit ----------------------------------------------

    @Test
    fun ready_reportsClientId() {
        val ready = mutableListOf<String>()
        val syn = synthesizer(mutableListOf(), ready)
        syn.onItem("evt", Json.parseToJsonElement("""{"type":"ready","clientId":"abc-123","host":{"home":"/root"}}""") as JsonObject, ConcurrentHashMap())
        assertEquals(listOf("abc-123"), ready)
    }

    @Test
    fun emit_apiSessionAdded_synthesizesHostSessionAdded() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/added","args":[{"sessionId":"s1","updatedAt":1,"running":false,"cwd":"/tmp","projections":{"asOfSeq":2}}]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("host/session-added", frames[0].method)
        assertEquals("s1", frames[0].payload.strField("sessionId"))
        assertEquals("/tmp", frames[0].payload.strField("cwd"))
    }

    @Test
    fun emit_apiSessionStatus_synthesizesHostSessionStatus() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement("""{"type":"emit","event":"api-session/status","args":["s1",true]}""") as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals("host/session-status", frames[0].method)
        assertEquals("s1", frames[0].payload.strField("sessionId"))
        // JsonPrimitive.content 恒字符串——布尔原语按 "true" 字面量断言
        assertEquals("true", (frames[0].payload["running"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun emit_commandsChange_synthesizesCommandsChanged() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement("""{"type":"emit","event":"commands/change","args":[]}""") as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals("commands/change", frames[0].method)
    }

    // ---- waterfall -----------------------------------------------------------

    @Test
    fun waterfall_userQuestions_synthesizesQuestionRequestedWithEventId() {
        val frames = mutableListOf<SynthFrame>()
        val pending = ConcurrentHashMap<String, String>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"waterfall","event":"user-questions/request","eventId":"ev-1","agentId":"s1","request":{"questions":[{"id":"q1","question":"选哪个？","multiSelect":false,"options":[{"label":"蓝"}]}]}}""",
            ) as JsonObject,
            pending,
        )
        assertEquals("question/requested", frames[0].method)
        assertEquals("ev-1", frames[0].rpcId)
        assertEquals("s1", frames[0].payload.strField("sessionId"))
        val q = (frames[0].payload["questions"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
        // multiSelect → multi_select 归一化（mapper 键）
        assertEquals("false", (q?.get("multi_select") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("question/requested", pending["ev-1"])
    }

    @Test
    fun cancel_pendingQuestion_synthesizesQuestionResolvedCancelled() {
        val frames = mutableListOf<SynthFrame>()
        val pending = ConcurrentHashMap(mapOf("ev-9" to "question/requested"))
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement("""{"type":"cancel","eventId":"ev-9"}""") as JsonObject,
            pending,
        )
        assertEquals("question/resolved", frames[0].method)
        assertEquals("true", (frames[0].payload["cancelled"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertNull(pending["ev-9"])
    }

    // ---- session/follow -------------------------------------------------------

    @Test
    fun followSnapshot_synthesizesSubscribedBaselineEventsAndProjections() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "f:s1",
            Json.parseToJsonElement(
                """{"type":"snapshot","header":{"id":"s1"},"cursor":7,"records":[
                    {"type":"event","event":{"type":"permission/preset","seq":0,"time":1,"data":{"preset":"ask"}}},
                    {"type":"chunks","event":{"type":"chunkrow/x","seq":1,"time":1,"data":{}}}
                   ],"hasMore":false,"projections":{"asOfSeq":7,"values":{"title":"t"}}}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        // subscribed 基线（对账起点）+ 1 event（chunks 跳过）+ 1 projection
        assertEquals("session/subscribed", frames[0].method)
        assertEquals("7", frames[0].payload.strField("lastSeq"))
        assertEquals("session/event", frames[1].method)
        assertEquals("s1", frames[1].payload.strField("sessionId"))
        assertEquals("session/projection", frames.last().method)
        assertEquals("title", frames.last().payload.strField("key"))
        assertEquals(3, frames.size)
    }

    @Test
    fun followIncrementalEvent_synthesizesSessionEvent() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "f:s1",
            Json.parseToJsonElement(
                """{"type":"event","event":{"type":"assistant/chunk","seq":8,"time":9,"data":{}}}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals("session/event", frames[0].method)
        assertEquals(8L, ((frames[0].payload["event"] as? JsonObject)?.get("seq") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLong())
    }

    // ---- session/control ------------------------------------------------------

    @Test
    fun controlBaseline_synthesizesJobsQueueProjectionFrames() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "ctl",
            Json.parseToJsonElement(
                """{"type":"baseline","value":{
                    "jobs":{"s1":[{"id":"j1"}]},
                    "queues":{"s1":[{"id":"m1","placement":"queued"}]},
                    "projections":{"s1":{"asOfSeq":3,"values":{"tokenUsage":{"outputTokens":5}}}}
                   }}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        val methods = frames.map { it.method }
        assertTrue(methods.contains("session/jobs"))
        assertTrue(methods.contains("session/queue"))
        assertTrue(methods.contains("session/projection"))
        val jobs = frames.first { it.method == "session/jobs" }
        assertEquals("s1", jobs.payload.strField("sessionId"))
        val queue = frames.first { it.method == "session/queue" }
        assertEquals("s1", queue.payload.strField("sessionId"))
    }

    private fun JsonObject.strField(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
}
