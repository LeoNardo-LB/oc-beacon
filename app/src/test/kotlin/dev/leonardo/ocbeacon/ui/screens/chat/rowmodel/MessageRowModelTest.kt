package dev.leonardo.ocbeacon.ui.screens.chat.rowmodel

import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (2026-09-12 消息层扁平化) 行模型 seam 单测——信息架构断言全部落在这里
 * （字段分配 / 能力位门控 / 第 N 轮来源 / 状态徽标 / 通知卡 / 逐轮行）。
 */
class MessageRowModelTest {

    private val emptyFlags = CoreFlags(
        compactionAsync = false,
        compactionModelIndependent = false,
        exportIsArchive = false,
        configEditable = true,
    )

    private fun capsOf(features: Set<ServerFeature>) =
        ServerCapabilities(coreFlags = emptyFlags, features = features)

    private val dshCaps = capsOf(setOf(ServerFeatures.TURN_TIMING, ServerFeatures.FEEDBACK))
    private val openCodeCaps = capsOf(
        setOf(
            ServerFeatures.COST,
            ServerFeatures.SESSION_REVERT,
            ServerFeatures.MESSAGE_DELETE,
        ),
    )

    private fun assistantTokens(
        input: Int,
        output: Int,
        total: Int? = null,
        reasoning: Int = 0,
        cacheRead: Int = 0,
        cacheWrite: Int = 0,
    ) = Message.Assistant.Tokens(
        input = input,
        output = output,
        total = total,
        reasoning = reasoning,
        cache = Message.Assistant.Tokens.Cache(read = cacheRead, write = cacheWrite),
    )

    // ---- 能力位 -------------------------------------------------------------

    @Test
    fun `dsh capabilities hide cost and revert but keep timing and feedback`() {
        val caps = rowCapabilitiesFor(dshCaps)
        assertFalse(caps.cost)
        assertTrue(caps.timing)
        assertTrue(caps.feedback)
        assertFalse(caps.revert)
        assertTrue(caps.fork)
        assertFalse(caps.messageDelete)
    }

    @Test
    fun `opencode capabilities keep cost and revert but hide timing and feedback`() {
        val caps = rowCapabilitiesFor(openCodeCaps)
        assertTrue(caps.cost)
        assertFalse(caps.timing)
        assertFalse(caps.feedback)
        assertTrue(caps.revert)
        assertTrue(caps.fork)
        assertTrue(caps.messageDelete)
    }

    // ---- 状态徽标 -----------------------------------------------------------

    @Test
    fun `completed message has no status badge`() {
        assertNull(statusBadgeFor(isStreaming = false, finish = "stop", hasError = false))
        assertNull(statusBadgeFor(isStreaming = false, finish = null, hasError = false))
    }

    @Test
    fun `streaming interrupted and error badges are derived`() {
        assertEquals(
            MessageStatusBadge.STREAMING,
            statusBadgeFor(isStreaming = true, finish = null, hasError = false),
        )
        assertEquals(
            MessageStatusBadge.INTERRUPTED,
            statusBadgeFor(isStreaming = false, finish = "interrupted", hasError = false),
        )
        assertEquals(
            MessageStatusBadge.ERROR,
            statusBadgeFor(isStreaming = false, finish = "error", hasError = false),
        )
    }

    @Test
    fun `error outranks interrupted and streaming`() {
        assertEquals(
            MessageStatusBadge.ERROR,
            statusBadgeFor(isStreaming = true, finish = "interrupted", hasError = true),
        )
    }

    // ---- 第 N 轮编号 --------------------------------------------------------

    @Test
    fun `server turn wins over client ordinal`() {
        val n = turnNumberFor(serverTurn = 7, clientOrdinal = 2)
        assertEquals(7, n!!.value)
        assertEquals(TurnNumberSource.SERVER, n.source)
    }

