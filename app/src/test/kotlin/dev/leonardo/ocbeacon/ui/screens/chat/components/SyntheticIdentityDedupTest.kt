package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.rowmodel.dedupeByEventIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * （2026-09-12 扁平化 US#22）同源通知在**装配层**按事件身份键收敛为一条并
 * 原位更新状态——取代旧的 UI 层「×N」连续同内容合并。
 *
 * 身份键 = 子会话 / shell id（同源）→ 消息 id（无 id 不折叠）。
 */
class SyntheticIdentityDedupTest {

    private fun syntheticMsg(id: String, text: String) = ChatMessage(
        message = Message.User(
            id = id,
            sessionId = "ses_1",
            time = TimeInfo(created = id.hashCode().toLong()),
            role = "synthetic",
        ),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "ses_1", messageId = id, text = text)),
    )

    private fun userMsg(id: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "ses_1", time = TimeInfo(created = 1L)),
        parts = listOf(Part.Text(id = "p_$id", sessionId = "ses_1", messageId = id, text = "hi")),
    )

    private fun shellXml(shellId: String, command: String = "echo hi") =
        "<shell id=\"$shellId\" state=\"running\" command=\"$command\">out</shell>"

    private fun dedupe(vararg msgs: ChatMessage) =
        dedupeByEventIdentity(msgs.toList(), ::syntheticEventIdentityKey)

    @Test
    fun repeatedSameShellNotificationKeepsFirstPositionAndUpdatesInPlace() {
        val items = listOf(
            syntheticMsg("m1", shellXml("call_a", "cmd")),
            userMsg("u1"),
            syntheticMsg("m2", shellXml("call_a", "cmd")),
        )
        val out = dedupe(*items.toTypedArray())
        // 保留首次出现的位置（第 1 项），值取最新一条 m2（原位更新，不挪位、不刷屏）
        assertEquals(listOf("m2", "u1"), out.map { it.message.id })
    }

    @Test
    fun repeatedSameSubagentSessionCollapsesToLatest() {
        val xml = "<subagent id=\"ses_child\" state=\"completed\" description=\"task\">done</subagent>"
        val items = listOf(
            syntheticMsg("m1", xml),
            syntheticMsg("m2", xml),
        )
        val out = dedupe(*items.toTypedArray())
        assertEquals(listOf("m2"), out.map { it.message.id })
    }

    @Test
    fun differentShellIdsAreNotCollapsed() {
        val items = listOf(
            syntheticMsg("m1", shellXml("call_a")),
            syntheticMsg("m2", shellXml("call_b")),
        )
        assertEquals(2, dedupe(*items.toTypedArray()).size)
    }

    @Test
    fun unparsableSyntheticTextFallsBackToMessageIdAndNeverCollapses() {
        val junk = "not structured synthetic text"
        val items = listOf(syntheticMsg("m1", junk), syntheticMsg("m2", junk))
        assertEquals(2, dedupe(*items.toTypedArray()).size)
    }

    @Test
    fun nonSyntheticMessagesHaveNoIdentityKey() {
        assertNull(syntheticEventIdentityKey(userMsg("u1")))
    }
}
