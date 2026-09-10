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
            "system/message",
            "assistant/attempt",
            "feedback/message-put",
            "feedback/message-delete",
            "subagent/catalog",
            "deliverables/presented",
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
