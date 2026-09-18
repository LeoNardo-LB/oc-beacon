package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.DshSubagentTiming
import dev.leonardo.ocbeacon.domain.model.DshTokenUsage
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.RowCapabilities
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.TurnNumber
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.TurnNumberSource
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.buildTurnDetailRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ContextDetailDelegate 子代理区数据组装测试（B：token 统计弹窗分流门控）。
 *
 * DSH 会话（Session 带 tokenUsage/subagentTiming）→ 弹窗状态携带子代理区字段；
 * OpenCode 会话（两字段 null）→ 字段 null，弹窗不渲染该区（V2 零改动）。
 */
class ContextDetailDelegateTest {

    private fun session(id: String, usage: DshTokenUsage?, timing: DshSubagentTiming?) = Session(
        id = id,
        time = Session.Time(created = 1L, updated = 2L),
        tokenUsage = usage,
        subagentTiming = timing,
    )

    @Test
    fun `dsh per-turn timing fills ttft and decode speed`() {
        val state = build(
            listOf(
                userMsg("u1"),
                ChatMessage(
                    message = Message.Assistant(
                        id = "a1",
                        sessionId = "s1",
                        time = TimeInfo(created = 10L, completed = 60_000L),
                        parentId = "",
                        ttftMs = 800L,
                        decodeMs = 20_000L,
                        decodeTokens = 300L,
                    ),
                    parts = emptyList(),
                ),
            ),
        )
        val row = buildTurnDetailRows(state.turnDetailInputs, fullCaps).single()
        assertEquals(800L, row.ttftMs)
        assertEquals(15.0, row.tokensPerSecond!!, 0.001)
    }

    @Test
    fun `dsh session populates subagent token total and active duration`() {
        val state = ContextDetailDelegate.buildContextDetailState(
            messages = emptyList(),
            stats = TokenStatsState(),
            session = session(
                "s1",
                DshTokenUsage(100L, 50L, 20L, 0L),
                DshSubagentTiming(1500L, 1000L, 2500L),
            ),
            contextWindow = 0,
        )
        assertEquals(170L, state.subagentTokens!!.total)
        assertEquals(3000L, state.subagentActiveDurationMs)
    }

    @Test
    fun `opencode session leaves subagent fields null`() {
        // V2/OpenCode：tokenUsage/subagentTiming 恒 null → 弹窗不渲染子代理区
        val state = ContextDetailDelegate.buildContextDetailState(
            messages = emptyList(),
            stats = TokenStatsState(),
            session = session("s2", null, null),
            contextWindow = 0,
        )
        assertNull(state.subagentTokens)
        assertNull(state.subagentActiveDurationMs)
    }

    @Test
    fun `null session leaves subagent fields null`() {
        val state = ContextDetailDelegate.buildContextDetailState(
            messages = emptyList(),
            stats = TokenStatsState(),
            session = null,
            contextWindow = 0,
        )
        assertNull(state.subagentTokens)
        assertNull(state.subagentActiveDurationMs)
    }

    // ------------------------------------------------------------------
    // 批3 统计弹窗：逐轮明细构造（turnDetailInputs，US#25-27）
    // ------------------------------------------------------------------

