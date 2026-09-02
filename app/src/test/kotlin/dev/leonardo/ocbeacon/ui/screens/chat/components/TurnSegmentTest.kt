package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderableTurn
import dev.leonardo.ocbeacon.ui.screens.chat.tools.computeRenderableTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #258 Stage B：TurnSegmentPlan 纯函数契约（spec 2026-09-02 §2.1/§3）。
 * 覆盖：权重门槛、part 数量主导 turn 的多段切割 + 段内 item 上限、巨型 part
 * 独立成段、尾段兜底合并、骨架装配降级、发射表（键序/钉扎/互斥/指纹陈旧）。
 *
 * fixture 尺寸注：巨型文本须同时满足「≥3000 字符（giant 门槛）」与
 * 「turn 总权重 ≥12000」——150 块 × ~90 字符 ≈ 13.2K（+200 包装）。
 */
class TurnSegmentTest {

    private fun assistantMsg(id: String, parts: List<Part>) = ChatMessage(
        message = Message.Assistant(id = id, sessionId = "s1", time = TimeInfo(2), parentId = "p0"),
        parts = parts,
    )

    private fun tool(i: Int) = Part.Tool(
        id = "t$i", sessionId = "s1", messageId = "m1", callId = "c$i",
        tool = if (i % 2 == 0) "read" else "bash",
        state = ToolState.Pending(input = emptyMap()),
    )

    private fun text(i: Int, len: Int) = Part.Text(
        id = "x$i", sessionId = "s1", messageId = "m1",
        text = "段落" + i + "：" + "内容填充".repeat(len / 4),
    )

    /** 150 块 × ~90 字符 ≈ 13.2K——单巨型 part（权重达标且 ≥CHUNK_MIN_CHARS）。 */
    private val giantBody: String =
        (1..150).joinToString("\n\n") { "段 $it：" + "内容".repeat(40) }

    private val giantDoc: String =
        (1..150).joinToString("\n\n") { "## 段 $it\n\n" + "内容".repeat(40) }

    private fun renderable(cm: ChatMessage): RenderableTurn = computeRenderableTurn(
        turnMessages = listOf(cm),
        currentMessage = cm,
        isTurnLast = true,
        formatError = { null },
    )

    // ---------- computeTurnSegments ----------

    @Test
    fun shortTurnBelowWeightThresholdReturnsNull() {
        val cm = assistantMsg("m1", listOf(text(0, 500), tool(0), text(1, 300)))
        assertNull(computeTurnSegments("t_m1", "m1", 1, renderable(cm)))
    }

