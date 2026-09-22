package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.tools.PartGroup
import dev.leonardo.ocbeacon.ui.screens.chat.tools.RenderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #422 历史懒加载契约:大组展开态拆条目发射。
 *
 * - 发射序 = 视觉自底向上(reverseLayout 索引 0 在底,同 #246 逆文档序先例):
 *   尾 Turn(skipStepGroupItem) → Body 分片逆序 → Head(折叠行,视觉顶部);
 * - displayEntryStart 钉在 Head(跳转落点 = 折叠行);
 * - 未展开/流式 turn 不拆;切片按权重预算贪心。
 */
class StepGroupLazySplitTest {

    private fun assistantMsg(id: String) = ChatMessage(
        message = Message.Assistant(id = id, sessionId = "s1", time = TimeInfo(2), parentId = "p0"),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = "x")),
    )

    private fun userMsg(id: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "s1", time = TimeInfo(1)),
        parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = "q")),
    )

    private fun bigStepGroup(msgId: String): RenderItem.StepGroup {
        // 3 个长 text part:权重 200+len → 总权重远超 LARGE_STEP_GROUP_WEIGHT
        val groups = (0..2).map { i ->
            PartGroup.Single(
                Part.Text(
                    id = msgId + "_p" + i,
                    sessionId = "s1",
                    messageId = msgId,
                    text = "x".repeat(3000),
                )
            )
        }
        return RenderItem.StepGroup(msgId = msgId, groups = groups, toolCount = 1, textCount = 3)
    }

    @Test
    fun sliceSplitsByWeightBudget() {
        val groups = bigStepGroup("m_sg").groups
        val bodies = sliceStepGroupBodies(groups)
        // 每个 part 权重 3200 > 预算 2200 → 各自成片
        assertEquals(3, bodies.size)
        assertEquals(groups, bodies.flatten())
    }

    @Test
    fun expandedLargeGroupEmitsSplitEntriesBottomUp() {
        val sg = bigStepGroup("m_sg")
        // turn = [非末消息 m_sg, 末消息 m_last];displayItems 最新在前
        val last = assistantMsg("m_last")
        // displayItems 只含 turn 末消息(非末 assistant 不入发射表——实机 RenderCache 取证)
        val displayItems = listOf(0 to last, 1 to userMsg("m_u"))
        val turnGroups = mapOf(0 to listOf(last, assistantMsg("m_sg")))
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = turnGroups,
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
            expandedStepGroups = mapOf("t_m_last" to sg),
        )
        val kinds = chat.entries.map { it::class.simpleName }
        // 自底向上:尾 Turn → 3 个 Body(逆序) → 尾收起行(#sgt) → Head;随后是更旧的 user 条目
        assertEquals(
            listOf("Turn", "StepGroupBody", "StepGroupBody", "StepGroupBody", "StepGroupHead", "StepGroupHead", "Turn"),
            kinds,
        )
        val tail = chat.entries[0] as ChatEntry.Turn
        assertTrue(tail.skipStepGroupItem)
        assertEquals("t_m_last", tail.key)
        // Body 逆序发射:最后一片(文档最旧 part)先入列
        val bodyKeys = chat.entries.drop(1).take(3).map { it.key }
        assertEquals(
            listOf("t_m_last#sgb2", "t_m_last#sgb1", "t_m_last#sgb0"),
            bodyKeys,
        )
        // 分片内容:逆序对应文档 groups
        val firstBody = chat.entries[1] as ChatEntry.StepGroupBody
        assertEquals(listOf(sg.groups[2]), firstBody.groups)
        // #423 批次三:倒数第 2=尾收起行(#sgt),最后=Head(其后是更旧的 user 条目);
        // displayEntryStart 仍钉在 Head(跳转落点=顶部折叠行)
        val tailRow = chat.entries[chat.entries.size - 3] as ChatEntry.StepGroupHead
        assertEquals("t_m_last#sgt", tailRow.key)
        val head = chat.entries[chat.entries.size - 2] as ChatEntry.StepGroupHead
        assertEquals("t_m_last#sgh", head.key)
        assertEquals(chat.entries.size - 2, chat.displayEntryStart[0])
    }

    @Test
    fun expandedSmallGroupAlsoSplits() {
        // #423 批次六:权重门槛拆除——小组展开同样走结构裂变(调研定案:头行恒高+
        // 内容条目化+animateItem;CardExpandReveal 原地增高路线退役于折叠组域)。
        val last = assistantMsg("m_last")
        val displayItems = listOf(0 to last, 1 to userMsg("m_u"))
        val turnGroups = mapOf(0 to listOf(last, assistantMsg("m_sg")))
        val small = RenderItem.StepGroup(
            msgId = "m_sg",
            groups = listOf(PartGroup.Single(Part.Text(id = "m_sg_p", sessionId = "s1", messageId = "m_sg", text = "tiny"))),
            toolCount = 0,
            textCount = 1,
        )
        val chat = buildChatEntries(
            displayItems, turnGroups, streamingMsgId = null,
            chunkPlans = emptyMap(), recentStreamedTurnKeys = emptySet(),
            expandedStepGroups = mapOf("t_m_last" to small),
        )
        // 自底向上:尾 Turn → 单 Body → 尾收起行 → Head
        assertEquals(
            listOf("Turn", "StepGroupBody", "StepGroupHead", "StepGroupHead", "Turn"),
            chat.entries.map { it::class.simpleName },
        )
        assertTrue((chat.entries[0] as ChatEntry.Turn).skipStepGroupItem)
    }

    @Test
    fun notExpandedOrStreamingKeepsSingleTurn() {
        val last = assistantMsg("m_last")
        val displayItems = listOf(0 to last, 1 to userMsg("m_u"))
        val turnGroups = mapOf(0 to listOf(last, assistantMsg("m_sg")))
        // 未展开:无拆分
        val idle = buildChatEntries(
            displayItems, turnGroups, streamingMsgId = null,
            chunkPlans = emptyMap(), recentStreamedTurnKeys = emptySet(),
        )
        assertEquals(2, idle.entries.size)
        assertEquals("Turn", idle.entries[0]::class.simpleName)
        assertEquals("u_m_u", idle.entries[1].key)
        // 流式:即使展开也不拆(流式渲染层恒平铺,拆分逻辑让位)
        val streaming = buildChatEntries(
            displayItems, turnGroups, streamingMsgId = "m_last",
            chunkPlans = emptyMap(), recentStreamedTurnKeys = emptySet(),
            expandedStepGroups = mapOf("t_m_last" to bigStepGroup("m_sg")),
        )
        assertEquals(2, streaming.entries.size)
        assertEquals(false, (streaming.entries[0] as ChatEntry.Turn).skipStepGroupItem)
    }
}
