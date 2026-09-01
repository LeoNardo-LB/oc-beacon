package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.data.mapper.MessageMergeEngine.PartRegistration
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

    @Test
    fun `applyDelta drops stale delta already contained in existing text part - #265`() {
        // #265 E2E 竞态：完结全量替换（partId 换代）后，批缓冲滞留的尾 delta
        // 才 flush——内容已在权威文本里，新建 part 会导致结尾句渲染两遍。
        // #266 收窄：包含判定只对终态 part 生效（完结替换后 part 必为终态）。
        val existing = Part.Text(
            id = "m1_text_ord_1", sessionId = "s1", messageId = "m1",
            text = "# 标题\n\n正文……a reminder that the most powerful forces never stop.",
            time = Part.Text.Time(start = 1, end = 2),
        )
        val out = MessageMergeEngine.applyDelta(
            listOf(existing), "m1_text_ord_9", "s1", "m1", "text",
            "reminder that the most powerful forces never stop."
        )
        assertEquals(1, out.size)
        assertEquals("m1_text_ord_1", out[0].id)
    }

    @Test
    fun `applyDelta drops stale reasoning delta already contained - #265`() {
        val existing = Part.Reasoning(
            id = "m1_reasoning_ord_0", sessionId = "s1", messageId = "m1",
            text = "思考过程尾部", time = Part.Reasoning.Time(start = 1, end = 2),
        )
        val out = MessageMergeEngine.applyDelta(
            listOf(existing), "m1_reasoning_ord_9", "s1", "m1", "reasoning", "过程尾部"
        )
        assertEquals(1, out.size)
        assertEquals("思考过程尾部", (out[0] as Part.Reasoning).text)
    }

    @Test
    fun `applyDelta still rebuilds unregistered when delta not contained - #223 preserved`() {
        // #223 语义保留：内容从未到达（不在任何既有 part 中）→ 照常重建
        val existing = listOf(text("m1_text_ord_1", text = "存量正文"))
        val out = MessageMergeEngine.applyDelta(existing, "m1_text_ord_9", "s1", "m1", "text", "全新的增量内容")
        assertEquals(2, out.size)
        assertEquals("全新的增量内容", (out[1] as Part.Text).text)
    }

    // ============ 终态守卫 + 身份回填（#266，2026-08-30 真机 E2E 定性）============

    @Test
    fun `applyDelta drops any delta after part reached terminal full value - #266`() {
        // text.ended 全量值是 replayable boundary（官方 session-event.ts 语义）：
        // 其后的 delta 必为过期重放或服务器截断残留（真机 E2E 实证：模型尾部
        // 自重复被服务器截断，滞留 delta 走 idx>=0 盲拼接 → 尾段渲染两遍）。
        // 终态 part 一律不再接收 delta——idx>=0 路径的守卫补齐。
        val terminal = Part.Text(
            id = "m1_text_ord_0", sessionId = "s1", messageId = "m1",
            text = "正文。权威结尾。", time = Part.Text.Time(start = 1, end = 2),
        )
        val out = MessageMergeEngine.applyDelta(
            listOf(terminal), "m1_text_ord_0", "s1", "m1", "text", "答案。多余尾段。"
        )
        assertEquals("正文。权威结尾。", (out.single() as Part.Text).text)
    }

    @Test
    fun `applyDelta drops delta after reasoning reached terminal - #266`() {
        val terminal = Part.Reasoning(
            id = "m1_reasoning_ord_0", sessionId = "s1", messageId = "m1",
            text = "思考完毕", time = Part.Reasoning.Time(start = 1, end = 2),
        )
        val out = MessageMergeEngine.applyDelta(
            listOf(terminal), "m1_reasoning_ord_0", "s1", "m1", "reasoning", "补充思考"
        )
        assertEquals("思考完毕", (out.single() as Part.Reasoning).text)
    }

    @Test
    fun `applyDelta reasoning endsWith dedup mirrors text branch - #266`() {
        // 顺带项：reasoning 注册 append 与 text 同款 endsWith 去重（此前盲拼接）
        val parts = listOf(reasoning("p1", text = "思考过程"))
        val out = MessageMergeEngine.applyDelta(parts, "p1", "s1", "m1", "reasoning", "过程")
        assertEquals("思考过程", (out[0] as Part.Reasoning).text)
    }

    @Test
    fun `applyDelta contains guard narrowed to terminal parts - midstream repeat rebuilds - #266`() {
        // 误伤面消除（#266 权衡裁决）：流式中（无终态 part）未注册 delta 内容
        // 恰与既有 part 重叠 = 合法新 part 的首段重复——不得丢弃（丢弃即内容
        // 丢失）。包含判过期只对终态（ended 全量值）part 生效。
        val existing = text("m1_text_ord_0", text = "今天天气很好")
        val out = MessageMergeEngine.applyDelta(listOf(existing), "m1_text_ord_9", "s1", "m1", "text", "天气很好")
        assertEquals(2, out.size)
        assertEquals("天气很好", (out[1] as Part.Text).text)
    }

    @Test
    fun `applyDelta still drops stale delta contained in terminal part - #266`() {
        // 终态包含判定保留：完结替换后的滞留 delta（partId 换代未注册）照旧丢弃
        val terminal = Part.Text(
            id = "m1_text_ord_1", sessionId = "s1", messageId = "m1",
            text = "存量正文包含片段", time = Part.Text.Time(start = 1, end = 2),
        )
        val out = MessageMergeEngine.applyDelta(
            listOf(terminal), "m1_text_ord_9", "s1", "m1", "text", "包含片段"
        )
        assertEquals(1, out.size)
        assertEquals("m1_text_ord_1", out[0].id)
    }

    @Test
    fun `mergePart backfills streaming derived id on authoritative replace - #266`() {
        // 权威替换按内容回填派生 id：part 身份跨完结保持稳定——#246 锚点、
        // Room 行键（upsertParts 只 REPLACE 不删缺席行，改名即产孤儿行）、
        // 未来一切 partId 键控逻辑不再站在漂移面上。
        val existing = text("m1_text_ord_0", text = "部分文本")
        val incoming = Part.Text(
            id = "prt_srv_1", sessionId = "s1", messageId = "m1",
            text = "部分文本。权威全文。", time = Part.Text.Time(start = 1, end = 2),
        )
        val merged = MessageMergeEngine.mergePart(existing, incoming)
        assertEquals("m1_text_ord_0", merged.id)
        assertEquals("部分文本。权威全文。", (merged as Part.Text).text)
    }

    @Test
    fun `mergePart backfills derived id for reasoning too - #266`() {
        val existing = reasoning("m1_reasoning_ord_0", text = "思考前半")
        val incoming = Part.Reasoning(
            id = "prt_rsrv_1", sessionId = "s1", messageId = "m1",
            text = "思考前半+后半", time = Part.Reasoning.Time(start = 1, end = 2),
        )
        val merged = MessageMergeEngine.mergePart(existing, incoming)
        assertEquals("m1_reasoning_ord_0", merged.id)
    }

    @Test
    fun `mergePart adopts incoming id only when existing id blank`() {
        // legacy 空 id 行（#109 时代 Room 数据 / REST id="" 契约）：existing 无
        // 身份可回填 → 采 incoming id（原行为保留）
        val existing = text("", text = "Got it.")
        val merged = MessageMergeEngine.mergePart(existing, text("prt_srv_1", text = "Got it. 全文"))
        assertEquals("prt_srv_1", merged.id)
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
        assertTrue(MessageMergeEngine.isDerivedOrdinalId("m1_text_ord_3"))
        assertTrue(MessageMergeEngine.isDerivedOrdinalId("m1_reasoning_ord_0"))
        assertFalse(MessageMergeEngine.isDerivedOrdinalId(""))
        assertFalse(MessageMergeEngine.isDerivedOrdinalId("p1"))
        assertTrue(MessageMergeEngine.isEmptyStreamPart(text("x")))
        assertFalse(MessageMergeEngine.isEmptyStreamPart(text("x", text = "有字")))
        assertTrue(MessageMergeEngine.sameStreamKind(text("a"), text("b")))
        assertFalse(MessageMergeEngine.sameStreamKind(text("a"), reasoning("b")))
    }

    // ============ resolvePartRegistration（#234 战役二）============

    @Test
    fun `registration merges by id when present`() {
        val parts = listOf(text("p1", text = "短"))
        val d = MessageMergeEngine.resolvePartRegistration(parts, text("p1", text = "更长文本"))
        assertEquals(PartRegistration.MergeAt(0), d)
    }

    @Test
    fun `registration merges blank-id text by content - #87b`() {
        val parts = listOf(text("sse_1", text = "Got it."))
        val d = MessageMergeEngine.resolvePartRegistration(parts, text("", text = "Got it."))
        assertEquals(PartRegistration.MergeByContent(0), d)
    }

    @Test
    fun `registration drops derived same-kind empty started duplicate - #223`() {
        val parts = listOf(reasoning("m1_reasoning_ord_0"))
        val d = MessageMergeEngine.resolvePartRegistration(parts, reasoning("m1_reasoning_ord_1"))
        assertEquals(PartRegistration.DropZeroInfoDuplicate, d)
    }

    @Test
    fun `registration drops first derived empty started - #230`() {
        val d = MessageMergeEngine.resolvePartRegistration(emptyList(), reasoning("m1_reasoning_ord_0"))
        assertEquals(PartRegistration.DropZeroInfo, d)
    }

    @Test
    fun `registration keeps custom-id empty parts - #223 exception`() {
        // 自定义 id 的两个空 part 可能 legitimately 不同——不折叠、不丢弃
        val parts = listOf(reasoning("p1"))
        val d = MessageMergeEngine.resolvePartRegistration(parts, reasoning("p2"))
        assertTrue(d is PartRegistration.Add)
        assertEquals("p2", (d as PartRegistration.Add).part.id)
    }

    @Test
    fun `sanitized strips zero-info parts for direct-write paths`() {
        val out = MessageMergeEngine.sanitized(listOf(reasoning("r1"), text("t1", text = "有字")))
        assertEquals(listOf("t1"), out.map { it.id })
    }

    // ============ File url 客户端补丁保留（#295 残项 2026-09-02） ============

    private fun filePart(id: String, url: String? = null) =
        Part.File(id = id, sessionId = "s1", messageId = "m1", mime = "image/png", filename = "shot.png", url = url)

    /** 服务器重刷（url=null）不得抹掉客户端 patch 的 data-url——否则缩略图退回文件 chip。 */
    @Test
    fun `mergePart keeps patched file url when incoming lacks it`() {
        val existing = filePart("f1", url = "data:image/png;base64,AAA")
        val incoming = filePart("f1") // REST/回放快照：url 恒 null
        val merged = MessageMergeEngine.mergePart(existing, incoming)
        assertEquals("data:image/png;base64,AAA", (merged as Part.File).url)
        assertEquals("image/png", merged.mime)
    }

    /** incoming 自带 url（V1/V2 REST 可携带）→ incoming 权威。 */
    @Test
    fun `mergePart incoming file url wins over existing`() {
        val existing = filePart("f1", url = "data:image/png;base64,OLD")
        val incoming = filePart("f1", url = "https://cdn/real.png")
        val merged = MessageMergeEngine.mergePart(existing, incoming)
        assertEquals("https://cdn/real.png", (merged as Part.File).url)
    }

    /** 端到端：mergePartsList 重刷路径（REST_AUTHORITY/SSE_PRIORITY 共用 mergePart）。 */
    @Test
    fun `mergePartsList preserves patched file url across rest refresh`() {
        val existing = listOf(filePart("f1", url = "data:image/png;base64,AAA"))
        val incoming = listOf(filePart("f1"))
        val out = MessageMergeEngine.mergePartsList(existing, incoming)
        assertEquals("data:image/png;base64,AAA", (out.single() as Part.File).url)
    }
}
