package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.CompactionStateInfo
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩分割线认领策略表驱动单测（C4-B）。
 *
 * 三个历史 bug 场景的语义哨兵（场景提取自修复 commit ff2b78be/ba53ffef/0ec516fe）：
 * - #226：V1 三元素重叠 + 空安全陷阱吞气泡
 * - #219：骨架期分割线消失（两边都不显示）
 * - #227：展开态滚出视口即丢（屏幕级展开表 + 交接桥）
 * - #217：进行中按 messageId 对位（历史分割线不受新压缩影响）
 * - #221：锁存空窗期认领（展开跨完成保持）
 */
class CompactionDividerPolicyTest {

    // ===== 构造助手 =====

    private fun userMsg(id: String, role: String = "user", parts: List<Part> = emptyList()) =
        ChatMessage(
            Message.User(id = id, sessionId = "s1", role = role, time = TimeInfo(created = 1L)),
            parts,
        )

    private fun v1SummaryMsg(
        id: String,
        completed: Long? = null,
        error: Message.Assistant.ErrorInfo? = null,
        parts: List<Part> = emptyList(),
    ) = ChatMessage(
        Message.Assistant(
            id = id,
            sessionId = "s1",
            role = "assistant",
            time = TimeInfo(created = 1L, completed = completed),
            parentId = "p1",
            agent = "compaction",
            error = error,
        ),
        parts,
    )

    private fun text(text: String) =
        Part.Text(id = "pt_" + text, sessionId = "s1", messageId = "m", text = text)

    private fun compactionPart(summary: String? = null, failed: Boolean = false) =
        Part.Compaction(id = "pc1", sessionId = "s1", messageId = "m", summary = summary, failed = failed)

    private fun activeState(messageId: String = "", delta: String = "") =
        CompactionStateInfo(isActive = true, reason = "", deltaText = delta, messageId = messageId)

    private fun entry(id: String, rawIndex: Int = 0, msg: ChatMessage? = null): Pair<Int, ChatMessage> =
        rawIndex to (msg ?: userMsg(id))

    // ============ #226：V1 摘要消息认领（v1SummarySpec） ============

    @Test
    fun v1Summary_未完结agentCompaction_伪活跃态分割线() {
        val spec = CompactionDividerPolicy.v1SummarySpec(
            v1SummaryMsg(id = "sum1", parts = listOf(text("ab"), text("cd"))),
        )
        assertNotNull(spec)
        assertTrue(spec!!.active)
        assertEquals("sum1", spec.messageId)
        assertEquals("sum1", spec.expansionKey)
        // 伪活跃 state 由流式 text 装配
        assertNotNull(spec.activeState)
        assertTrue(spec.activeState!!.isActive)
        assertEquals("ab\n\ncd", spec.activeState!!.deltaText)
        assertEquals("sum1", spec.activeState!!.messageId)
        assertNull(spec.summary)
        assertFalse(spec.failed)
    }

    @Test
    fun v1Summary_已完结折叠CompactionPart_完成态() {
        val spec = CompactionDividerPolicy.v1SummarySpec(
            v1SummaryMsg(id = "sum1", completed = 5L, parts = listOf(compactionPart(summary = "S"))),
        )
        assertNotNull(spec)
        assertFalse(spec!!.active)
        assertNull(spec.activeState)
        assertEquals("S", spec.summary)
        assertFalse(spec.failed)
    }

    @Test
    fun v1Summary_已完结error非空_failed为真() {
        val spec = CompactionDividerPolicy.v1SummarySpec(
            v1SummaryMsg(id = "sum1", completed = 5L, error = Message.Assistant.ErrorInfo(name = "boom")),
        )
        assertNotNull(spec)
        assertTrue(spec!!.failed)
    }

    @Test
    fun v1Summary_普通assistant与user消息不认领() {
        val plainAssistant = ChatMessage(
            Message.Assistant(
                id = "a1", sessionId = "s1", role = "assistant",
                time = TimeInfo(created = 1L), parentId = "p1",
            ),
            listOf(text("hi")),
        )
        assertNull(CompactionDividerPolicy.v1SummarySpec(plainAssistant))
        assertNull(CompactionDividerPolicy.v1SummarySpec(userMsg("u1")))
    }

    // ============ #226：V1 触发消息与空安全陷阱（userTriggerClaim） ============

    @Test
    fun userClaim_无CompactionPart普通用户消息不被吞掉() {
        // #226 热修回归哨兵：初版 firstOrNull()?.summary.isNullOrBlank() 在无 part
        // 消息上 = null.isNullOrBlank() = true → 全部用户气泡被隐藏。
        // 必须是 NotCompaction，绝不是 V1TriggerHidden。
        val claim = CompactionDividerPolicy.userTriggerClaim(
            userMsg("u1", parts = listOf(text("hello"))), null, null,
        )
        assertEquals(CompactionDividerSpec.NotCompaction, claim)
    }

    @Test
    fun userClaim_V1触发消息隐藏不渲染() {
        listOf(null, "", "   ").forEach { summary ->
            val claim = CompactionDividerPolicy.userTriggerClaim(
                userMsg("u1", parts = listOf(compactionPart(summary = summary))), null, null,
            )
            assertEquals("summary=" + summary, CompactionDividerSpec.V1TriggerHidden, claim)
        }
    }

