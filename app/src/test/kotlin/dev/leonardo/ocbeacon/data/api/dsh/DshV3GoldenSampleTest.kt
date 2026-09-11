package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片7 / #398：DSH 会话格式 V3 **真实会话日志黄金样本**契约测试
 *（spec 用户故事 22：用真实会话日志黄金样本按代断言映射结果）。
 *
 * 载荷来源：本机 DSH 0.1.5-rc.1 归档
 * `~/.dsh/sessions/…/session.v3.jsonl.zstd`（c0ffb1a8，seq 203-207）与
 * `docs/research/2026-09-11-dsh-v3-event-payloads.md` §二。样本**逐字**保留，
 * 不臆造字段——断言钉死当前映射决策，作为后续「按代事件词汇表」结构重构的回归护栏。
 */
class DshV3GoldenSampleTest {

    private fun env(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun eventsOf(raw: String) =
        DshEventMapper.mapSessionEvent("s1", env(raw))
            .filterIsInstance<DshMappedEvent.Sse>()
            .map { it.event }

    /** 归档 seq204 逐字：PTC 子调用登记（present 内层工具）。 */
    private val ptcDispatchStartPresent = """{"type":"tool/ptc-dispatch-start","seq":204,"time":1789040800451,"data":{"rootCallId":"call_4b6a41bb836149099bc5d9f0","parentCallId":"call_4b6a41bb836149099bc5d9f0","subCallId":"call_4b6a41bb836149099bc5d9f0:ptc:1","name":"present","arguments":{"files":[{"path":"/tmp/recon/slice3-capabilities.md","description":"切片3 五族私有能力端口化只读盘点报告（file:line + 签名 + 行为）"}]}}}"""

    /** 归档 seq205 逐字：产物交付（present 工具权威事件）。 */
    private val deliverablesPresented = """{"type":"deliverables/presented","seq":205,"time":1789040800453,"data":{"turn":1,"callId":"call_4b6a41bb836149099bc5d9f0:ptc:1","files":[{"path":"/tmp/recon/slice3-capabilities.md","description":"切片3 五族私有能力端口化只读盘点报告（file:line + 签名 + 行为）"}]}}"""

    /** 归档 seq206 逐字：PTC 子调用回执（present 内层工具）。 */
    private val ptcDispatchPresent = """{"type":"tool/ptc-dispatch","seq":206,"time":1789040800453,"data":{"rootCallId":"call_4b6a41bb836149099bc5d9f0","parentCallId":"call_4b6a41bb836149099bc5d9f0","subCallId":"call_4b6a41bb836149099bc5d9f0:ptc:1","name":"present","arguments":{"files":[{"path":"/tmp/recon/slice3-capabilities.md","description":"切片3 五族私有能力端口化只读盘点报告（file:line + 签名 + 行为）"}]},"isError":false,"content":[{"type":"text","text":"Presented /tmp/recon/slice3-capabilities.md"}]}}"""

    @Test
    fun `golden present ptc dispatch is a non-card inner tool`() {
        for (raw in listOf(ptcDispatchStartPresent, ptcDispatchPresent)) {
            assertEquals(
                raw.take(40),
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.CODE_DISPATCH)),
                DshEventMapper.mapSessionEvent("s1", env(raw)),
            )
        }
    }

    @Test
    fun `golden deliverables presented lands on the root run_code host`() {
        val events = eventsOf(deliverablesPresented)
        val part = events.filterIsInstance<SseEvent.MessagePartUpdated>().single().part as Part.Deliverables
        assertEquals("dsh-call-call_4b6a41bb836149099bc5d9f0", part.messageId)
        assertEquals(listOf("/tmp/recon/slice3-capabilities.md"), part.presented.map { it.path })
        assertEquals("切片3 五族私有能力端口化只读盘点报告（file:line + 签名 + 行为）", part.presented[0].description)
    }

    @Test
    fun `golden system message empty content yields no transcript noise`() {
        // research §2.2 样本：content:[]——零信息不产事件
        val raw = """{"type":"system/message","seq":9,"time":1788991851436,"data":{"turn":1,"step":1,"message":{"id":"v2-to-v3-system-1bf46bf3","role":"system","source":{"kind":"plugin","plugin":"@deepseek-ai/dsh-system-prompt"},"content":[]}},"surfaceOp":"append"}"""
        assertTrue(DshEventMapper.mapSessionEvent("s1", env(raw)).isEmpty())
    }

    @Test
    fun `golden subagent catalog and assistant attempt stay named-degraded`() {
        val catalog = """{"type":"subagent/catalog","seq":595,"time":1789031280335,"data":{"version":0,"childId":"00b3cbcd","childCreatedAt":1789031280303,"mode":"continuable","label":"Fact-find repo patterns for plan"}}"""
        val attempt = """{"type":"assistant/attempt","seq":2063,"time":1788498579904,"data":{"turn":2,"step":35,"stream":[{"type":"chunk","time":1788498579898,"chunk":{"type":"usage","usage":{"inputTokens":0,"outputTokens":0,"totalTokens":0}}},{"type":"chunk","time":1788498579904,"chunk":{"type":"finish","reason":{"kind":"error","failure":{"message":"502 Bad Gateway","code":"SERVER"}}}}]}}"""
        for (raw in listOf(catalog, attempt)) {
            assertEquals(
                listOf(DshMappedEvent.Ignored(DshIgnoreReason.SESSION_FORMAT_V3)),
                DshEventMapper.mapSessionEvent("s1", env(raw)),
            )
        }
    }

    @Test
    fun `golden v3 transcript fold rebuilds without structural violations`() {
        val rows = listOf(
            env("""{"type":"session","version":3,"id":"v3-golden","createdAt":1,"cwd":"/w"}"""),
            env("""{"type":"tool/call","seq":203,"time":1789040800450,"data":{"callId":"call_4b6a41bb836149099bc5d9f0","name":"run_code","arguments":"{}"}}"""),
            env(ptcDispatchStartPresent),
            env(deliverablesPresented),
            env(ptcDispatchPresent),
            env("""{"type":"tool/result","seq":207,"time":1789040800454,"data":{"message":{"source":{"callId":"call_4b6a41bb836149099bc5d9f0"},"content":[{"type":"tool-result","content":[{"type":"text","text":"done"}]}]}}}"""),
        )
        val fold = DshHistoryFolder.fold(rows)
        assertTrue("V3 黄金样本不得累积结构性违约: " + fold.structuralViolations, fold.structuralViolations.isEmpty())
        assertEquals(false, fold.refusedRebuild)
        assertEquals(207L, fold.lastSeq)
    }
}
