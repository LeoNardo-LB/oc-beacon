package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片7：按代事件词汇表 seam（[DshEventVocabulary]）。
 *
 * 断言：V3 = V2 超集；会话格式版本择表；未知词汇按代降级（V2 视角的 V3 类型
 * 落 UNKNOWN_DEGRADED，V3 视角落具名 SESSION_FORMAT_V3）；历史折叠按 session
 * 头 version 择表。共享处理器（[DshEventMapper] 的映射分支）不受影响。
 */
class DshEventVocabularyTest {

    private fun env(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `v3 vocabulary is a superset of v2`() {
        // V2 共有的已知忽略
        assertEquals(DshIgnoreReason.LOG_ONLY, DshEventVocabulary.V2.ignoreReason("model/selection"))
        assertEquals(DshIgnoreReason.LOG_ONLY, DshEventVocabulary.V3.ignoreReason("model/selection"))
        assertEquals(DshIgnoreReason.STREAM_ERROR, DshEventVocabulary.V2.ignoreReason("stream/error"))
        // V3 新增：V2 未知 / V3 具名
        assertNull(DshEventVocabulary.V2.ignoreReason("assistant/attempt"))
        assertEquals(DshIgnoreReason.SESSION_FORMAT_V3, DshEventVocabulary.V3.ignoreReason("assistant/attempt"))
        assertNull(DshEventVocabulary.V2.ignoreReason("subagent/catalog"))
        assertEquals(DshIgnoreReason.SESSION_FORMAT_V3, DshEventVocabulary.V3.ignoreReason("subagent/catalog"))
        // 有映射的类型不入忽略表（否则会遮蔽共享处理器分支）
        assertNull(DshEventVocabulary.V3.ignoreReason("system/message"))
        assertNull(DshEventVocabulary.V3.ignoreReason("deliverables/presented"))
        assertNull(DshEventVocabulary.V3.ignoreReason("tool/ptc-dispatch"))
        assertNull(DshEventVocabulary.V3.ignoreReason("llm/retry"))
    }

    @Test
    fun `session format version selects the vocabulary with tolerant fallback`() {
        assertSame(DshEventVocabulary.V2, DshEventVocabulary.ofSessionFormatVersion(1L))
        assertSame(DshEventVocabulary.V2, DshEventVocabulary.ofSessionFormatVersion(2L))
        assertSame(DshEventVocabulary.V3, DshEventVocabulary.ofSessionFormatVersion(3L))
        // 缺席 / 未知版本 → CURRENT（容错优先）
        assertSame(DshEventVocabulary.CURRENT, DshEventVocabulary.ofSessionFormatVersion(null))
        assertSame(DshEventVocabulary.CURRENT, DshEventVocabulary.ofSessionFormatVersion(9L))
    }

    @Test
    fun `unknown vocabulary degrades by the selected generation`() {
        val row = env("""{"type":"assistant/attempt","seq":1,"time":1,"data":{}}""")
        assertEquals(
            listOf(DshMappedEvent.Ignored(DshIgnoreReason.UNKNOWN_DEGRADED)),
            DshEventMapper.mapSessionEvent("s1", row, DshEventVocabulary.V2),
        )
        assertEquals(
            listOf(DshMappedEvent.Ignored(DshIgnoreReason.SESSION_FORMAT_V3)),
            DshEventMapper.mapSessionEvent("s1", row, DshEventVocabulary.V3),
        )
    }

    @Test
    fun `history fold tolerates generation-specific ignored vocabulary`() {
        // V3 头 + V3 已知忽略类型：折叠无 SSE，水位推进，绝不拒绝重建
        val fold3 = DshHistoryFolder.fold(
            listOf(
                env("""{"type":"session","version":3,"id":"s3","createdAt":1,"cwd":"/w"}"""),
                env("""{"type":"assistant/attempt","seq":7,"time":1,"data":{}}"""),
            )
        )
        assertTrue(fold3.sseEvents.isEmpty())
        assertEquals(false, fold3.refusedRebuild)
        assertEquals(7L, fold3.lastSeq)

        // V2 头 + V3 类型（V2 眼中未知）→ UNKNOWN_DEGRADED，同样不拒绝重建
        val fold2 = DshHistoryFolder.fold(
            listOf(
                env("""{"type":"session","version":2,"id":"s2","createdAt":1,"cwd":"/w"}"""),
                env("""{"type":"assistant/attempt","seq":7,"time":1,"data":{}}"""),
            )
        )
        assertTrue(fold2.sseEvents.isEmpty())
        assertEquals(false, fold2.refusedRebuild)
        assertEquals(7L, fold2.lastSeq)
    }
}
