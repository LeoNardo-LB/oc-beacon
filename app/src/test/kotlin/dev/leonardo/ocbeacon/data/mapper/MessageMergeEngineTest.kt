package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.domain.model.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MessageMergeEngine 直测（#234 战役一）。
 *
 * 与 handler 侧测试的分工：handler 测试锁「SSE 事件/三策略 upsert → 状态流」
 * 的端到端行为；本文件锁引擎纯函数自身的代数性质——尤其是「零信息 part
 * 不得出现在输出」这条横跨 #223/#228/#229/#230 四个 bug 的不变量。
 */
class MessageMergeEngineTest {

    private fun text(id: String, msgId: String = "m1", text: String = "") =
        Part.Text(id = id, sessionId = "s1", messageId = msgId, text = text)

    private fun reasoning(id: String, msgId: String = "m1", text: String = "") =
        Part.Reasoning(id = id, sessionId = "s1", messageId = msgId, text = text)

    // ============ applyDelta ============

    @Test
    fun `applyDelta appends to registered text part`() {
        val parts = listOf(text("p1", text = "你好"))
        val out = MessageMergeEngine.applyDelta(parts, "p1", "s1", "m1", "text", "世界")
        assertEquals(1, out.size)
        assertEquals("你好世界", (out[0] as Part.Text).text)
    }

    @Test
    fun `applyDelta endsWith dedup does not double-append overlapping delta`() {
        // 48ms 批内同 part 多次 delta 可能携带重叠后缀——endsWith 去重（铁律②配套）
        val parts = listOf(text("p1", text = "你好世界"))
        val out = MessageMergeEngine.applyDelta(parts, "p1", "s1", "m1", "text", "世界")
        assertEquals("你好世界", (out[0] as Part.Text).text)
    }

    @Test
    fun `applyDelta rebuilds unregistered part as text by kind`() {
        // idx<0 兜底重建（#223 已验证机制：空 started 被 #230 丢弃后 delta 重建）
        val out = MessageMergeEngine.applyDelta(emptyList(), "m1_text_ord_0", "s1", "m1", "text", "首段")
        assertEquals(1, out.size)
        assertTrue(out[0] is Part.Text)
        assertEquals("m1_text_ord_0", out[0].id)
        assertEquals("首段", (out[0] as Part.Text).text)
    }

    @Test
    fun `applyDelta rebuilds unregistered part as reasoning by kind`() {
        // #230：kind 错乱封堵——reasoning delta 不再以正文形态复活
        val out = MessageMergeEngine.applyDelta(emptyList(), "m1_reasoning_ord_0", "s1", "m1", "reasoning", "思考")
        assertTrue(out[0] is Part.Reasoning)
        assertEquals("思考", (out[0] as Part.Reasoning).text)
    }

    // ============ inferDeltaKind ============

    @Test
    fun `inferDeltaKind prefers registered part type`() {
        val parts = listOf(reasoning("p1", text = "r"), text("p2", text = "t"))
        assertEquals("reasoning", MessageMergeEngine.inferDeltaKind(parts, "p1"))
        assertEquals("text", MessageMergeEngine.inferDeltaKind(parts, "p2"))
    }

    @Test
    fun `inferDeltaKind falls back to derived id contract`() {
        assertEquals("reasoning", MessageMergeEngine.inferDeltaKind(null, "m1_reasoning_ord_2"))
        assertEquals("text", MessageMergeEngine.inferDeltaKind(null, "m1_text_ord_0"))
        assertEquals("text", MessageMergeEngine.inferDeltaKind(emptyList(), "custom_id"))
    }

    // ============ mergePartsList：零信息 part 不变量 ============

    @Test
    fun `mergePartsList never emits zero-information part - incoming side`() {
        // #228 对称补全：incoming 空 part（Room 炸弹回灌）不得进入输出
        val existing = listOf(text("a", text = "存量A"))
        val incoming = listOf(text("a", text = "存量A"), reasoning("m1_reasoning_ord_5"), reasoning("m1_reasoning_ord_6"))
        val out = MessageMergeEngine.mergePartsList(existing, incoming)
        assertTrue(out.all { !MessageMergeEngine.isEmptyStreamPart(it) })
        assertEquals(1, out.size)
    }

    @Test
    fun `mergePartsList sanitizes existing side when incoming is all empty`() {
        // #228：sanitized 全空时 existing 也滤空（原路径直通 existing，炸弹永生）
        val existing = listOf(text("a", text = "实文"), reasoning("m1_reasoning_ord_0"), text("b", text = ""))
        val incoming = listOf(reasoning("m1_reasoning_ord_9"))
        val out = MessageMergeEngine.mergePartsList(existing, incoming)
        assertEquals(listOf("a"), out.map { it.id })
    }

    @Test
    fun `mergePartsList preserves existing-only SSE accumulated text`() {
        // 2026-08-12 根因：incoming 为空时保留 existing（REST 流式未提交）
        val existing = listOf(text("sse_1", text = "SSE 累积长文本"))
        assertEquals(existing, MessageMergeEngine.mergePartsList(existing, emptyList()))
        // incoming 不含的 SSE 独有 part 追加在后（id 契约不一致保护）
        val incoming = listOf(text("rest_1", text = "REST 文本"))
        val out = MessageMergeEngine.mergePartsList(existing, incoming)
        assertEquals(2, out.size)
        assertEquals("rest_1", out[0].id)
        assertEquals("sse_1", out[1].id)
    }

    // ============ dedupOverlappingTextParts ============

    @Test
    fun `dedup collapses overlapping text keeping longer`() {
        // #109：id 契约演进期间同一逻辑 part 两版本（legacy id="" vs 派生 id）
        val legacy = text("", text = "你好世界的一半")
        val derived = text("m1_text_ord_0", text = "你好世界的一半，后半也是")
        val out = MessageMergeEngine.dedupOverlappingTextParts(listOf(legacy, derived))
        assertEquals(1, out.size)
        assertEquals("m1_text_ord_0", out[0].id)
    }

    @Test
    fun `dedup keeps two distinct derived-contract parts`() {
        // 双侧新版契约 id 不同 = 真不同 part（不折叠）
        val a = text("m1_text_ord_0", text = "第一段")
        val b = text("m1_text_ord_1", text = "第二段完全不同")
        val out = MessageMergeEngine.dedupOverlappingTextParts(listOf(a, b))
        assertEquals(2, out.size)
    }

    // ============ 谓词 ============

    @Test
    fun `predicates classify parts per id contract`() {
        assertTrue(MessageMergeEngine.isNewPartId("m1_text_ord_3"))
        assertTrue(MessageMergeEngine.isNewPartId("m1_reasoning_ord_0"))
        assertFalse(MessageMergeEngine.isNewPartId(""))
        assertFalse(MessageMergeEngine.isNewPartId("p1"))
        assertTrue(MessageMergeEngine.isEmptyStreamPart(text("x")))
        assertFalse(MessageMergeEngine.isEmptyStreamPart(text("x", text = "有字")))
        assertTrue(MessageMergeEngine.sameStreamKind(text("a"), text("b")))
        assertFalse(MessageMergeEngine.sameStreamKind(text("a"), reasoning("b")))
    }
}
