package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片7：DSH 0.1.5 / 会话格式 V3 适配（P0 修复面）。
 *
 * 断言：V3 新词汇不拒绝重建（容错优先）、PTC 派发复用子代理卡、surfaceOp 双读。
 */
class DshV3AdaptationTest {

    private fun env(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `ptc dispatch reuses the legacy code-dispatch mapping`() {
        val data = """{"kind":"foreground","runId":"run-1","output":"{\"subagent\":\"child-1\"}"}"""
        val legacy = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"tool/code-dispatch","seq":5,"time":100,"data":$data}"""),
        )
        val v3 = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"tool/ptc-dispatch","seq":5,"time":100,"data":$data}"""),
        )
        assertEquals(legacy, v3)
        assertTrue(v3.none { it is DshMappedEvent.Ignored && it.reason == DshIgnoreReason.UNKNOWN_DEGRADED })
    }

    @Test
    fun `v3 vocabulary is named-degraded and never a structural violation`() {
        val v3Types = listOf(
            "assistant/attempt",
            "feedback/message-put",
            "feedback/message-delete",
            "subagent/catalog",
        )
        for (type in v3Types) {
            val mapped = DshEventMapper.mapSessionEvent("s1", env("""{"type":"$type","seq":1,"time":1,"data":{}}"""))
            assertEquals(
                "type=$type 应具名收编为 V3 词汇",
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.SESSION_FORMAT_V3)),
                mapped,
            )
        }
    }

    @Test
    fun `v3 system message maps to an injection card message`() {
        // 实况载荷：data.message{id,role=system,source{kind:plugin},content[text]}
        val data = """{"turn":1,"step":1,"message":{"id":"v2-to-v3-system-abc","role":"system","source":{"kind":"plugin","plugin":"@deepseek-ai/dsh-system-prompt"},"content":[{"type":"text","text":"sys prompt"}]}}"""
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"system/message","seq":9,"time":100,"data":$data}"""),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        val updated = events.filterIsInstance<SseEvent.MessageUpdated>().single()
        val user = updated.info as dev.leonardo.ocbeacon.domain.model.Message.User
        assertEquals("system", user.role)
        assertEquals("plugin", user.injectionKind)
        assertTrue(user.id.startsWith("dsh-sys-"))
        val text = events.filterIsInstance<SseEvent.MessagePartUpdated>().single().part
            as dev.leonardo.ocbeacon.domain.model.Part.Text
        assertEquals("sys prompt", text.text)
    }

    @Test
    fun `v3 system message with empty content produces no events`() {
        val data = """{"turn":1,"step":1,"message":{"id":"s","role":"system","source":{"kind":"plugin"},"content":[]}}"""
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"system/message","seq":9,"time":100,"data":$data}"""),
        )
        assertTrue(mapped.isEmpty())
    }

    @Test
    fun `v3 deliverables presented attaches authoritative files to the present tool host`() {
        // 实况载荷：data{turn,callId,files[{path,description}]}（dsh-tool-present）
        val data = """{"turn":1,"callId":"call_abc:ptc:1","files":[
            {"path":"/tmp/recon/slice3.md","description":"切片3 盘点"},
            {"path":"   ","description":"空路径丢弃"},
            {"path":"/tmp/keep.md","description":"ok"}]}"""
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"deliverables/presented","seq":205,"time":42,"data":$data}"""),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        val part = events.filterIsInstance<SseEvent.MessagePartUpdated>().single().part
            as dev.leonardo.ocbeacon.domain.model.Part.Deliverables
        // PTC 子调用 id → 根 run_code 工具卡宿主（rootCallId 剥 :ptc:N）
        assertEquals("dsh-call-call_abc", part.messageId)
        assertEquals("dsh-deliverables-call_abc:ptc:1", part.id)
        assertEquals(listOf("/tmp/recon/slice3.md", "/tmp/keep.md"), part.presented.map { it.path })
        assertEquals("切片3 盘点", part.presented[0].description)
        assertTrue("合法载荷不得落任何 Ignored", mapped.none { it is DshMappedEvent.Ignored })
    }

    @Test
    fun `ptc subcall id resolves to its root tool host`() {
        assertEquals("call_root", DshEventMapper.rootCallId("call_root:ptc:3"))
        assertEquals("call_root", DshEventMapper.rootCallId("call_root"))
        // 非 PTC 冒号形态与非 callId 前缀原样保留（不得误剥）
        assertEquals("a:b", DshEventMapper.rootCallId("a:b"))
        assertEquals(":ptc:1", DshEventMapper.rootCallId(":ptc:1"))
    }

    @Test
    fun `v3 deliverables presented without usable files produces no events`() {
        val empty = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"deliverables/presented","seq":1,"time":1,"data":{"turn":1,"callId":"c1","files":[]}}"""),
        )
        assertTrue(empty.isEmpty())
        val blank = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"deliverables/presented","seq":1,"time":1,"data":{"turn":1,"callId":"c1","files":[{"path":" "}]}}"""),
        )
        assertTrue(blank.isEmpty())
        val noCallId = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"deliverables/presented","seq":1,"time":1,"data":{"turn":1,"files":[{"path":"a"}]}}"""),
        )
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.MALFORMED)), noCallId)
    }

    @Test
    fun `v3 user message reasoning block maps to a reasoning part (tool-call mirror silently dropped)`() {
        // #398 实况取证：V3 user/message 可载 reasoning / tool-call 内容块，此前落 else 丢弃
        val data = """{"content":[{"type":"reasoning","text":"think"},{"type":"tool-call","id":"c1"},{"type":"text","text":"hi"}],"role":"user","id":"u1"}"""
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env("""{"type":"user/message","seq":9,"time":10,"data":$data}"""),
        )
        val parts = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
            .filterIsInstance<SseEvent.MessagePartUpdated>()
            .map { it.part }
        assertEquals(2, parts.size) // reasoning + text；tool-call 为冗余镜像不产 part
        val reasoning = parts.filterIsInstance<dev.leonardo.ocbeacon.domain.model.Part.Reasoning>().single()
        assertEquals("think", reasoning.text)
        assertEquals(
            "hi",
            parts.filterIsInstance<dev.leonardo.ocbeacon.domain.model.Part.Text>().single().text,
        )
    }

    @Test
    fun `future unknown vocabulary degrades without refusing the page`() {
        val rows = listOf(
            env("""{"type":"session","version":3,"id":"v3-1","createdAt":1,"cwd":"/w"}"""),
            env("""{"type":"future/unknown-2077","seq":1,"time":2,"data":{}}"""),
            env("""{"type":"deliverables/presented","seq":2,"time":3,"data":{}}"""),
        )
        val fold = DshHistoryFolder.fold(rows)
        assertTrue("未知词汇不得累积结构性违约", fold.structuralViolations.isEmpty())
        assertEquals(false, fold.refusedRebuild)
        assertEquals(2L, fold.lastSeq)
    }

    @Test
    fun `assistant-stream chunk synthesised as legacy chunk maps to part delta`() {
        // 引擎把 assistant-stream chunk 帧合成为 {type:assistant/chunk, data:{turn,step,chunk}}
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env(
                """{"type":"assistant/chunk","seq":0,"time":42,"data":
                   {"turn":1,"step":2,"chunk":{"type":"text-delta","index":0,"text":"hi"}}}"""
            ),
        )
        val delta = mapped.filterIsInstance<DshMappedEvent.Sse>()
            .map { it.event }
            .filterIsInstance<SseEvent.MessagePartDelta>()
        assertEquals(1, delta.size)
        assertEquals("hi", delta[0].delta)
        // 流式宿主 id 契约：dsh-t{turn}s{step}（chunk 帧的 turn/step 由 start 帧登记换算）
        assertEquals("dsh-t1s2", delta[0].messageId)
    }

    @Test
    fun `surfaceOp replace reads V3 startSeq endSeq`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env(
                """{"type":"user/message","seq":9,"time":20,"data":{"id":"m1",
                   "surfaceOp":{"op":"replace","startSeq":3,"endSeq":7},"content":[]}}"""
            ),
        )
        val replaced = mapped.filterIsInstance<DshMappedEvent.Sse>()
            .map { it.event }
            .filterIsInstance<SseEvent.SurfaceRangeReplaced>()
        assertEquals(1, replaced.size)
        assertEquals(3L, replaced[0].startSeq)
        assertEquals(7L, replaced[0].endSeq)
    }

    @Test
    fun `surfaceOp replace still reads legacy start end`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            env(
                """{"type":"user/message","seq":9,"time":20,"data":{"id":"m2",
                   "surfaceOp":{"op":"replace","start":4,"end":8},"content":[]}}"""
            ),
        )
        val replaced = mapped.filterIsInstance<DshMappedEvent.Sse>()
            .map { it.event }
            .filterIsInstance<SseEvent.SurfaceRangeReplaced>()
        assertEquals(1, replaced.size)
        assertEquals(4L, replaced[0].startSeq)
        assertEquals(8L, replaced[0].endSeq)
    }
}
