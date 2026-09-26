package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #435 统一配对规则与流式增长账本单测。
 *
 * 取代 DeferredRevealCompensatorTest/RevealCompensatorsTest(#222 延迟揭示状态机
 * 随 COMP 家族退役)。锁住「锚即意图」统一规则的四构型与账本协议:
 * 贴底跟随族/读历史免派发(震荡根源构造性消失),尾段阅读 +Δ 同帧配对
 * (旧 COMP offset+delta 语义的引擎化等价)。
 */
class StreamingGrowLedgerTest {

    // ---- StreamingPairingRule:统一规则四构型 ----

    @Test
    fun rule_pinnedBottom_appendFamily_noDispatch() {
        // 贴底跟随族:锚=0(banner 区),增长源=消息 item 7——追加语义,免派发
        assertEquals(0f, StreamingAnchorRule.pairedDelta(0, 0, 7, 48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_anchorBelowGrowthSource_noDispatch() {
        // 锚在增长源之下(横幅锚,非零 fiso 仍属跟随族)
        assertEquals(0f, StreamingAnchorRule.pairedDelta(2, 140, 7, 48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_anchorOnSourceOffsetZero_pairs() {
        // 锚=item 自身 start 边(fiso==0,非贴底原点):锚内增长=跟随,需 +Δ 保持画面
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 0, 7, 48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_anchorOnSourceScrolledIn_pairsFullDelta() {
        // 尾段阅读(锚上移进入增长源贴底邻域):+Δ 同帧配对(fiso+=Δ)
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 60, 7, 48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_deepReading_beyondAtBottomPx_pairs() {
        // 锚=item 自身深处(用户场景:脱离贴底停在流式文本中)——+Δ 配对
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 300, 7, 48f, coveredByFollowFamily = true))
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 100, 7, 48f, coveredByFollowFamily = true))
        assertEquals(48f, StreamingAnchorRule.pairedDelta(7, 99, 7, 48f, coveredByFollowFamily = true))
        // 贴底原点:物理跟随免派发
        assertEquals(0f, StreamingAnchorRule.pairedDelta(0, 0, 0, 48f, coveredByFollowFamily = true))
        // #437 验收八轮(真机像素证伪 ≤):增长源在锚之下(读历史,流式 item 在下方)
        // 免派发——LazyList 锚定默认已保持画面,+Δ 属双重补偿(拖拽+LEAP 回吐震荡)
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 300, 0, 48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_readingAway_growthBelowViewport_noDispatch() {
        // #437 验收八轮:读历史(增长源在锚下方、非锚 item)免派发——默认锚定已保持画面
        assertEquals(0f, StreamingAnchorRule.pairedDelta(20, 500, 7, 48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_shrink_neverPaired() {
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 300, 7, -48f, coveredByFollowFamily = true))
    }

    @Test
    fun rule_itemNotInLayout_dropped() {
        assertEquals(0f, StreamingAnchorRule.pairedDelta(7, 300, -1, 48f, coveredByFollowFamily = true))
    }

    // ---- StreamingGrowLedger:账本协议 ----

    @Test
    fun ledger_coldStart_silentBaseline() {
        val l = StreamingGrowLedger()
        l.note("msg", "k1", 500)
        assertFalse(l.hasPending)
        assertEquals(0f, l.takePaired(0, 0) { -1 })
    }

    @Test
    fun ledger_growth_accumulates_then_consumed_once() {
        val l = StreamingGrowLedger()
        l.note("msg", "k1", 500)
        l.note("msg", "k1", 572) // +72
        l.note("msg", "k1", 620) // +48
        assertTrue(l.hasPending)
        // 锚恰在增长源贴底邻域:全额配对(120=72+48)
        assertEquals(120f, l.takePaired(1, 60) { if (it == "k1") 1 else -1 })
        // 清账:第二次取为零
        assertFalse(l.hasPending)
        assertEquals(0f, l.takePaired(1, 60) { if (it == "k1") 1 else -1 })
    }

    @Test
    fun ledger_appendFamily_growth_droppedAndRebased() {
        val l = StreamingGrowLedger()
        l.note("msg", "k1", 500)
        l.note("msg", "k1", 700) // +200
        // 贴底跟随:免派发丢弃
        assertEquals(0f, l.takePaired(0, 0) { if (it == "k1") 7 else -1 })
        assertFalse(l.hasPending)
        // 丢弃后基线已 rebase:后续增长重新起账,不累积历史
        l.note("msg", "k1", 748) // +48
        assertEquals(48f, l.takePaired(1, 10) { if (it == "k1") 1 else -1 })
    }

    @Test
    fun ledger_shrink_rebasesWithoutPending() {
        val l = StreamingGrowLedger()
        l.note("msg", "k1", 500)
        l.note("msg", "k1", 300) // -200 收缩:不配对
        assertFalse(l.hasPending)
        l.note("msg", "k1", 348) // +48
        assertEquals(48f, l.takePaired(1, 60) { 1 })
    }

    @Test
    fun ledger_multiSource_visibleSums_invisibleDropped() {
        val l = StreamingGrowLedger()
        l.note("msg", "t_x", 1000)
        l.note("tool", "tool_progress", 200)
        l.note("msg", "t_x", 1048)      // +48(可见锚上)
        l.note("tool", "tool_progress", 272) // +72(增长源在视口之下)
        // 仅锚上增长源配对;不可见源丢弃
        assertEquals(48f, l.takePaired(5, 60) { k -> if (k == "t_x") 5 else -1 })
        // 工具源基线已随取账 rebase
        l.note("tool", "tool_progress", 300) // +28
        assertEquals(28f, l.takePaired(1, 50) { k -> if (k == "tool_progress") 1 else -1 })
    }

    @Test
    fun ledger_userScroll_rebaseAll() {
        val l = StreamingGrowLedger()
        l.note("msg", "k1", 500)
        l.note("msg", "k1", 600)
        l.rebaseAll()
        assertFalse(l.hasPending)
        assertEquals(0f, l.takePaired(1, 60) { 1 })
    }

    @Test
    fun ledger_forget_coldRestart() {
        val l = StreamingGrowLedger()
        l.note("msg", "k1", 500)
        l.forget("msg") // 节点卸载(流式结束/回收)
        l.note("msg", "k1", 900) // 重入:冷启动静默建基线,不产生伪增量
        assertFalse(l.hasPending)
    }

    @Test
    fun ledger_entryKeyIdentity_separateFromItemKey() {
        val l = StreamingGrowLedger()
        // 同 item 双增长源(消息包裹+内层压缩卡):entryKey 区分,itemKey 共用锚位判定
        l.note("msg:t_x", "t_x", 500)
        l.note("cmp_v1:t_x", "t_x", 120)
        l.note("msg:t_x", "t_x", 548)
        l.note("cmp_v1:t_x", "t_x", 168)
        assertEquals(96f, l.takePaired(1, 60) { 1 })
    }
}