    @Test
    fun middleBandTurnWithGiantBypassesWeightGate() {
        // #300①：巨型 part（3.4K）+ 少量小 part（总权重 ~5K < 12000）——旧版落
        // null（走旧 MdChunkPlan 粗片），现应豁免门槛产出 GiantHole 骨架
        val giant = Part.Text(
            id = "g0", sessionId = "s1", messageId = "m1",
            text = (1..42).joinToString("\n\n") { "段 $it：" + "内容".repeat(40) },
        )
        val parts = listOf(text(0, 400), giant, text(1, 400))
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))
        assertNotNull("中间带 turn（有巨型）应豁免权重门槛", sk)
        val holes = sk!!.cuts.filterIsInstance<TurnSegmentSkeleton.GiantHole>()
        assertEquals(1, holes.size)
    }

    @Test
    fun partCountHeavyTurnSegmentsWithItemCap() {
        // 30 个 tool part（550 当量/个 = 16500 ≥ 12000 门槛）——part 数量主导形态
        val parts = (0 until 30).map { tool(it) }
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))
        assertNotNull(sk)
        val itemsSegments = sk!!.cuts.filterIsInstance<TurnSegmentSkeleton.Items>()
        // 段内 item 数上限：30 tool / 上限 10 → 至少 3 段
        assertTrue(itemsSegments.size >= 3)
        // 段连续覆盖全部 renderItems（无缺口/无重叠）
        var expected = 0
        for (seg in itemsSegments) {
            assertEquals(expected, seg.from)
            expected = seg.to
        }
        assertEquals(30, expected)
    }

    @Test
    fun giantPartIsolatedIntoHole() {
        val giant = Part.Text(id = "g0", sessionId = "s1", messageId = "m1", text = giantBody)
        val parts = listOf(text(0, 400), giant, text(1, 400))
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))!!
        val holes = sk.cuts.filterIsInstance<TurnSegmentSkeleton.GiantHole>()
        assertEquals(1, holes.size)
        assertEquals("g0", holes[0].partId)
        assertEquals(giant.text.length, holes[0].text.length)
        // 巨型 part 前后各一段 Items（骨架顺序：Items, GiantHole, Items）
        assertTrue(sk.cuts.first() is TurnSegmentSkeleton.Items)
        assertTrue(sk.cuts.last() is TurnSegmentSkeleton.Items)
    }

    @Test
    fun singleGiantOnlyTurnYieldsJustHole() {
        val giant = Part.Text(id = "g0", sessionId = "s1", messageId = "m1", text = giantBody)
        val cm = assistantMsg("m1", listOf(giant))
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))
        assertNotNull(sk)
        assertEquals(1, sk!!.cuts.size)
        assertTrue(sk.cuts[0] is TurnSegmentSkeleton.GiantHole)
    }

    @Test
    fun tailSingleItemMergesIntoPreviousItemsSegment() {
        // 9 个 ~1400 字符 text（权重 ~1600/个）：累计 ~3200 ≥ 目标 3000 → 每 2 个一段；
        // 末段 [8] 单 item → 兜底合并回 [6,7] 段 → [6,9)。总权重 ~14.4K ≥ 门槛。
        val parts = (0 until 9).map { text(it, 1400) }
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))!!
        val segs = sk.cuts.filterIsInstance<TurnSegmentSkeleton.Items>()
        assertEquals(4, segs.size)
        assertEquals(0 to 2, segs[0].from to segs[0].to)
        assertEquals(2 to 4, segs[1].from to segs[1].to)
        assertEquals(4 to 6, segs[2].from to segs[2].to)
        assertEquals(6 to 9, segs[3].from to segs[3].to)
    }

    // ---------- buildPlan ----------

    @Test
    fun buildPlanDegradedGiantBecomesSingleItemSegment() {
        val giant = Part.Text(id = "g0", sessionId = "s1", messageId = "m1", text = giantBody)
        val parts = listOf(text(0, 400), giant, text(1, 400))
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))!!
        // 无 giantPlans（解析失败/降级）→ GiantHole 变单 item Items 段
        val plan = sk.buildPlan(emptyList())
        assertEquals(3, plan.segments.size)
        assertTrue(plan.segments[1] is TurnSegmentPlan.Segment.Items)
        assertEquals(3, plan.chunkCount)
    }

    @Test
    fun buildPlanWithGiantMdChunkPlanExpandsRanges() = runBlocking(Dispatchers.Default) {
        val giant = Part.Text(id = "g0", sessionId = "s1", messageId = "m1", text = giantDoc)
        val parts = listOf(text(0, 400), giant, text(1, 400))
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 1, renderable(cm))!!
        val st = parse(giantDoc)
        val md = computeChunkPlan(
            "g0", st,
            RenderSupplyCoordinator.CHUNK_MIN_CHARS,
            RenderSupplyCoordinator.CHUNK_TARGET_CHARS,
        )!!
        val plan = sk.buildPlan(listOf(md))
        val giantSeg = plan.segments[1] as TurnSegmentPlan.Segment.Giant
        assertEquals(md.ranges.size, giantSeg.chunkCount)
        assertEquals(md.ranges.size + 2, plan.chunkCount)
    }

    private suspend fun parse(text: String): com.mikepenz.markdown.model.State.Success {
        val normalized = dev.leonardo.ocbeacon.ui.screens.chat.markdown.normalizeForRender(text, isUser = false)
        return withTimeout(10_000) {
            com.mikepenz.markdown.model.parseMarkdownFlow(normalized).first {
                it is com.mikepenz.markdown.model.State.Success
            }
        } as com.mikepenz.markdown.model.State.Success
    }

    // ---------- buildChatEntries 集成 ----------

    @Test
    fun turnChunksEmitTailFirstAndPinHead() {
        val parts = (0 until 30).map { tool(it) }
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", turnPlanFingerprint(cm, listOf(cm)), renderable(cm))!!
        val plan = sk.buildPlan(emptyList())
        val chat = buildChatEntries(
            displayItems = listOf(0 to cm),
            turnGroups = mapOf(0 to listOf(cm)),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            segmentPlans = mapOf("t_m1" to plan),
        )
        val count = plan.chunkCount
        val keys = chat.entries.map { it.key }
        assertEquals((count - 1 downTo 0).map { "t_m1#s" + it }, keys)
        // displayEntryStart 钉在头片（含标签栏——跳转落点语义）
        assertEquals(count - 1, chat.displayEntryStart[0])
    }

    @Test
    fun mdChunkPlanTakesPrecedenceOverSegmentPlan() = runBlocking(Dispatchers.Default) {
        val giant = Part.Text(id = "g0", sessionId = "s1", messageId = "m1", text = giantDoc)
        val cm = assistantMsg("m1", listOf(giant))
        val st = parse(giantDoc)
        val md = computeChunkPlan(
            "g0", st,
            RenderSupplyCoordinator.CHUNK_MIN_CHARS,
            RenderSupplyCoordinator.CHUNK_TARGET_CHARS,
        )!!
        val sk = computeTurnSegments("t_m1", "m1", turnPlanFingerprint(cm, listOf(cm)), renderable(cm))!!
        val plan = sk.buildPlan(listOf(md))
        val chat = buildChatEntries(
            displayItems = listOf(0 to cm),
            turnGroups = mapOf(0 to listOf(cm)),
            streamingMsgId = null,
            chunkPlans = mapOf("g0" to md),
            recentStreamedTurnKeys = emptySet(),
            segmentPlans = mapOf("t_m1" to plan),
        )
        // 旧路径胜出：全部条目为 #c（MdChunkPlan），无 #s
        assertTrue(chat.entries.all { it.key.contains("#c") })
        assertTrue(chat.entries.none { it.key.contains("#s") })
    }

    @Test
    fun staleFingerprintDropsSegmentPlan() {
        val parts = (0 until 30).map { tool(it) }
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", 12345, renderable(cm))!!
        val plan = sk.buildPlan(emptyList())
        val chat = buildChatEntries(
            displayItems = listOf(0 to cm),
            turnGroups = mapOf(0 to listOf(cm)),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            segmentPlans = mapOf("t_m1" to plan),
        )
        // 指纹失配（12345 ≠ 现时）→ 弃置，回落整 turn 单 item
        assertEquals(1, chat.entries.size)
        assertTrue(chat.entries[0] is ChatEntry.Turn)
    }

    @Test
    fun streamingAndRecentStreamedTurnsExcluded() {
        val parts = (0 until 30).map { tool(it) }
        val cm = assistantMsg("m1", parts)
        val sk = computeTurnSegments("t_m1", "m1", turnPlanFingerprint(cm, listOf(cm)), renderable(cm))!!
        val plan = sk.buildPlan(emptyList())
        for (streaming in listOf("m1", null)) {
            val chat = buildChatEntries(
                displayItems = listOf(0 to cm),
                turnGroups = mapOf(0 to listOf(cm)),
                streamingMsgId = streaming,
                chunkPlans = emptyMap(),
                recentStreamedTurnKeys = if (streaming == null) setOf("t_m1") else emptySet(),
                segmentPlans = mapOf("t_m1" to plan),
            )
            assertEquals(1, chat.entries.size)
            assertTrue(chat.entries[0] is ChatEntry.Turn)
        }
    }
}