    @Test
    fun userClaim_带摘要user触发消息渲染完成态分割线() {
        val claim = CompactionDividerPolicy.userTriggerClaim(
            userMsg("u1", parts = listOf(compactionPart(summary = "S"))), null, null,
        )
        assertTrue(claim is CompactionDividerSpec.Trigger)
        claim as CompactionDividerSpec.Trigger
        assertEquals("S", claim.summary)
        assertNull(claim.activeState)
        assertFalse(claim.failed)
        assertEquals("u1", claim.expansionKey)
    }

    // ============ #219：骨架期对位认领（userTriggerClaim + activeCompactionFor） ============

    @Test
    fun userClaim_骨架消息对位认领进行中分割线() {
        // inbox.enqueued 即插入骨架（role=compaction、无 Part.Compaction）——
        // started 到达后按 messageId 对位 → 消息流内渲染进行中分割线
        val skeleton = userMsg("m5", role = "compaction")
        val state = activeState(messageId = "m5", delta = "d")
        val claim = CompactionDividerPolicy.userTriggerClaim(skeleton, state, null)
        assertTrue(claim is CompactionDividerSpec.Trigger)
        claim as CompactionDividerSpec.Trigger
        assertNotNull(claim.activeState)
        assertNull(claim.summary)
    }

    @Test
    fun userClaim_steer排队期不认领() {
        // compactionState 未置（skeleton 已入列但 started 未到）——认领会
        // 渲染成静止「已压缩」误导
        val skeleton = userMsg("m5", role = "compaction")
        assertEquals(
            CompactionDividerSpec.NotCompaction,
            CompactionDividerPolicy.userTriggerClaim(skeleton, null, null),
        )
    }

    @Test
    fun userClaim_锁存空窗期靠latchedId认领() {
        // #221 展开跨完成保持：ended 清态 → REST 刷新带入 part 前的空窗，
        // 靠锁存 messageId 认领 → CompactionCard 不离开组合
        val skeleton = userMsg("m5", role = "compaction")
        val claim = CompactionDividerPolicy.userTriggerClaim(skeleton, null, latchedCompactionMsgId = "m5")
        assertTrue(claim is CompactionDividerSpec.Trigger)
        assertNull((claim as CompactionDividerSpec.Trigger).activeState)
    }

    @Test
    fun activeCompactionFor_仅命中当前压缩messageId() {
        // #217：历史分割线不受新压缩影响
        val state = activeState(messageId = "m_new")
        assertNull(CompactionDividerPolicy.activeCompactionFor(state, "m_old"))
        assertNotNull(CompactionDividerPolicy.activeCompactionFor(state, "m_new"))
        assertNull(CompactionDividerPolicy.activeCompactionFor(null, "m_new"))
        // inactive 状态不对位
        assertNull(CompactionDividerPolicy.activeCompactionFor(
            CompactionStateInfo(isActive = false, messageId = "m_new"), "m_new",
        ))
    }

    @Test
    fun combined_骨架入列后尾部让位且消息流认领() {
        // #219 修复前：尾部去重条件被骨架满足而让位，消息流又因无 part
        // 不认领 → 进行中态两边都不显示。断言两条路径互补。
        val ids = setOf("m5")
        val state = activeState(messageId = "m5")
        val tail = CompactionDividerPolicy.tailSpec(state, ids, v1SummaryInList = false)
        val claim = CompactionDividerPolicy.userTriggerClaim(
            userMsg("m5", role = "compaction"), state, null,
        )
        assertNull("骨架已入列——尾部不再出线", tail)
        assertTrue("消息流按 role+对位认领——进行中分割线不消失", claim is CompactionDividerSpec.Trigger)
    }

    // ============ ①④：尾部兜底认领（tailSpec）表驱动 ============

    @Test
    fun tailSpec_认领去重让位矩阵() {
        data class Case(
            val name: String,
            val compaction: CompactionStateInfo?,
            val ids: Set<String>,
            val v1InList: Boolean,
            val expectTail: Boolean,
        )
        val cases = listOf(
            Case("非活跃不兜底", CompactionStateInfo(isActive = false, messageId = "m1"), setOf("a"), false, false),
            Case("null不兜底", null, setOf("a"), false, false),
            Case("消息已入列去重", activeState("m1"), setOf("m1"), false, false),
            Case("V1摘要入列让位226", activeState(""), setOf("a"), true, false),
            Case("V2未入列兜底", activeState("m9"), setOf("a"), false, true),
            Case("V1空串messageId兜底", activeState(""), setOf("a"), false, true),
        )
        cases.forEach { c ->
            val tail = CompactionDividerPolicy.tailSpec(c.compaction, c.ids, c.v1InList)
            assertEquals(c.name, c.expectTail, tail != null)
            if (c.expectTail) {
                assertEquals(c.name, c.compaction!!.messageId, tail!!.state.messageId)
            }
        }
    }

