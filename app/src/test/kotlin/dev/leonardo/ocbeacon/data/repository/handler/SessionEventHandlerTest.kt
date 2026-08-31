package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SessionEventHandlerTest {

    private lateinit var handler: SessionEventHandler

    @Before
    fun setup() {
        handler = SessionEventHandler()
    }

    private fun testSession(id: String) = Session(
        id = id,
        title = "Test $id",
        time = Session.Time(created = 1000L, updated = 2000L)
    )

    @Test
    fun `handles SessionCreated`() = runTest {
        val session = testSession("s1")
        val event = SseEvent.SessionCreated(session)

        val handled = handler.handle(event, "server1")

        assertTrue(handled)
        assertEquals(listOf(session), handler.sessions.value)
    }

    @Test
    fun `handles SessionUpdated - update existing`() = runTest {
        val session = testSession("s1")
        handler.handle(SseEvent.SessionCreated(session), "server1")

        val updated = session.copy(title = "Updated")
        handler.handle(SseEvent.SessionUpdated(updated), "server1")

        assertEquals(listOf(updated), handler.sessions.value)
    }

    @Test
    fun `handles SessionUpdated - upsert new`() = runTest {
        val updated = testSession("s1")
        handler.handle(SseEvent.SessionUpdated(updated), "server1")

        assertEquals(listOf(updated), handler.sessions.value)
    }

    @Test
    fun `handles SessionDeleted`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")

        handler.handle(SseEvent.SessionDeleted(testSession("s1")), "server1")

        assertEquals(1, handler.sessions.value.size)
        assertEquals("s2", handler.sessions.value[0].id)
    }

    @Test
    fun `SessionDeleted keeps other sessions in serverSessions map (#218)`() = runTest {
        // 2026-08-25 修复回归：原 values.removeAll { it.contains(sessionId) } 会把
        // 整台服务器的会话 id 集合整体移除（谓词作用于 Set 元素而非集合内元素）→
        // 任一 session.deleted SSE 后会话列表全空（用户报告：退回列表 Empty directory；
        // 真机复现：删除任一会话即触发）。修复后仅移除该 id，其余保留。
        handler.setSessions("server1", listOf(testSession("s1"), testSession("s2"), testSession("s3")))

        handler.handle(SseEvent.SessionDeleted(testSession("s2")), "server1")

        val remaining = handler.serverSessions.value["server1"] ?: emptySet()
        assertTrue("删除 s2 后 s1 必须仍在 serverSessions（否则列表全空）", "s1" in remaining)
        assertTrue("删除 s2 后 s3 必须仍在 serverSessions", "s3" in remaining)
        assertFalse("被删的 s2 不得残留", "s2" in remaining)
        assertEquals("sessions 列表只移除被删项", 2, handler.sessions.value.size)
    }

    @Test
    fun `SessionDeleted clears lastUserMessageTime and sessionDiffs (#96)`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.recordUserMessage("s1", 1234L)
        handler.handle(SseEvent.SessionDiff("s1", emptyList()), "server1")

        handler.handle(SseEvent.SessionDeleted(testSession("s1")), "server1")

        assertTrue(
            "SessionDeleted 后 lastUserMessageTime 不得残留（#96 泄漏）",
            "s1" !in handler.lastUserMessageTime.value
        )
        assertTrue(
            "SessionDeleted 后 sessionDiffs 不得残留",
            "s1" !in handler.sessionDiffs.value
        )
    }

    @Test
    fun `handles SessionStatus - acknowledged, no local status state`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        // SessionStatus is acknowledged (returns true) but no longer tracked locally —
        // SessionStateService is the single source of truth for status.
        val handled = handler.handle(SseEvent.SessionStatus("s1", SessionStatus.Busy), "server1")
        assertTrue(handled)
    }

    @Test
    fun `handles SessionIdle - acknowledged, no local status state`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        val handled = handler.handle(SseEvent.SessionIdle("s1"), "server1")
        assertTrue(handled)
    }

    @Test
    fun `handles SessionDiff`() = runTest {
        val diffs = listOf(FileDiff(file = "test.kt", status = "modified"))
        handler.handle(SseEvent.SessionDiff("s1", diffs), "server1")

        assertEquals(diffs, handler.sessionDiffs.value["s1"])
    }

    @Test
    fun `handles VcsBranchUpdated`() = runTest {
        handler.handle(SseEvent.VcsBranchUpdated("main"), "server1")
        assertEquals("main", handler.vcsBranch.value)
    }

    @Test
    fun `handles ProjectUpdated`() = runTest {
        val project = Project(id = "p1", name = "Test", worktree = "/test")
        handler.handle(SseEvent.ProjectUpdated(project), "server1")
        assertEquals(project, handler.projectInfo.value)
    }

    @Test
    fun `returns false for non-session events`() {
        val handled = handler.handle(SseEvent.MessageUpdated(
            info = Message.User(
                id = "m1", sessionId = "s1",
                time = TimeInfo(created = 1000L)
            )
        ), "server1")
        assertFalse(handled)
    }

    @Test
    fun `clearForServer removes only target server sessions`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server2")

        handler.clearForServer("server1")

        assertEquals(1, handler.sessions.value.size)
        assertEquals("s2", handler.sessions.value[0].id)
    }

    @Test
    fun `clearAll resets everything`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.VcsBranchUpdated("main"), "server1")

        handler.clearAll()

        assertTrue(handler.sessions.value.isEmpty())
        assertNull(handler.vcsBranch.value)
        assertNull(handler.projectInfo.value)
    }

    @Test
    fun `setSessions merges correctly`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")

        handler.setSessions("server1", listOf(testSession("s1").copy(title = "Updated"), testSession("s2")))

        assertEquals(2, handler.sessions.value.size)
        assertEquals("Updated", handler.sessions.value.find { it.id == "s1" }?.title)
    }

    @Test
    fun `trackSession registers serverId mapping`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.handle(SseEvent.SessionCreated(testSession("s2")), "server1")

        val serverSessionMap = handler.serverSessions.value
        assertEquals(setOf("s1", "s2"), serverSessionMap["server1"])
    }

    @Test
    fun `handles ServerHeartbeat`() = runTest {
        assertTrue(handler.handle(SseEvent.ServerHeartbeat, "server1"))
    }

    @Test
    fun `handles ServerConnected`() = runTest {
        assertTrue(handler.handle(SseEvent.ServerConnected, "server1"))
    }

    @Test
    fun `handles SessionCompacted`() = runTest {
        assertTrue(handler.handle(SseEvent.SessionCompacted("s1"), "server1"))
    }

    @Test
    fun `handles SessionError`() = runTest {
        assertTrue(handler.handle(SseEvent.SessionError("s1", "error msg"), "server1"))
    }

    @Test
    fun `SessionTokenUsageChanged folds tokenUsage into session last-wins`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        assertTrue(
            handler.handle(
                SseEvent.SessionTokenUsageChanged("s1", DshTokenUsage(100L, 50L, 20L, 0L)),
                "server1",
            )
        )
        assertEquals(DshTokenUsage(100L, 50L, 20L, 0L), handler.sessions.value[0].tokenUsage)
        // last-wins：后续帧整替换
        handler.handle(
            SseEvent.SessionTokenUsageChanged("s1", DshTokenUsage(200L, 80L, 30L, 5L)),
            "server1",
        )
        assertEquals(DshTokenUsage(200L, 80L, 30L, 5L), handler.sessions.value[0].tokenUsage)
    }

    @Test
    fun `SessionSubagentTimingChanged folds timing into session`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        assertTrue(
            handler.handle(
                SseEvent.SessionSubagentTimingChanged("s1", DshSubagentTiming(1500L, 1000L, 2500L)),
                "server1",
            )
        )
        assertEquals(DshSubagentTiming(1500L, 1000L, 2500L), handler.sessions.value[0].subagentTiming)
    }

    @Test
    fun `projection event before session baseline is no-op`() = runTest {
        // 事件早于 session.list 基线（会话未入列表）→ no-op，不崩不残留
        assertTrue(
            handler.handle(
                SseEvent.SessionTokenUsageChanged("ghost", DshTokenUsage(1L)),
                "server1",
            )
        )
        assertTrue(handler.sessions.value.isEmpty())
    }

    @Test
    fun `clearForServer with no sessions removes server entry`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.clearForServer("server1")

        assertFalse(handler.serverSessions.value.containsKey("server1"))
    }

    // #134（D2-L54）：revert=null 的 SessionUpdated 清除本地清除标志；
    // 副作用从 update lambda 移出后语义不变（CAS 重试不重复执行）。
    @Test
    fun `SessionUpdated with revert null clears locallyClearedReverts flag`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        val withRevert = testSession("s1").copy(
            revert = Session.Revert(messageId = "msg_old")
        )
        handler.handle(SseEvent.SessionUpdated(withRevert), "server1")
        assertEquals(withRevert.revert, handler.sessions.value[0].revert)

        // 用户发消息 → 本地清除 revert
        handler.clearRevert("s1")
        assertEquals(null, handler.sessions.value[0].revert)

        // 服务器确认 revert=null → 清除标志（副作用移出 update lambda 后仍生效）
        handler.handle(SseEvent.SessionUpdated(testSession("s1")), "server1")

        // 标志已清：后续新 revert 不再被抑制（陈旧抑制只保护确认前的窗口）
        val newRevert = testSession("s1").copy(
            revert = Session.Revert(messageId = "msg_new")
        )
        handler.handle(SseEvent.SessionUpdated(newRevert), "server1")
        assertEquals("msg_new", handler.sessions.value[0].revert?.messageId)
    }

    @Test
    fun `stale SessionUpdated with revert is suppressed while flag set`() = runTest {
        handler.handle(SseEvent.SessionCreated(testSession("s1")), "server1")
        handler.clearRevert("s1")

        // 服务器陈旧 revert 恢复尝试 → 被抑制（本地清除优先）
        val stale = testSession("s1").copy(
            revert = Session.Revert(messageId = "msg_stale")
        )
        handler.handle(SseEvent.SessionUpdated(stale), "server1")

        assertEquals(null, handler.sessions.value[0].revert)
    }

    // ============ D1③：转录内错误行（sessionErrorEvents 广播 + sessionErrors
    // 记录/dedup/sendMessage 清卡；DSH turn-error 语义：转录内行无 dismiss） ============

    @Test
    fun `session error records transcript rows and dedupes consecutive same text`() = runTest {
        handler.handle(SseEvent.SessionError(sessionId = "s1", error = "provider rejected: balance"), "server1")
        handler.handle(SseEvent.SessionError(sessionId = "s1", error = "provider rejected: balance"), "server1")
        handler.handle(SseEvent.SessionError(sessionId = "s1", error = "provider rejected: quota"), "server1")
        handler.handle(SseEvent.SessionError(sessionId = "s2", error = "agent crash"), "server1")
        assertEquals(listOf("provider rejected: balance", "provider rejected: quota"), handler.sessionErrors.value["s1"])
        assertEquals(listOf("agent crash"), handler.sessionErrors.value["s2"])
    }

    @Test
    fun `session error transcript rows clear removes all on send success`() = runTest {
        handler.handle(SseEvent.SessionError(sessionId = "s1", error = "e1"), "server1")
        handler.handle(SseEvent.SessionError(sessionId = "s1", error = "e2"), "server1")
        handler.clearSessionErrors("s1")
        assertTrue(handler.sessionErrors.value["s1"].isNullOrEmpty())
        assertTrue("s1" !in handler.sessionErrors.value)
    }
}
