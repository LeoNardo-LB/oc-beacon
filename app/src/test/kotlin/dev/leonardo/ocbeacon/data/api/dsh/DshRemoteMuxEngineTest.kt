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

    private fun synthesizer(
        frames: MutableList<SynthFrame>,
        ready: MutableList<String>,
        active: MutableList<String> = mutableListOf(),
    ): DshMuxSynthesizer =
        DshMuxSynthesizer(
            onFrame = { m, p, r -> frames += SynthFrame(m, p, r) },
            onReady = { ready += it },
            onSessionActive = { active += it },
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

    /**
     * #331：added 摘要（SessionSummary）带 updatedAt（服务器 summaryFor 实证）——
     * 合成帧必须透传，否则 SessionCreated.time.updated=epoch0 → 新会话行按
     * time.updated 倒序沉列表底部（「约 3 分钟才入列表顶位」观测的 added 帧腿）；
     * origin 一并透传（#333 origin 判别的下游消费键）。
     */
    @Test
    fun emit_apiSessionAdded_forwardsUpdatedAtAndOrigin() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/added","args":[{"sessionId":"s1","updatedAt":1788626112891,"running":false,"cwd":"/tmp","parentSessionId":"p1","origin":"subagent"}]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals("host/session-added", frames[0].method)
        assertEquals("1788626112891", frames[0].payload.strField("updatedAt"))
        assertEquals("subagent", frames[0].payload.strField("origin"))
    }

    /**
     * #310① A8 缺陷B根因钉：api-session/added 摘要是 SessionSummary（服务器
     * listFields 摊 header.parentSession 为 **parentSessionId** 键——index.js:1919）；
     * 旧实现误读 SessionWireHeader（follow snapshot 头）的 parentSession 键 →
     * added 帧恒丢父址 → SessionCreated 整替换抹掉子会话 parentId → 第二次发送
     * 分流条件失效误走 session/prompt 被拒（wire=session/agent-busy）。
     */
    @Test
    fun emit_apiSessionAdded_carriesParentSessionIdForSubagentChild() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/added","args":[{"sessionId":"s-child","updatedAt":1,"running":true,"cwd":"/w","parentSessionId":"s-parent","origin":"subagent"}]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals("host/session-added", frames[0].method)
        assertEquals("s-parent", frames[0].payload.strField("parentSessionId"))
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

    // ---- onSessionActive：三事件动态补开（#319 双轴审查补全） -----------------

    @Test
    fun sessionActive_apiSessionAdded_triggersCallback() {
        val active = mutableListOf<String>()
        val syn = synthesizer(mutableListOf(), mutableListOf(), active)
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/added","args":[{"sessionId":"s-new","updatedAt":1,"running":false}]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(listOf("s-new"), active)
    }

    @Test
    fun sessionActive_statusRunningTrue_triggersCallback() {
        val active = mutableListOf<String>()
        val syn = synthesizer(mutableListOf(), mutableListOf(), active)
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/status","args":["s-old",true]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        // running=true（>24h 老会话再激活）触发补开；host/session-status 帧照常合成
        assertEquals(listOf("s-old"), active)
    }

    @Test
    fun sessionActive_statusRunningFalse_doesNotTrigger() {
        val active = mutableListOf<String>()
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf(), active)
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/status","args":["s-idle",false]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertTrue(active.isEmpty())
        assertEquals("host/session-status", frames[0].method) // 帧合成不受影响
    }

    @Test
    fun sessionActive_activity_triggersCallback() {
        val active = mutableListOf<String>()
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf(), active)
        syn.onItem(
            "evt",
            Json.parseToJsonElement(
                """{"type":"emit","event":"api-session/activity","args":["s-wake",1690000000000]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        // 仅补开信号，不合成 0.1.1 帧（activity 数据面由 session.list 刷新承担）
        assertEquals(listOf("s-wake"), active)
        assertTrue(frames.isEmpty())
    }

    // ---- waterfall -----------------------------------------------------------

    @Test
    fun waterfall_userQuestions_synthesizesQuestionRequestedWithEventId() {
        val frames = mutableListOf<SynthFrame>()
        val pending = ConcurrentHashMap<String, PendingWaterfall>()
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
        assertEquals(PendingWaterfall("question/requested", "s1"), pending["ev-1"])
    }

    @Test
    fun cancel_pendingQuestion_synthesizesQuestionResolvedCancelled() {
        val frames = mutableListOf<SynthFrame>()
        val pending = ConcurrentHashMap(mapOf("ev-9" to PendingWaterfall("question/requested", "s-host")))
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement("""{"type":"cancel","eventId":"ev-9"}""") as JsonObject,
            pending,
        )
        assertEquals("question/resolved", frames[0].method)
        assertEquals("true", (frames[0].payload["cancelled"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        // #319 E2E 实证修复：resolved 帧必须带 sessionId（无 sid 被 mapper MALFORMED 丢弃
        // ——Web 端作答 settle 后 app 卡不消除根因）
        assertEquals("s-host", frames[0].payload.strField("sessionId"))
        assertNull(pending["ev-9"])
    }

    @Test
    fun cancel_pendingApproval_synthesizesApprovalResolvedWithSessionId() {
        val frames = mutableListOf<SynthFrame>()
        val pending = ConcurrentHashMap(mapOf("ev-7" to PendingWaterfall("approval/requested", "s-host")))
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "evt",
            Json.parseToJsonElement("""{"type":"cancel","eventId":"ev-7"}""") as JsonObject,
            pending,
        )
        assertEquals("approval/resolved", frames[0].method)
        assertEquals("s-host", frames[0].payload.strField("sessionId"))
        assertEquals("ev-7", frames[0].payload.strField("approvalId"))
        assertNull(pending["ev-7"])
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

    // ---- session/control 增量帧（#327：baseline 后的实时推送——服务器 types/control.js
    //      SessionControlController broadcast {type:'queue'|'jobs'|'projection',…}） ----

    @Test
    fun controlIncrementalQueue_synthesizesSessionQueueFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "ctl",
            Json.parseToJsonElement(
                """{"type":"queue","sessionId":"s1","items":[{"id":"m2","placement":"queued"}]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("session/queue", frames[0].method)
        assertEquals("s1", frames[0].payload.strField("sessionId"))
        assertTrue(frames[0].payload.containsKey("items"))
    }

    @Test
    fun controlIncrementalJobs_synthesizesSessionJobsFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "ctl",
            Json.parseToJsonElement(
                """{"type":"jobs","sessionId":"s1","jobs":[{"id":"j2"}]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("session/jobs", frames[0].method)
        assertEquals("s1", frames[0].payload.strField("sessionId"))
        assertTrue(frames[0].payload.containsKey("jobs"))
    }

    @Test
    fun controlIncrementalProjection_synthesizesSessionProjectionFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "ctl",
            Json.parseToJsonElement(
                """{"type":"projection","sessionId":"s1","key":"tokenUsage","value":{"outputTokens":9},"seq":12}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("session/projection", frames[0].method)
        assertEquals("s1", frames[0].payload.strField("sessionId"))
        assertEquals("tokenUsage", frames[0].payload.strField("key"))
    }

    // ---- workspace/follow（#311 Task1：baseline {items,archivedSessionIds} +
    //      增量 {type:'archived'}——workspace-controller types.d.ts:108-131；
    //      baseline 带 value 包装（同 session/control #327 形态），增量平铺） ----

    @Test
    fun workspaceBaseline_synthesizesWorkspaceBaselineFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "wsp",
            Json.parseToJsonElement(
                """{"type":"baseline","value":{
                    "items":[{"workspaceId":"ws-1","path":"/w","title":"W","sessionIds":["s-1"]}],
                    "archivedSessionIds":["s-9"]
                   }}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("workspace/baseline", frames[0].method)
        val item = (frames[0].payload["items"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? JsonObject
        assertEquals("ws-1", item?.strField("workspaceId"))
        assertEquals("s-9", (frames[0].payload["archivedSessionIds"] as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
    }

    @Test
    fun workspaceIncrementalArchived_synthesizesWorkspaceArchivedFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "wsp",
            Json.parseToJsonElement(
                """{"type":"archived","archivedSessionIds":["s-2","s-9"]}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("workspace/archived", frames[0].method)
        val ids = (frames[0].payload["archivedSessionIds"] as? kotlinx.serialization.json.JsonArray)
        assertEquals(2, ids?.size)
    }

    /** #311 Task3：upsert 增量（{type:'upsert', workspace:WorkspaceView}）→ 合成帧
     * workspace/upsert——title 重命名/新会话入组等注册表行变更的实时载体（对话框
     * title/sessionIds 消费面）。remove/order 增量消费见 #330 下方两测。 */
    @Test
    fun workspaceIncrementalUpsert_synthesizesWorkspaceUpsertFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "wsp",
            Json.parseToJsonElement(
                """{"type":"upsert","workspace":{"workspaceId":"ws-1","path":"/w","title":"Renamed","sessionIds":["s-1","s-2"]}}""",
            ) as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("workspace/upsert", frames[0].method)
        val workspace = frames[0].payload["workspace"] as? JsonObject
        assertEquals("ws-1", workspace?.strField("workspaceId"))
        assertEquals("Renamed", workspace?.strField("title"))
    }

    /** #330：remove 增量（{type:'remove', workspaceId}——服务器 changed() deleted 分支）
     * → 合成帧 workspace/remove——注册表行删除实时载体（重连 baseline 前即收敛，
     * #331 对话框陈旧条目随 remove 消费而减）。 */
    @Test
    fun workspaceIncrementalRemove_synthesizesWorkspaceRemoveFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "wsp",
            Json.parseToJsonElement("""{"type":"remove","workspaceId":"ws-1"}""") as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("workspace/remove", frames[0].method)
        assertEquals("ws-1", frames[0].payload.strField("workspaceId"))
    }

    /** #330：order 增量（{type:'order', workspaceIds}——服务器 publish 完整新序）
     * → 合成帧 workspace/order（数组透传，序即注册表显示序）。 */
    @Test
    fun workspaceIncrementalOrder_synthesizesWorkspaceOrderFrame() {
        val frames = mutableListOf<SynthFrame>()
        val syn = synthesizer(frames, mutableListOf())
        syn.onItem(
            "wsp",
            Json.parseToJsonElement("""{"type":"order","workspaceIds":["ws-2","ws-1","ws-3"]}""") as JsonObject,
            ConcurrentHashMap(),
        )
        assertEquals(1, frames.size)
        assertEquals("workspace/order", frames[0].method)
        val ids = (frames[0].payload["workspaceIds"] as? kotlinx.serialization.json.JsonArray)
        assertEquals(listOf("ws-2", "ws-1", "ws-3"), ids?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
    }
}