    private fun userMsg(id: String, synthetic: Boolean = false) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "s1",
            role = if (synthetic) "synthetic" else "user",
            time = TimeInfo(created = 0L),
        ),
        parts = emptyList(),
    )

    private fun assistantMsg(
        id: String,
        created: Long,
        completed: Long? = null,
        tokens: Message.Assistant.Tokens? = null,
        cost: Double? = null,
        turnNumber: Long? = null,
        modelId: String? = null,
        providerId: String? = null,
        toolCount: Int = 0,
    ) = ChatMessage(
        message = Message.Assistant(
            id = id,
            sessionId = "s1",
            time = TimeInfo(created = created, completed = completed),
            parentId = "",
            modelId = modelId,
            providerId = providerId,
            cost = cost,
            tokens = tokens,
            turnNumber = turnNumber,
        ),
        parts = List(toolCount) { idx ->
            Part.Tool(
                id = "$id-tool-$idx",
                sessionId = "s1",
                messageId = id,
                tool = "bash",
                state = ToolState.Completed(),
            )
        },
    )

    private val fullCaps = RowCapabilities(
        cost = true,
        timing = true,
        feedback = true,
        revert = true,
        fork = true,
        messageDelete = true,
    )

    private fun build(messages: List<ChatMessage>) = ContextDetailDelegate.buildContextDetailState(
        messages = messages,
        stats = TokenStatsState(),
        session = null,
        contextWindow = 0,
    )

    @Test
    fun `turn grouping orders newest first with server turn priority and client fallback`() {
        val state = build(
            listOf(
                userMsg("u1"),
                assistantMsg("a1", created = 100, completed = 200, turnNumber = 5),
                // synthetic 注入不算轮锚、不占序号
                userMsg("u-syn", synthetic = true),
                userMsg("u2"),
                // OpenCode：无服务器 turn 号 → client 序号兜底
                assistantMsg("a2", created = 300, completed = 400),
                // 无 assistant 的轮整轮跳过、不占序号
                userMsg("u3"),
            )
        )
        val inputs = state.turnDetailInputs
        assertEquals(2, inputs.size)
        // 倒序：最新一轮在前
        assertEquals(2, inputs[0].clientOrdinal)
        assertNull(inputs[0].serverTurn)
        assertEquals(1, inputs[1].clientOrdinal)
        assertEquals(5L, inputs[1].serverTurn)
        // 行号派生：serverTurn 优先，client 序号兜底
        val rows = buildTurnDetailRows(inputs, fullCaps)
        assertEquals(TurnNumber(5, TurnNumberSource.SERVER), rows[1].turnNumber)
        assertEquals(TurnNumber(2, TurnNumberSource.CLIENT), rows[0].turnNumber)
    }

    @Test
    fun `token buckets sum across assistant steps in a turn`() {
        val state = build(
            listOf(
                userMsg("u1"),
                assistantMsg(
                    "a1",
                    created = 1,
                    completed = 2,
                    tokens = Message.Assistant.Tokens(
                        input = 100,
                        output = 50,
                        reasoning = 10,
                        cache = Message.Assistant.Tokens.Cache(read = 30, write = 5),
                        total = 195,
                    ),
                ),
                assistantMsg(
                    "a2",
                    created = 2,
                    completed = 3,
                    tokens = Message.Assistant.Tokens(
                        input = 200,
                        output = 70,
                        reasoning = 0,
                        cache = Message.Assistant.Tokens.Cache(read = 0, write = 8),
                        total = 278,
                    ),
                ),
            )
        )
        val turn = state.turnDetailInputs.single()
        val t = turn.tokens!!
        assertEquals(300, t.input)
        assertEquals(120, t.output)
        assertEquals(10, t.reasoning)
        assertEquals(30, t.cache.read)
        assertEquals(13, t.cache.write)
        assertEquals(473, t.total)
        assertEquals(2, turn.stepCount)
    }

    @Test
    fun `tool call count sums tool parts across the turn`() {
        val state = build(
            listOf(
                userMsg("u1"),
                assistantMsg("a1", created = 1, toolCount = 2),
                assistantMsg("a2", created = 2, toolCount = 1),
            )
        )
        assertEquals(3, state.turnDetailInputs.single().toolCallCount)
    }

    @Test
    fun `all-null tokens and cost aggregate to null and streaming turn has null duration`() {
        val state = build(
            listOf(
                userMsg("u1"),
                // 流式中：completed = null → 轮耗时 null
                assistantMsg("a1", created = 100),
                userMsg("u2"),
                assistantMsg("a2", created = 300, completed = 400, tokens = null, cost = null),
            )
        )
        // 倒序：inputs[0] = 第 2 轮（已完结），inputs[1] = 第 1 轮（流式中）
        val done = state.turnDetailInputs[0]
        assertEquals(100L, done.durationMs)
        assertNull(done.tokens)
        assertNull(done.cost)
        val streamingTurn = state.turnDetailInputs[1]
        assertNull(streamingTurn.durationMs)
    }

    @Test
    fun `cost sums non-null entries and non-positive duration collapses to null`() {
        val state = build(
            listOf(
                userMsg("u1"),
                // completed == created → 差值 0 → null
                assistantMsg("a1", created = 100, completed = 100, cost = 0.5),
                userMsg("u2"),
                assistantMsg("a2", created = 300, completed = 400, cost = null),
                assistantMsg("a3", created = 310, completed = 420, cost = 0.25),
            )
        )
        // 倒序：inputs[0] = 第 2 轮，inputs[1] = 第 1 轮
        val turn2 = state.turnDetailInputs[0]
        assertEquals(2, turn2.clientOrdinal)
        assertEquals(120L, turn2.durationMs)
        assertEquals(0.25, turn2.cost!!, 1e-9)
        assertEquals(2, turn2.stepCount)
        val turn1 = state.turnDetailInputs[1]
        assertEquals(1, turn1.clientOrdinal)
        assertNull(turn1.durationMs)
        assertEquals(0.5, turn1.cost!!, 1e-9)
    }
}
