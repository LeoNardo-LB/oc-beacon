package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.DshSubagentTiming
import dev.leonardo.ocbeacon.domain.model.DshTokenUsage
import dev.leonardo.ocbeacon.domain.model.Session
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
}
