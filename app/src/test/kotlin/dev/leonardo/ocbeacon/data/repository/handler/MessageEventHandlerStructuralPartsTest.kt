package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.MergeStrategy
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * #442 B案 节奏收编（spec 2026-10-02 §2.1）——数据层发布策略表征测试：
 *
 * 1. 流式 delta 批：`_parts` 热视图照旧累积（既有读点语义零变更），
 *    structuralParts **零发射**（根因二收口），StreamingDeltaBus 发布累积全文；
 * 2. 结构性事件（PartUpdated/upsert 直调/clear 族）：structuralParts 发射 +
 *    终态 part 的 bus 覆盖撤销。
 *
 * 计数器确定性：收集器挂 Unconfined——StateFlow 赋值在测试线程上同步回调。
 */
class MessageEventHandlerStructuralPartsTest {

    private lateinit var handler: MessageEventHandler
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val structuralEmissions = AtomicInteger(0)
    @Volatile private var lastStructural: Map<String, List<Part>>? = null

    @Before
    fun setUp() {
        // 双臂纪律：本类断言旗标开语义（B2/B3 通道）——旗标关臂显式跳过而非假红
        org.junit.Assume.assumeTrue(dev.leonardo.ocbeacon.ui.screens.chat.components.StreamingDeltaBus.enabled)
        handler = MessageEventHandler()
        StreamingDeltaBus.clearAll()
        structuralEmissions.set(0)
        lastStructural = null
        scope.launch {
            handler.structuralParts.collect { map ->
                lastStructural = map
                structuralEmissions.incrementAndGet()
            }
        }
    }

    @After
    fun tearDown() {
        StreamingDeltaBus.clearAll()
        scope.cancel()
    }