    @Test
    fun `client ordinal used when server turn absent`() {
        val n = turnNumberFor(serverTurn = null, clientOrdinal = 3)
        assertEquals(3, n!!.value)
        assertEquals(TurnNumberSource.CLIENT, n.source)
    }

    @Test
    fun `turn number absent when both sources missing`() {
        assertNull(turnNumberFor(serverTurn = null, clientOrdinal = null))
        assertNull(turnNumberFor(serverTurn = 0, clientOrdinal = 0))
    }

    // ---- 逐轮明细 -----------------------------------------------------------

    @Test
    fun `turn detail row carries every bucket and model for dsh`() {
        val rows = buildTurnDetailRows(
            listOf(
                TurnDetailInput(
                    serverTurn = 4,
                    clientOrdinal = null,
                    durationMs = 1200,
                    tokens = assistantTokens(
                        input = 100, output = 40, total = 180, reasoning = 5,
                        cacheRead = 30, cacheWrite = 10,
                    ),
                    stepCount = 2,
                    toolCallCount = 3,
                    cost = null,
                    modelId = "deepseek-flash",
                    providerId = "opencode-go",
                    ttftMs = 250,
                    tokensPerSecond = 12.5,
                ),
            ),
            rowCapabilitiesFor(dshCaps),
        )
        val row = rows.single()
        assertEquals(4, row.turnNumber!!.value)
        assertEquals(TurnNumberSource.SERVER, row.turnNumber.source)
        assertEquals(180L, row.tokensTotal)
        assertEquals(100L, row.tokensInput)
        assertEquals(40L, row.tokensOutput)
        assertEquals(5L, row.tokensReasoning)
        assertEquals(30L, row.tokensCacheRead)
        assertEquals(10L, row.tokensCacheWrite)
        assertEquals(250L, row.ttftMs)
        assertEquals(12.5, row.tokensPerSecond!!, 0.0)
        assertEquals(2, row.stepCount)
        assertEquals(3, row.toolCallCount)
        assertEquals("deepseek-flash", row.modelId)
        assertNull(row.cost)
        assertTrue(row.expandable)
    }

    @Test
    fun `turn detail hides cost on dsh and timing on opencode`() {
        val input = TurnDetailInput(
            serverTurn = null,
            clientOrdinal = 1,
            durationMs = 900,
            tokens = assistantTokens(input = 10, output = 5),
            stepCount = 1,
            toolCallCount = 0,
            cost = 0.42,
            modelId = "m",
            providerId = null,
            ttftMs = 100,
            tokensPerSecond = 9.0,
        )
        val opencode = buildTurnDetailRows(listOf(input), rowCapabilitiesFor(openCodeCaps)).single()
        assertNull(opencode.ttftMs)
        assertNull(opencode.tokensPerSecond)
        assertEquals(0.42, opencode.cost!!, 0.0)
    }

    @Test
    fun `zero optional buckets collapse to null so empty rows are not rendered`() {
        val row = buildTurnDetailRows(
            listOf(
                TurnDetailInput(
                    serverTurn = 1, clientOrdinal = null, durationMs = null,
                    tokens = assistantTokens(input = 0, output = 0),
                    stepCount = 0, toolCallCount = 0, cost = null,
                    modelId = null, providerId = null,
                ),
            ),
            rowCapabilitiesFor(dshCaps),
        ).single()
        assertNull(row.tokensInput)
        assertNull(row.tokensOutput)
        assertNull(row.tokensReasoning)
        assertNull(row.tokensCacheRead)
        assertNull(row.tokensCacheWrite)
        assertFalse(row.expandable)
    }

    // ---- 通知卡 -------------------------------------------------------------

    @Test
    fun `notification row model keeps body click as the only expand entry`() {
        val model = notificationRowModel(
            label = "子智能体完成",
            failed = false,
            description = "总结",
            hasBody = true,
            navTargetId = "ses_child",
        )
        assertTrue(model.bodyClickable)
        assertTrue(model.navArrowVisible)
        assertTrue(model.sameWidthNoIndent)
    }

