package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #378 压缩转录实体族——mapper 契约（活体实录 journal 378-380-wire §五）：
 * compaction/start|summary|end 三事件双发（banner 域保留 + 转录实体）；summary 为
 * ContentBlock[]（原 data.str 恒 null 静默 Ignored 缺陷）；user/message 的
 * surfaceOp.replace 与 source.compactionId 副产物。
 */
class DshEventMapperCompaction378Test {

    private val json = Json

    private fun envelope(type: String, data: String, seq: Long, time: Long = 1788582193000): JsonObject =
        json.parseToJsonElement(
            "{\"type\":\"" + type + "\",\"seq\":" + seq + ",\"time\":" + time + ",\"data\":" + data + "}"
        ).jsonObject

    @Test
    fun `compaction start emits banner plus transcript entity`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("compaction/start", """{"compactionId":"k1","sourceCommandId":"cmd-1","turn":null}""", seq = 5390),
        )
        assertEquals(2, mapped.size)
        val transcript = mapped.filterIsInstance<DshMappedEvent.Sse>()
            .map { it.event }
            .filterIsInstance<SseEvent.CompactionStarted>()
            .single()
        assertEquals("k1", transcript.compactionId)
        assertEquals("cmd-1", transcript.sourceCommandId)
        assertEquals(5390L, transcript.seq)
    }

    @Test
    fun `compaction summary parses content blocks array`() {
        // wire 实录（seq-5391）：summary 是 ContentBlock[]——原 data.str 实现恒 null
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                "compaction/summary",
                """{"compactionId":"k1","sourceCommandId":"cmd-1","summary":[{"type":"text","text":"## 摘要A"},{"type":"text","text":"第二段"}]}""",
                seq = 5391,
            ),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        // banner delta（#309 通道修复）+ 转录实体 双发
        val transcript = events.filterIsInstance<SseEvent.CompactionSummary>().single()
        assertEquals("## 摘要A\n\n第二段", transcript.summaryText)
        assertEquals(5391L, transcript.seq)
        assertTrue(events.any { it is SseEvent.SessionNext })
    }

    @Test
    fun `compaction summary without text blocks stays ignored`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("compaction/summary", """{"compactionId":"k1","summary":[]}""", seq = 5391),
        )
        assertEquals(listOf(DshMappedEvent.Ignored(DshIgnoreReason.COMPACTION)), mapped)
    }

    @Test
    fun `compaction summary shadowedRange derives range replaced`() {
        // 三层根修（2026-09-09 原始 journal 实录 seq-205955）：折叠权威区间在
        // summary 事件的 data.shadowedRange——原 data.surfaceOp 判定从未命中
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                "compaction/summary",
                """{"compactionId":"k1","summary":[{"type":"text","text":"## 摘要"}],"shadowedRange":{"start":7,"end":204973}}""",
                seq = 205955,
            ),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        val range = events.filterIsInstance<SseEvent.SurfaceRangeReplaced>().single()
        assertEquals(7L, range.startSeq)
        assertEquals(204973L, range.endSeq)
        assertEquals("s1", range.sessionId)
        // 摘要双发不受影响
        assertTrue(events.any { it is SseEvent.CompactionSummary })
    }

    @Test
    fun `compaction prune shadowedRange derives range replaced`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                "compaction/prune",
                """{"shadowedRange":{"start":100,"end":200}}""",
                seq = 5399,
            ),
        )
        val range = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
            .filterIsInstance<SseEvent.SurfaceRangeReplaced>().single()
        assertEquals(100L, range.startSeq)
        assertEquals(200L, range.endSeq)
    }

    @Test
    fun `compaction prune without range stays ignored and malformed range dropped`() {
        // 无区间：计价事件维持 Ignored
        assertEquals(
            listOf(DshMappedEvent.Ignored(DshIgnoreReason.COMPACTION)),
            DshEventMapper.mapSessionEvent(
                "s1",
                envelope("compaction/prune", """{}""", seq = 5399),
            ),
        )
        // 区间残缺（end<start）：summary 侧摘要双发仍在，range 事件被丢弃
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                "compaction/summary",
                """{"compactionId":"k1","summary":[{"type":"text","text":"x"}],"shadowedRange":{"start":50,"end":10}}""",
                seq = 5400,
            ),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        assertTrue(events.filterIsInstance<SseEvent.SurfaceRangeReplaced>().isEmpty())
        assertTrue(events.any { it is SseEvent.CompactionSummary })
    }

    @Test
    fun `compaction end success emits transcript finish without error`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("compaction/end", """{"compactionId":"k1","sourceCommandId":"cmd-1","turn":null}""", seq = 5393),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        val finish = events.filterIsInstance<SseEvent.CompactionFinished>().single()
        assertEquals("k1", finish.compactionId)
        assertEquals(null, finish.error)
        assertTrue(events.any { it is SseEvent.SessionCompacted })
    }

    @Test
    fun `compaction end failure carries error to both channels`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("compaction/end", """{"compactionId":"k2","turn":1,"error":"summarize failed"}""", seq = 600),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        val finish = events.filterIsInstance<SseEvent.CompactionFinished>().single()
        assertEquals("summarize failed", finish.error)
    }

    @Test
    fun `user message with surfaceOp replace emits range replaced`() {
        // wire 实录（seq-5392）：压缩摘要载体 + surfaceOp{op:replace,start:9,end:4968}
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope(
                "user/message",
                """{"content":[{"type":"text","text":"checkpoint"}],"source":{"kind":"plugin","plugin":"compact","compactionId":"k1","sourceCommandId":"cmd-1"},"role":"user","id":"b42","surfaceOp":{"op":"replace","start":9,"end":4968}}""",
                seq = 5392,
            ),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        val range = events.filterIsInstance<SseEvent.SurfaceRangeReplaced>().single()
        assertEquals(9L, range.startSeq)
        assertEquals(4968L, range.endSeq)
        assertEquals("seq-s1-5392", range.byMessageId)
        val bound = events.filterIsInstance<SseEvent.CompactionSurfaceBound>().single()
        assertEquals("k1", bound.compactionId)
        assertEquals("seq-s1-5392", bound.messageId)
    }

    @Test
    fun `plain user message emits no surface artifacts`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("user/message", """{"content":[{"type":"text","text":"hi"}],"role":"user","id":"u1"}""", seq = 100),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        assertTrue(events.none { it is SseEvent.SurfaceRangeReplaced || it is SseEvent.CompactionSurfaceBound })
    }

    @Test
    fun `malformed surfaceOp is dropped without range event`() {
        val mapped = DshEventMapper.mapSessionEvent(
            "s1",
            envelope("user/message", """{"content":[],"surfaceOp":{"op":"replace","start":50,"end":9},"role":"user","id":"u2"}""", seq = 101),
        )
        val events = mapped.filterIsInstance<DshMappedEvent.Sse>().map { it.event }
        assertTrue(events.none { it is SseEvent.SurfaceRangeReplaced })
    }

    @Test
    fun `transcript events filter selects card family only`() {
        val family = DshTranscriptEvents.cardFamily(
            listOf(
                SseEvent.CommandRunStarted("s", "c", "compact", seq = 1),
                SseEvent.MessageUpdated(
                    dev.leonardo.ocbeacon.domain.model.Message.User(
                        id = "seq-1", sessionId = "s",
                        time = dev.leonardo.ocbeacon.domain.model.TimeInfo(created = 1L),
                    )
                ),
                SseEvent.SessionCompacted("s"),
                SseEvent.SurfaceRangeReplaced("s", 1, 2, "seq-3", seq = 3),
            )
        )
        assertEquals(2, family.size)
        assertTrue(family.all { it is SseEvent.CommandRunStarted || it is SseEvent.SurfaceRangeReplaced })
    }
}
