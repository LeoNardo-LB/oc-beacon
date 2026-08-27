package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #243 连续同内容 shell 卡去重纯函数契约。
 *
 * 用户裁决（2026-08-27）：完全相同内容不重复渲染——首张 + ×N，其余抑制。
 * 范围：仅 shell 合成卡；仅连续同键；task/subagent 卡永不折叠。
 */
class SyntheticDedupTest {

    private fun shellXml(command: String, callId: String, output: String = "幂等测试ABC   Command exited with code 0.") =
        "<shell id=\"$callId\" state=\"completed\" command=\"$command\"> $output </shell>"

    private fun syntheticMsg(id: String, text: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "ses_1", time = TimeInfo(created = 1L), role = "synthetic"),
        parts = listOf(
            dev.leonardo.ocbeacon.domain.model.Part.Text(
                id = "p_$id", sessionId = "ses_1", messageId = id, text = text
            )
        )
    )

    private fun userMsg(id: String, text: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "ses_1", time = TimeInfo(created = 1L)),
        parts = listOf(
            dev.leonardo.ocbeacon.domain.model.Part.Text(
                id = "p_$id", sessionId = "ses_1", messageId = id, text = text
            )
        )
    )

    private fun subagentXml(sessionId: String) =
        "<subagent id=\"$sessionId\" state=\"completed\" description=\"统计md文件数量\">结果 A</subagent>"

    private fun dedupe(vararg msgs: ChatMessage) = dedupeConsecutiveSynthetics(
        msgs.mapIndexed { i, m -> i to m }
    )

    @Test
    fun threeConsecutiveIdenticalShellsCollapseToFirstWithCount2() {
        val cmd = "sleep 1 && echo 幂等测试ABC"
        val items = listOf(
            syntheticMsg("m1", shellXml(cmd, "call_a")),
            syntheticMsg("m2", shellXml(cmd, "call_b")),
            syntheticMsg("m3", shellXml(cmd, "call_c")),
        )
        val (filtered, counts) = dedupe(*items.toTypedArray())

        assertEquals(listOf("m1"), filtered.map { it.second.message.id })
        assertEquals(2, counts["m1"])
    }

    @Test
    fun sameShellSeparatedByOtherMessageIsNotCollapsed() {
        val cmd = "sleep 1 && echo 幂等测试ABC"
        val items = listOf(
            syntheticMsg("m1", shellXml(cmd, "call_a")),
            userMsg("u1", "中间插了一条普通消息"),
            syntheticMsg("m2", shellXml(cmd, "call_b")),
        )
        val (filtered, counts) = dedupe(*items.toTypedArray())

        assertEquals(listOf("m1", "u1", "m2"), filtered.map { it.second.message.id })
        assertTrue(counts.isEmpty())
    }

    @Test
    fun differentCommandsAreNotCollapsedEvenWhenConsecutive() {
        val items = listOf(
            syntheticMsg("m1", shellXml("cmd one", "call_a", "out one   Command exited with code 0.")),
            syntheticMsg("m2", shellXml("cmd two", "call_b", "out two   Command exited with code 0.")),
        )
        val (filtered, counts) = dedupe(*items.toTypedArray())

        assertEquals(2, filtered.size)
        assertTrue(counts.isEmpty())
    }

    @Test
    fun subagentCardsNeverCollapseEvenWhenIdentical() {
        val items = listOf(
            syntheticMsg("m1", subagentXml("ses_a")),
            syntheticMsg("m2", subagentXml("ses_b")),
        )
        val (filtered, counts) = dedupe(*items.toTypedArray())

        assertEquals(2, filtered.size)
        assertTrue(counts.isEmpty())
    }

    @Test
    fun unparsableSyntheticTextGetsNullKeyAndIsNeverCollapsed() {
        val junk = "这不是结构化 synthetic 文本"
        val items = listOf(
            syntheticMsg("m1", junk),
            syntheticMsg("m2", junk),
        )
        val (filtered, counts) = dedupe(*items.toTypedArray())

        assertEquals(2, filtered.size)
        assertTrue(counts.isEmpty())
    }

    @Test
    fun dedupKeyIgnoresVolatileCallIdButKeepsCommandAndOutput() {
        val k1 = syntheticDedupKey(shellXml("cmd", "call_aaa"))
        val k2 = syntheticDedupKey(shellXml("cmd", "call_bbb"))
        val k3 = syntheticDedupKey(shellXml("cmd", "call_aaa", "不同输出   Command exited with code 1."))

        assertEquals(k1, k2)
        assertTrue(k1 != k3)
    }
}