    @Test
    fun `notification without nav target renders no arrow`() {
        val model = notificationRowModel(
            label = "Shell 完成", failed = false, description = null,
            hasBody = true, navTargetId = null,
        )
        assertFalse(model.navArrowVisible)
    }

    // ---- 事件身份键去重（US#22） -------------------------------------------

    @Test
    fun `dedupe by event identity keeps first position and newest value`() {
        val items = listOf("a" to "k1", "b" to "k2", "c" to "k1")
        val out = dedupeByEventIdentity(items) { it.second }
        // 位置 = 首次出现（第 1 项），值 = 最新（c）
        assertEquals(listOf("c", "b"), out.map { it.first })
    }

    @Test
    fun `dedupe by event identity keeps null key items untouched`() {
        val items = listOf("a" to null, "b" to "k", "c" to null)
        val out = dedupeByEventIdentity(items) { it.second }
        assertEquals(listOf("a", "b", "c"), out.map { it.first })
    }

    // ---- 事件身份键 ---------------------------------------------------------

    @Test
    fun `identity key prefers callId then child session then message id`() {
        assertEquals("call:c1", eventIdentityKey("c1", "ses", "m"))
        assertEquals("child:ses", eventIdentityKey(null, "ses", "m"))
        assertEquals("msg:m", eventIdentityKey("", null, "m"))
    }

    // ---- 尾部统计栏（v2） ---------------------------------------------------

    @Test
    fun `tail is always visible because the detail entry is always present`() {
        val tail = messageRowTail(
            turn = null, ledger = null, turnNumber = null, userMessage = null,
            caps = RowCapabilities.NONE, isStreaming = false,
            hasCopy = false, hasRevert = false, hasJump = false,
            hasFeedbackSheetItem = false, hasDeleteSheetItem = false,
            hasMarkdownCopySheetItem = false,
        )
        assertTrue(tail.visible)
        assertTrue(tail.detailAvailable)
        assertNull(tail.agentName)
    }

    @Test
    fun `fork makes the detail dialog non-empty`() {
        val tail = messageRowTail(
            turn = null, ledger = null, turnNumber = null, userMessage = null,
            caps = RowCapabilities.NONE, isStreaming = false,
            hasCopy = false, hasRevert = false, hasJump = true,
            hasFeedbackSheetItem = false, hasDeleteSheetItem = false,
            hasMarkdownCopySheetItem = false,
        )
        assertTrue(tail.moreAvailable)
    }

    // ---- 详情弹窗字段（v2） -------------------------------------------------

    @Test
    fun `user detail shows only the timestamp`() {
        val fields = messageDetailFields(MessageDetailInput(isUser = true, timeMs = 1L))
        assertEquals(listOf(MessageDetailField.TIME), fields)
    }

    @Test
    fun `assistant detail orders present fields and drops absent ones`() {
        val fields = messageDetailFields(
            MessageDetailInput(
                isUser = false,
                timeMs = 1L,
                agentName = "build",
                providerId = "oc",
                modelId = "gpt",
                durationMs = 2400,
                stepCount = 3,
                toolCallCount = 2,
                tokensTotal = 1234,
                cost = 0.42,
            ),
        )
        assertEquals(
            listOf(
                MessageDetailField.TIME,
                MessageDetailField.AGENT,
                MessageDetailField.MODEL,
                MessageDetailField.DURATION,
                MessageDetailField.STEPS,
                MessageDetailField.TOOLS,
                MessageDetailField.TOKENS,
                MessageDetailField.COST,
            ),
            fields,
        )
    }

    @Test
    fun `assistant detail omits absent data and zero step tool pairs`() {
        val fields = messageDetailFields(MessageDetailInput(isUser = false, timeMs = 1L))
        assertEquals(listOf(MessageDetailField.TIME), fields)
    }
}
