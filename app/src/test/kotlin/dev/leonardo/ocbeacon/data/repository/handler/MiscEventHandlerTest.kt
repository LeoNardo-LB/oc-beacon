package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MiscEventHandlerTest {

    private lateinit var handler: MiscEventHandler

    @Before
    fun setup() {
        handler = MiscEventHandler()
    }

    @Test
    fun `handles TodoUpdated`() {
        val todos = listOf(SseEvent.TodoUpdated.Todo("Task 1", "pending", "high"))
        assertTrue(handler.handle(SseEvent.TodoUpdated("s1", todos), "server1"))
        assertEquals(todos, handler.todos.value["s1"])
    }

    @Test
    fun `handles PtyCreated`() {
        assertTrue(handler.handle(SseEvent.PtyCreated(id = "pty_1"), "server1"))
    }

    @Test
    fun `handles CommandExecuted`() {
        assertTrue(handler.handle(
            SseEvent.CommandExecuted(name = "build", sessionId = "s1"), "server1"
        ))
    }

    @Test
    fun `handles LspUpdated`() {
        assertTrue(handler.handle(SseEvent.LspUpdated, "server1"))
    }

    /** #285：commands/change 全局帧 → commandsChanged 流广播（消费者重载命令列表）。 */
    @Test
    fun `handles CommandsChanged emitting flow`() = kotlinx.coroutines.test.runTest {
        var fired = 0
        val job = backgroundScope.launch {
            handler.commandsChanged.collect { fired++ }
        }
        assertTrue(handler.handle(SseEvent.CommandsChanged, "server1"))
        testScheduler.runCurrent()
        assertEquals(1, fired)
        job.cancel()
    }

    @Test
    fun `returns false for unhandled events`() {
        assertFalse(handler.handle(SseEvent.ServerHeartbeat, "server1"))
        assertFalse(handler.handle(SseEvent.MessageUpdated(
            info = Message.User(
                id = "m1", sessionId = "s1",
                time = TimeInfo(created = 1000L)
            )
        ), "server1"))
    }

    @Test
    fun `clearForSession removes todos`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")

        handler.clearForSession("s1")

        assertNull(handler.todos.value["s1"])
    }

    @Test
    fun `clearForServer removes todos for session set`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")
        handler.handle(SseEvent.TodoUpdated("s2", listOf(
            SseEvent.TodoUpdated.Todo("Task 2", "done", "low")
        )), "server1")

        handler.clearForServer(setOf("s1"))

        assertNull(handler.todos.value["s1"])
        assertNotNull(handler.todos.value["s2"])
    }

    @Test
    fun `clearAll resets todos`() {
        handler.handle(SseEvent.TodoUpdated("s1", listOf(
            SseEvent.TodoUpdated.Todo("Task", "pending", "medium")
        )), "server1")

        handler.clearAll()

        assertTrue(handler.todos.value.isEmpty())
    }

    // ============ #384：手动压缩吸收源命令卡（box 单卡承载） ============

    @Test
    fun `manual compaction absorbs source command card and routes done into entry`() {
        // run 建卡（wire seq 序：run 恒先于 start——实录 613/614）
        handler.handle(SseEvent.CommandRunStarted(sessionId = "s1", commandId = "cmd-9", name = "compact", seq = 613, time = 1), "srv")
        assertEquals(1, handler.commandFeedback.value["s1"]?.size)
        // start 吸收：命令卡移除、box 建立——单卡
        handler.handle(SseEvent.CompactionStarted(sessionId = "s1", compactionId = "c1", sourceCommandId = "cmd-9", seq = 614, time = 2), "srv")
        assertEquals(0, handler.commandFeedback.value["s1"]?.size)
        assertEquals(1, handler.compactionEntries.value["s1"]?.size)
        // done 结算路由入 box，不复活命令卡（实录 618）
        handler.handle(SseEvent.CommandDone(sessionId = "s1", commandId = "cmd-9", kind = "success", text = "Compacted 8 history items.", seq = 618, time = 3), "srv")
        assertEquals(0, handler.commandFeedback.value["s1"]?.size)
        val entry = handler.compactionEntries.value["s1"]!!.single()
        assertEquals("Compacted 8 history items.", entry.commandDone?.text)
        assertTrue(entry.commandDone!!.isSuccess)
    }

    @Test
    fun `auto compaction without source command leaves command cards untouched`() {
        handler.handle(SseEvent.CommandRunStarted(sessionId = "s1", commandId = "cmd-7", name = "export", seq = 10, time = 1), "srv")
        handler.handle(SseEvent.CompactionStarted(sessionId = "s1", compactionId = "c2", sourceCommandId = null, seq = 11, time = 2), "srv")
        handler.handle(SseEvent.CommandDone(sessionId = "s1", commandId = "cmd-7", kind = "success", seq = 12, time = 3), "srv")
        // 自动压缩（sourceCommandId 缺席）：无吸收——命令卡照常存在并终态化
        assertEquals(1, handler.commandFeedback.value["s1"]?.size)
        assertEquals("success", handler.commandFeedback.value["s1"]!!.single().done?.kind)
        assertEquals(1, handler.compactionEntries.value["s1"]?.size)
    }
}