    private fun seedMessage(messageId: String, part: Part) {
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(
                Message.Assistant(
                    id = messageId,
                    sessionId = "s1",
                    parentId = "",
                    time = TimeInfo(created = 1000L),
                    modelId = "test-model",
                ),
                listOf(part),
            )),
            MergeStrategy.SSE_PRIORITY,
        )
    }

    private fun delta(delta: String, partId: String = "m1_text_ord_0", field: String = "text") {
        handler.handleMessagePartDelta(SseEvent.MessagePartDelta(
            sessionId = "s1", messageId = "m1", partId = partId, field = field, delta = delta,
        ))
    }

    @Test
    fun `delta flush keeps hot view, silent structural, publishes bus`() {
        motive("根修主断言：流式批只走热视图+bus，structuralParts 零发射——CML-tick 归零前提的数据层侧证明")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        val baseline = structuralEmissions.get()

        delta("lo")
        delta("!")
        handler.forceFlushDeltas()

        // 热视图照旧（isStaleDelta/持久化读点语义零变更）
        assertEquals(
            "Hello!",
            handler.parts.value["m1"]?.firstOrNull { it.id == "m1_text_ord_0" }?.let { (it as Part.Text).text },
        )
        // 结构性视图零发射（根因二：combine 源静默）
        assertEquals(baseline, structuralEmissions.get())
        // bus 快通道携带累积全文（键=落位 part.id）
        assertEquals("Hello!", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
        assertFalse(StreamingDeltaBus.live.value["m1_text_ord_0"]!!.reasoning)
    }

    @Test
    fun `terminal PartUpdated publishes structural and clears bus coverage`() {
        motive("R5 完结换装：text.ended 权威经 structural 过桥 + bus 撤销覆盖——消费端回退参数即终态")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        // text.ended 权威替换（终态：time.end ≠ 0）
        handler.handle(
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello!",
                    time = Part.Text.Time(start = 1L, end = 99L),
                ),
            ),
            "srv",
        )

        assertTrue(structuralEmissions.get() > baseline)
        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
        assertEquals(
            "Hello!",
            (lastStructural?.get("m1")?.firstOrNull { it.id == "m1_text_ord_0" } as Part.Text).text,
        )
    }

    @Test
    fun `upsertMessages direct entry publishes structural and clears bus for incoming parts`() {
        motive("R6 REST 直调入口：合并后发布结构视图 + 撤销触及 part 覆盖（服务端真相优先于陈旧累积）")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello"))

        assertTrue(structuralEmissions.get() > baseline)
        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
    }

    @Test
    fun `clearAll empties hot view, structural and bus`() {
        motive("全清族三视图一致归零——防跨会话陈旧状态残留")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.isNotEmpty())

        handler.clearAll()

        assertTrue(handler.parts.value.isEmpty())
        assertTrue(StreamingDeltaBus.live.value.isEmpty())
    }

    @Test
    fun `delta events through handle do not bridge flushed state into structural`() {
        motive("B6 真机定罪回归锚：flush 后首个 delta 事件不得把累积态过桥——否则结构性静默被击穿（曾致 CML-tick 6/s）")
        // B6 真机定罪回归锚：flush 改热视图后，后续 MessagePartDelta 事件经
        // handle() 分发不得把「已含本批累积」的热视图新值过桥进结构性视图
        //（否则每 flush 后首个 delta 击穿静默 → combine 恢复每批滴答）
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertEquals("Hello", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
        val baseline = structuralEmissions.get()

        handler.handle(
            SseEvent.MessagePartDelta(
                sessionId = "s1", messageId = "m1", partId = "m1_text_ord_0",
                field = "text", delta = "!",
            ),
            "srv",
        )

        assertEquals(baseline, structuralEmissions.get())
        // 热视图不受影响（缓冲照旧）
        assertEquals(
            "Hello",
            handler.parts.value["m1"]?.firstOrNull { it.id == "m1_text_ord_0" }?.let { (it as Part.Text).text },
        )
    }

    @Test
    fun `reasoning deltas publish bus with reasoning flag`() {
        motive("R1 推理必须进范围：reasoning delta 经 bus 带推理标记发布——glm 系 Waiting 期根修的前提通道")
        seedMessage("m1", Part.Reasoning(id = "m1_reasoning_ord_0", sessionId = "s1", messageId = "m1", text = "th"))
        delta("ink", partId = "m1_reasoning_ord_0", field = "reasoning")
        handler.forceFlushDeltas()

        assertEquals("think", StreamingDeltaBus.live.value["m1_reasoning_ord_0"]?.text)
        assertTrue(StreamingDeltaBus.live.value["m1_reasoning_ord_0"]!!.reasoning)
    }

    // ===== 分支补全（2026-10-02 全面覆盖批）：每测首行 motive 输出动机 =====

    private fun motive(msg: String) = println("[MOTIVE] $msg")

    @Test
    fun `PartRemoved 撤销 bus 覆盖并发布结构性视图`() {
        motive("R6 移除路径：part 拆除后 live 覆盖必须撤销，否则陈旧累积遮蔽删除语义（幽灵文本）")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        handler.handle(
            SseEvent.MessagePartRemoved(sessionId = "s1", messageId = "m1", partId = "m1_text_ord_0"),
            "srv",
        )

        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `TimePatch 终态化撤销 bus 覆盖`() {
        motive("DSH block-end 路径（#453）：无 kind 终态化信号同样要撤销覆盖——服务端族多样性不能漏")
        // 派生 id 契约：TimePatch 按 _ord_{ordinal} 后缀定位
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))
        val baseline = structuralEmissions.get()

        handler.handle(
            SseEvent.MessagePartTimePatch(sessionId = "s1", messageId = "m1", ordinal = 0L, endMs = 99L),
            "srv",
        )

        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `非终态 PartUpdated 保留 bus 覆盖（流式连续性）`() {
        motive("流式中 part.updated（元数据补齐）不得误清覆盖——否则 bus 通道间歇失活回退参数路径，根修退化")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.containsKey("m1_text_ord_0"))

        // 非终态（end=null）的权威替换——time.start 补齐族
        handler.handle(
            SseEvent.MessagePartUpdated(
                Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello",
                    time = Part.Text.Time(start = 5L)),
            ),
            "srv",
        )

        // 覆盖保留：流式仍在飞，live 通道不得中断
        assertEquals("Hello", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
    }

    @Test
    fun `REST 合并后下一 flush 以合并基线重发布（连续性闭环）`() {
        motive("R5/R6 连续性闭环：upsert 清覆盖后流仍在飞时，下一 flush 以合并后基线重建 bus——防永久失活")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        // REST 刷新落库合并（服务端权威文本=Hello!）
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hello!"))
        assertNull(StreamingDeltaBus.live.value["m1_text_ord_0"])

        // 流仍在飞：后续 delta 在合并基线上续涨 → bus 重建
        delta(" World")
        handler.forceFlushDeltas()

        assertEquals("Hello! World", StreamingDeltaBus.live.value["m1_text_ord_0"]?.text)
    }

    @Test
    fun `clearForSession 清 bus 覆盖并发布结构性视图`() {
        motive("R6 会话清理：切数据/删会话后残留覆盖会跨会话泄漏陈旧文本——先于热视图移除捕获 part ids")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        delta("lo")
        handler.forceFlushDeltas()
        assertTrue(StreamingDeltaBus.live.value.isNotEmpty())
        val baseline = structuralEmissions.get()

        handler.clearForSession("s1")

        assertTrue(StreamingDeltaBus.live.value.isEmpty())
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `patchToolChildSession 变更发布结构性且无变更补丁零发射`() {
        motive("直调入口族（dispatch 外写点，#216）：子会话补丁真实变更必须过桥——漏发布=UI 永不追平；无匹配 part 的 no-op 补丁经值相等去重零发射（防无意义重组，同 publishStructural 幂等语义）")
        seedMessage("m1", Part.Tool(
            id = "tp", sessionId = "s1", messageId = "m1", callId = "call_x", tool = "task",
            state = dev.leonardo.ocbeacon.domain.model.ToolState.Running(),
        ))
        val baseline = structuralEmissions.get()

        // no-op（callId 无匹配）→ 热视图未变 → 值相等去重零发射
        handler.patchToolChildSession("s1", "ghost", "ses_c")
        assertEquals(baseline, structuralEmissions.get())

        // 真实变更（Running 工具卡补子会话元数据）→ 结构性发布
        handler.patchToolChildSession("s1", "call_x", "ses_child")
        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `pruneRevertedMessages 发布结构性视图`() {
        motive("撤销裁剪（EventDispatcher.clearRevert 路径）：裁剪后 UI 必须立即收缩——否则已撤销消息短暂重现")
        seedMessage("m1", Part.Text(id = "m1_text_ord_0", sessionId = "s1", messageId = "m1", text = "Hel"))
        val baseline = structuralEmissions.get()

        handler.pruneRevertedMessages("s1", revertMessageId = "m0")

        assertTrue(structuralEmissions.get() > baseline)
    }

    @Test
    fun `delta flush births unregistered part bridges structural once`() {
        motive("#501 DSH 线面形态：block-start 空种子被 #230 丢弃、无任何已注册 part——首个 delta 批 applyDelta 兜底出生的 part 必须立即过桥结构视图，否则 B案 UI 在完结前看不到正文（真机定罪：正文完结整段砸出）")
        // DSH 形态：消息存在但 parts 为空（block-start 空种子被 #230 丢弃）
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(
                Message.Assistant(
                    id = "m1",
                    sessionId = "s1",
                    parentId = "",
                    time = TimeInfo(created = 1000L),
                    modelId = "test-model",
                ),
                emptyList(),
            )),
            MergeStrategy.SSE_PRIORITY,
        )
        val baseline = structuralEmissions.get()

        delta("# TC", partId = "m1_text_ord_1")
        handler.forceFlushDeltas()

        // 出生即过桥：结构性视图包含新 part（UI 可渲染其 PartContent）
        assertTrue(structuralEmissions.get() > baseline)
        assertEquals(
            "# TC",
            (lastStructural?.get("m1")?.firstOrNull { it.id == "m1_text_ord_1" } as Part.Text).text,
        )

        // 出生后纯文本增长：结构性静默不破（增长只走 bus）
        val afterBirth = structuralEmissions.get()
        delta("P handshake", partId = "m1_text_ord_1")
        handler.forceFlushDeltas()
        assertEquals(afterBirth, structuralEmissions.get())
        assertEquals("# TCP handshake", StreamingDeltaBus.live.value["m1_text_ord_1"]?.text)
    }

    @Test
    fun `two parts born in one flush bridge with single structural emission`() {
        motive("#501 出生过桥的低频性：同批多 part 出生（reasoning+text 并行）只发一次结构事件——组合侧每轮一次而非每 part 一次")
        handler.upsertMessages(
            "s1",
            listOf(MessageWithParts(
                Message.Assistant(
                    id = "m1",
                    sessionId = "s1",
                    parentId = "",
                    time = TimeInfo(created = 1000L),
                    modelId = "test-model",
                ),
                emptyList(),
            )),
            MergeStrategy.SSE_PRIORITY,
        )
        val baseline = structuralEmissions.get()

        delta("think", partId = "m1_reasoning_ord_0", field = "reasoning")
        delta("answer", partId = "m1_text_ord_1")
        handler.forceFlushDeltas()

        assertEquals(baseline + 1, structuralEmissions.get())
        assertEquals(2, lastStructural?.get("m1")?.size)
    }

    // ===== #513（2026-10-05）：V1 空种子注册族 first-text 过桥 =====

    @Test
    fun `empty-seed server part first text bridges structural once`() {
        motive("#513 V1 线面形态：part.updated 空文本种子（服务器 prt_ id，非派生序号 id）经 Add 直接注册为空 part——首次落文本必须过桥结构视图一次，否则渲染条目整个流式期不存在（真机三层定罪：bus live 流转正常而 liveFor 订阅迟至完结才建立、正文完结砸出）")
        // V1 形态：服务器 id（非 _ord_ 派生）空种子 → #230 不弃（仅弃派生序号 id），Add 注册
        handler.handle(
            SseEvent.MessagePartUpdated(
                Part.Text(id = "prt_seed_v1", sessionId = "s1", messageId = "m1", text = ""),
            ),
            "srv",
        )
        // 空种子已注册在热视图（出生即有，非 #501 的 flush 兜底出生族）
        assertTrue(handler.parts.value["m1"]!!.any { it.id == "prt_seed_v1" })
        val baseline = structuralEmissions.get()

        delta("你", partId = "prt_seed_v1")
        handler.forceFlushDeltas()

        // 首文本过桥：结构性视图携带非空文本（渲染条目存在性在此翻转——
        // 装配/PartContent 的 isNotBlank 门自此可过，liveFor 订阅建立）
        assertTrue(structuralEmissions.get() > baseline)
        assertEquals(
            "你",
            (lastStructural?.get("m1")?.firstOrNull { it.id == "prt_seed_v1" } as Part.Text).text,
        )
        assertEquals("你", StreamingDeltaBus.live.value["prt_seed_v1"]?.text)

        // 其后纯文本增长：结构性静默不破（增长只走 bus——CML-tick≈0 前提保持）
        val afterFirst = structuralEmissions.get()
        delta("好", partId = "prt_seed_v1")
        handler.forceFlushDeltas()
        assertEquals(afterFirst, structuralEmissions.get())
        assertEquals("你好", StreamingDeltaBus.live.value["prt_seed_v1"]?.text)
    }

    @Test
    fun `empty-seed reasoning first text bridges structural`() {
        motive("#513 推理先行轮同症：空 reasoning 种子（glm 系 Waiting 期流式）首文本同样过桥——渲染条目存在性门（isNotBlank）与正文分支同构，漏桥同形冻结")
        handler.handle(
            SseEvent.MessagePartUpdated(
                Part.Reasoning(id = "prt_reason_seed", sessionId = "s1", messageId = "m1", text = ""),
            ),
            "srv",
        )
        val baseline = structuralEmissions.get()

        delta("思", partId = "prt_reason_seed", field = "reasoning")
        handler.forceFlushDeltas()

        assertTrue(structuralEmissions.get() > baseline)
        assertEquals("思", StreamingDeltaBus.live.value["prt_reason_seed"]?.text)
        assertTrue(StreamingDeltaBus.live.value["prt_reason_seed"]!!.reasoning)
        // 过桥后继续静默增长（推理流长于正文是常态）
        val afterFirst = structuralEmissions.get()
        delta("考", partId = "prt_reason_seed", field = "reasoning")
        handler.forceFlushDeltas()
        assertEquals(afterFirst, structuralEmissions.get())
        assertEquals("思考", StreamingDeltaBus.live.value["prt_reason_seed"]?.text)
    }
}
