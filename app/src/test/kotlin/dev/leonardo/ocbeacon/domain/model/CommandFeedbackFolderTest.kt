package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #323 配对纯函数——commandId 配对原位更新（同 commandId run→done 单卡刷新，非两行）。
 */
class CommandFeedbackFolderTest {

    private fun run(commandId: String, name: String, seq: Long, time: Long = 0L, args: String? = null) =
        SseEvent.CommandRunStarted(
            sessionId = "s1", commandId = commandId, name = name, args = args, seq = seq, time = time,
        )

    private fun done(commandId: String, kind: String, seq: Long, time: Long = 0L, text: String? = null) =
        SseEvent.CommandDone(
            sessionId = "s1", commandId = commandId, kind = kind, text = text, seq = seq, time = time,
        )

    @Test
    fun `run appends a running state in arrival order`() {
        val states = CommandFeedbackFolder.onRun(emptyList(), run("c1", "compact", seq = 6, time = 100, args = "--x"))
        assertEquals(
            listOf(CommandFeedback(commandId = "c1", name = "compact", args = "--x", seq = 6, startedAt = 100)),
            states,
        )
        assertNull(states.single().done)

        val two = CommandFeedbackFolder.onRun(states, run("c2", "new", seq = 8, time = 200))
        assertEquals(listOf("c1", "c2"), two.map { it.commandId })
    }

    @Test
    fun `done pairs by commandId and refreshes the same card in place`() {
        // 核心：同 commandId run→done 单卡原位刷新——保位、保 name/args，不追加第二行
        var states = CommandFeedbackFolder.onRun(emptyList(), run("c1", "compact", seq = 6, time = 100, args = "--keep"))
        states = CommandFeedbackFolder.onRun(states, run("c2", "new", seq = 8, time = 120))

        states = CommandFeedbackFolder.onDone(states, done("c1", "success", seq = 7, time = 150, text = "ok"))

        assertEquals("不得产生第三行", 2, states.size)
        assertEquals(listOf("c1", "c2"), states.map { it.commandId })
        val first = states[0]
        assertEquals("compact", first.name)
        assertEquals("--keep", first.args)
        assertEquals(6, first.seq)
        val doneState = first.done!!
        assertEquals("success", doneState.kind)
        assertEquals("ok", doneState.text)
        assertEquals(150, doneState.time)
        assertNull("c2 保持进行中", states[1].done)
    }

    @Test
    fun `replayed run replaces the same commandId in place`() {
        // 重放幂等：历史折叠/重连重推不复制行
        var states = CommandFeedbackFolder.onRun(emptyList(), run("c1", "compact", seq = 6))
        states = CommandFeedbackFolder.onRun(states, run("c1", "compact", seq = 6, time = 100))
        assertEquals(1, states.size)
        assertEquals(100, states.single().startedAt)
    }

    @Test
    fun `orphan done appends its own terminal state with blank name`() {
        // run 缺席（重放截断/run 追加失败）：结算事实不丢弃——自建终态卡，name 空串回退
        val states = CommandFeedbackFolder.onDone(emptyList(), done("c9", "error", seq = 4, time = 50, text = "boom"))
        val single = states.single()
        assertEquals("c9", single.commandId)
        assertEquals("", single.name)
        assertEquals("error", single.done!!.kind)
        assertEquals("boom", single.done!!.text)
    }

    @Test
    fun `second done overwrites terminal state last-wins`() {
        var states = CommandFeedbackFolder.onRun(emptyList(), run("c1", "compact", seq = 6))
        states = CommandFeedbackFolder.onDone(states, done("c1", "error", seq = 7, text = "first"))
        states = CommandFeedbackFolder.onDone(states, done("c1", "error", seq = 8, text = "second"))
        assertEquals(1, states.size)
        assertEquals("second", states.single().done!!.text)
    }
}
