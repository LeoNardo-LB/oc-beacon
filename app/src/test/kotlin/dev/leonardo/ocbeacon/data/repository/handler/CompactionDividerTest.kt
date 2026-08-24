package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #217 分割线包揽（2026-08-24）：压缩状态数据链路单测。
 *
 * 覆盖：delta 流式累积、started 重置（同会话二次压缩）、ended 清理、
 * 乱序 delta 兜底（未 started 先 delta）、SessionEventHandler 压缩计数
 * （R3 修复：同会话多次压缩每次都递增）。
 */
class CompactionDividerTest {

    private lateinit var handler: SessionNextEventHandler
    private lateinit var sessionHandler: SessionEventHandler

    @Before
    fun setup() {
        handler = SessionNextEventHandler(dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker())
        sessionHandler = SessionEventHandler()
    }

    // ============ delta 累积 ============

    @Test
    fun `started then deltas accumulate deltaText and keep messageId`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m9", reason = "manual")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionDelta(sessionId = "s1", messageId = "m9", delta = "## Obj")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionDelta(sessionId = "s1", messageId = "m9", delta = "ective")
        )
        val state = handler.compactionState.value["s1"]
        assertNotNull(state)
        assertTrue(state!!.isActive)
        assertEquals("manual", state.reason)
        assertEquals("m9", state.messageId)
        assertEquals("## Objective", state.deltaText)
    }

    @Test
    fun `second compaction resets deltaText but keeps new messageId`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "manual")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionDelta(sessionId = "s1", messageId = "m1", delta = "first")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionEnded(sessionId = "s1", messageId = "m1")
        )
        // 同会话第二次压缩：delta 必须重新累积（不得残留第一次的文本）
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m2", reason = "auto")
        )
        val state = handler.compactionState.value["s1"]!!
        assertEquals("m2", state.messageId)
        assertEquals("", state.deltaText)
    }

    @Test
    fun `ended clears active state`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionStarted(sessionId = "s1", messageId = "m1", reason = "")
        )
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionEnded(sessionId = "s1", messageId = "m1")
        )
        assertNull(handler.compactionState.value["s1"])
    }

    @Test
    fun `out-of-order delta bootstraps active state`() {
        // 事件乱序防御：delta 先于 started 到达 → 置 isActive 兜底累积
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionDelta(sessionId = "s1", messageId = "m1", delta = "text")
        )
        val state = handler.compactionState.value["s1"]
        assertNotNull(state)
        assertTrue(state!!.isActive)
        assertEquals("text", state.deltaText)
    }

    @Test
    fun `empty delta is no-op`() {
        handler.handleSessionNextEvent(
            SessionNextEvent.CompactionDelta(sessionId = "s1", messageId = "m1", delta = "")
        )
        assertNull(handler.compactionState.value["s1"])
    }

    // ============ 压缩计数（R3 修复）============

    @Test
    fun `compacted count increments per event for same session`() {
        val e1 = dev.leonardo.ocbeacon.domain.model.SseEvent.SessionCompacted(sessionId = "s1")
        sessionHandler.handle(e1, "svr1")
        sessionHandler.handle(e1, "svr1")
        sessionHandler.handle(e1, "svr1")
        assertEquals(3L, sessionHandler.compactedSessions.value["s1"])
    }

    @Test
    fun `compacted count is per-session independent`() {
        sessionHandler.handle(
            dev.leonardo.ocbeacon.domain.model.SseEvent.SessionCompacted(sessionId = "s1"), "svr1")
        sessionHandler.handle(
            dev.leonardo.ocbeacon.domain.model.SseEvent.SessionCompacted(sessionId = "s2"), "svr1")
        assertEquals(1L, sessionHandler.compactedSessions.value["s1"])
        assertEquals(1L, sessionHandler.compactedSessions.value["s2"])
    }
}
