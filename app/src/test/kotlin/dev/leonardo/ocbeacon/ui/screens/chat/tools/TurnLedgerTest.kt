package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #310④ 轨迹台账——轮次台账行纯投影（turnLedgerSummary）+ 轮次序号 + 
 * computeRenderableTurn 台账预计算字段（stepCount/tokensTotal）钉死。
 *
 * 裁决（2026-09-05，数据可用性）：
 * - 步骤数 = turn 内 assistant 消息数（V2 每 step 一条消息；DSH assistant/message
 *   同构）——后端无关真相源，不用 StepFinish parts（DSH step/end 不产 part）。
 * - token = Σ 消息级 tokens（V2 step.ended/REST 与 DSH usage 均写入）；
 *   任一消息缺席 → null（严格语义，同 durationMs 的"全完结才给值"哲学）。
 */
class TurnLedgerTest {

    // ---------- fixture ----------

    private fun tool(name: String) = Part.Tool(
        id = "t_$name", tool = name,
        state = ToolState.Completed(input = emptyMap()),
    )

    private fun text(id: String) = Part.Text(
        id = id, sessionId = "s1", messageId = "m1", text = "text-$id",
    )

    private fun single(part: Part) = RenderItem.GroupedParts(PartGroup.Single(part))

    private fun contextGroup(vararg tools: Part.Tool) =
        RenderItem.GroupedParts(PartGroup.Context(tools.toList()))

    private fun turnOf(
        vararg items: RenderItem,
        durationMs: Long? = 12_000L,
        stepCount: Int = 2,
        tokensTotal: Long? = 800L,
    ) = RenderableTurn(
        renderItems = items.toList(),
        isEmpty = false,
        errorText = null,
        agentName = null,
        modelId = null,
        durationMs = durationMs,
        turnStartMs = 0L,
        stepFinishes = emptyList(),
        taskAgentName = null,
        copyText = "x",
        stepCount = stepCount,
        tokensTotal = tokensTotal,
    )

    private fun assistantMsg(
        id: String,
        created: Long,
        completed: Long?,
        tokens: Message.Assistant.Tokens? = null,
        parts: List<Part> = emptyList(),
    ) = ChatMessage(
        message = Message.Assistant(
            id = id, sessionId = "s1",
            time = TimeInfo(created = created, completed = completed),
            parentId = "", modelId = "m", tokens = tokens,
        ),
        parts = parts,
    )

    // ---------- turnLedgerSummary：renderItems → 工具投影 ----------

    @Test
    fun `tool call count sums single context and repeating items`() {
        val turn = turnOf(
            single(tool("bash")),
            contextGroup(tool("read"), tool("glob"), tool("grep")),
            RenderItem.RepeatingTool(tool("edit"), count = 3),
            single(text("p1")),
            RenderItem.TurnDivider("m1"),
        )
        val s = turnLedgerSummary(turn, turnNumber = 1)
        assertEquals(7, s.toolCallCount) // 1 + 3 + 3
        assertEquals(listOf("bash", "read", "glob", "grep", "edit"), s.toolNames)
    }

    @Test
    fun `tool names dedup preserves first seen order`() {
        val turn = turnOf(
            single(tool("bash")),
            single(tool("read")),
            single(tool("bash")),
        )
        val s = turnLedgerSummary(turn, turnNumber = 3)
        assertEquals(3, s.toolCallCount)
        assertEquals(listOf("bash", "read"), s.toolNames)
    }

    @Test
    fun `summary passes through turn number duration steps and tokens`() {
        val s = turnLedgerSummary(
            turnOf(
                single(tool("bash")),
                durationMs = 65_000L, stepCount = 3, tokensTotal = 1_234L,
            ),
            turnNumber = 7,
        )
        assertEquals(7, s.turnNumber)
        assertEquals(65_000L, s.durationMs)
        assertEquals(3, s.stepCount)
        assertEquals(1_234L, s.tokensTotal)
    }

    @Test
    fun `absent tokens and duration project to null`() {
        val s = turnLedgerSummary(
            turnOf(single(tool("bash")), durationMs = null, tokensTotal = null),
            turnNumber = 2,
        )
        assertNull(s.durationMs)
        assertNull(s.tokensTotal)
    }

    // ---------- computeRenderableTurn：台账预计算字段 ----------

    @Test
    fun `step count equals assistant message count regardless of isTurnLast`() {
        val msgs = listOf(
            assistantMsg("a1", 1000L, 2000L),
            assistantMsg("a2", 2500L, 8000L),
        )
        val t = computeRenderableTurn(msgs, msgs.last(), isTurnLast = false) { null }
        assertEquals(2, t.stepCount)
    }

    @Test
    fun `token total sums message tokens with input plus output fallback`() {
        val msgs = listOf(
            // total 缺席 → input + output 兜底
            assistantMsg("a1", 1000L, 2000L,
                tokens = Message.Assistant.Tokens(input = 100, output = 50)),
            assistantMsg("a2", 2500L, 8000L,
                tokens = Message.Assistant.Tokens(input = 10, output = 20, total = 999)),
        )
        val t = computeRenderableTurn(msgs, msgs.last(), isTurnLast = true) { null }
        assertEquals((150L + 999L), t.tokensTotal)
    }

    @Test
    fun `token total is null when any message lacks tokens`() {
        val msgs = listOf(
            assistantMsg("a1", 1000L, 2000L,
                tokens = Message.Assistant.Tokens(input = 100, output = 50)),
            assistantMsg("a2", 2500L, 8000L), // tokens 缺席 → 整轮 null（严格语义）
        )
        val t = computeRenderableTurn(msgs, msgs.last(), isTurnLast = true) { null }
        assertNull(t.tokensTotal)
    }

    @Test
    fun `compute pipeline feeds ledger projection end to end`() {
        val msgs = listOf(
            assistantMsg(
                "a1", 1000L, 5000L,
                tokens = Message.Assistant.Tokens(input = 300, output = 120),
                parts = listOf(tool("bash"), text("p1")),
            ),
        )
        val t = computeRenderableTurn(msgs, msgs.last(), isTurnLast = true) { null }
        val s = turnLedgerSummary(t, turnNumber = 4)
        assertEquals(1, s.stepCount)
        assertEquals(420L, s.tokensTotal)
        assertEquals(1, s.toolCallCount)
        assertEquals(listOf("bash"), s.toolNames)
        assertEquals(4000L, s.durationMs)
    }

    // ---------- 轮次序号 ----------

    @Test
    fun `turn ordinals number assistant anchors oldest first within window`() {
        // displayItems 新的在前（降序）；序号按视觉顺序（旧→新）编，最旧 = 1
        val a0 = assistantMsg("a0", 1000L, 2000L)
        val u1 = ChatMessage(
            message = dev.leonardo.ocbeacon.domain.model.Message.User(
                id = "u1", sessionId = "s1", time = TimeInfo(created = 3000L),
            ),
            parts = emptyList(),
        )
        val a2 = assistantMsg("a2", 4000L, 6000L)
        val displayItems = listOf(2 to a2, 1 to u1, 0 to a0)
        val ordinals = turnOrdinalByAnchorId(displayItems)
        assertEquals(1, ordinals["a0"])
        assertEquals(2, ordinals["a2"])
        assertNull(ordinals["u1"])
    }
}