    @Test
    fun tailSpec_展开键V2真实id_V1固定键() {
        assertEquals("m9", CompactionDividerPolicy.tailSpec(activeState("m9"), emptySet(), false)!!.expansionKey)
        assertEquals(
            CompactionDividerPolicy.TAIL_EXPANSION_KEY,
            CompactionDividerPolicy.tailSpec(activeState(""), emptySet(), false)!!.expansionKey,
        )
    }

    // ============ ⑤：撤销边界（v1RevertBoundary） ============

    @Test
    fun v1RevertBoundary_取紧邻前触发消息_找不到退自身() {
        val items = listOf(
            entry("plain", 0),
            entry("trigger", 1, userMsg("trigger", parts = listOf(compactionPart()))),
            entry("sum", 2, v1SummaryMsg(id = "sum")),
        )
        assertEquals("trigger", CompactionDividerPolicy.v1RevertBoundary(items, 2, fallbackId = "sum"))
        // 前驱不是带 Compaction part 的 user 消息 → 退自身
        assertEquals("sum", CompactionDividerPolicy.v1RevertBoundary(items, 0, fallbackId = "sum"))
        assertEquals("trigger", CompactionDividerPolicy.v1RevertBoundary(items, 1, fallbackId = "trigger"))
    }

    // ============ ①②：去重判据原料 ============

    @Test
    fun dedupInputs_displayIds与v1SummaryMessageId() {
        val items = listOf(
            entry("u1", 0),
            entry("sum", 1, v1SummaryMsg(id = "sum")),
            entry("u2", 2),
        )
        assertEquals(setOf("u1", "sum", "u2"), CompactionDividerPolicy.displayItemMessageIds(items))
        assertEquals("sum", CompactionDividerPolicy.v1SummaryMessageId(items))
        // 普通 assistant（agent=null）不算 V1 摘要
        val plain = listOf(
            entry("u1", 0),
            entry("a1", 1, ChatMessage(
                Message.Assistant(
                    id = "a1", sessionId = "s1", role = "assistant",
                    time = TimeInfo(created = 1L), parentId = "p1",
                ),
                emptyList(),
            )),
        )
        assertNull(CompactionDividerPolicy.v1SummaryMessageId(plain))
    }

    // ============ #227：屏幕级展开表语义（交接桥） ============

    @Test
    fun handover_尾部展开后摘要入列_搬移到消息键() {
        val items = listOf(entry("sum", 1, v1SummaryMsg(id = "sum")))
        val plan = CompactionDividerPolicy.v1TailHandoverPlan(
            items, mapOf(CompactionDividerPolicy.TAIL_EXPANSION_KEY to true),
        )
        assertNotNull(plan)
        assertEquals("sum", plan!!.targetKey)
        assertTrue(plan.expanded)
        // effect 落地后展开态经消息键存活——滚出视口再滚回不丢（#227 语义）
    }

    @Test
    fun handover_收起态也交接且不强制折叠() {
        val items = listOf(entry("sum", 1, v1SummaryMsg(id = "sum")))
        val plan = CompactionDividerPolicy.v1TailHandoverPlan(
            items, mapOf(CompactionDividerPolicy.TAIL_EXPANSION_KEY to false),
        )
        assertNotNull(plan)
        assertFalse(plan!!.expanded)
    }

    @Test
    fun handover_无尾部展开记录零操作() {
        val items = listOf(entry("sum", 1, v1SummaryMsg(id = "sum")))
        assertNull(CompactionDividerPolicy.v1TailHandoverPlan(items, emptyMap()))
        assertNull(CompactionDividerPolicy.v1TailHandoverPlan(items, mapOf("sum" to true)))
    }

    @Test
    fun handover_无摘要消息入列零操作() {
        assertNull(CompactionDividerPolicy.v1TailHandoverPlan(
            listOf(entry("u1")), mapOf(CompactionDividerPolicy.TAIL_EXPANSION_KEY to true),
        ))
    }

    // ============ ③：banner 计数派生（C4-C 等价性矩阵） ============

    @Test
    fun bannerTerms_与旧手算判据逐格等价() {
        val compactions = listOf(
            null,
            CompactionStateInfo(isActive = false, messageId = "m1"),
            activeState("m1"),
            activeState(""),
            activeState("m9"),
        )
        val idSets = listOf(setOf("a"), setOf("m1"), setOf("m9"), emptySet())
        val v1Flags = listOf(true, false)
        for (c in compactions) for (ids in idSets) for (v1 in v1Flags) {
            val terms = CompactionDividerPolicy.bannerTerms(c, ids, v1)
            // 旧 bannerCount 压缩项：active && (messageId in ids || v1)
            val legacyBanner = c != null && c.isActive && (c.messageId in ids || v1)
            // 旧 revealBannerCount 压缩项：active && messageId !in ids && !v1
            val legacyReveal = c?.isActive == true && c.messageId !in ids && !v1
            val label = "compaction=" + c + " ids=" + ids + " v1=" + v1
            assertEquals(label, legacyBanner, terms.streamClaimed)
            assertEquals(label, legacyReveal, terms.tailFallback)
            assertFalse(label, terms.streamClaimed && terms.tailFallback)
        }
    }
}
