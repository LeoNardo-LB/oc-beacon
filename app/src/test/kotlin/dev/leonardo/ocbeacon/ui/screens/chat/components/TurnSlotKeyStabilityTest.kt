package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import dev.leonardo.ocbeacon.ui.screens.chat.util.computeTurnAnchors
import dev.leonardo.ocbeacon.ui.screens.chat.util.computeTurnGroups
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #440 槽位锚键回归：流式宿主（dsh-t${turn}s${step}）与终态消息（seq-*）在同一轮
 * 换装时 turnKey 零漂移——LazyColumn 视角无 remove+add，消除换装跳变。
 *
 * 列表序 = newest→oldest（reverseLayout 索引 0 在屏幕底部）：assistant 在其提问
 * user 消息的上方；turn 组由 Older 侧相邻非 assistant 消息终结（= 提问者，锚）。
 */
class TurnSlotKeyStabilityTest {

    private fun user(id: String) = ChatMessage(
        message = Message.User(id = id, sessionId = "s", time = TimeInfo(0L)),
        parts = emptyList(),
    )

    private fun assistant(id: String) = ChatMessage(
        message = Message.Assistant(
            id = id, sessionId = "s", time = TimeInfo(0L), parentId = "",
        ),
        parts = emptyList(),
    )

    private fun keyOf(messages: List<ChatMessage>, rawIndex: Int): String =
        chatEntryKey(computeTurnGroups(messages), rawIndex, messages[rawIndex], computeTurnAnchors(messages))

    @Test
    fun `流式宿主换终态消息 turnKey 不变`() {
        val streaming = listOf(assistant("dsh-t2s1"), user("seq-s-22"))
        val finalized = listOf(assistant("seq-s-23"), user("seq-s-22"))
        assertEquals("t_seq-s-22", keyOf(streaming, 0))
        assertEquals("t_seq-s-22", keyOf(finalized, 0))
    }

    @Test
    fun `多步流式宿主交替 turnKey 不变`() {
        val step1 = listOf(assistant("dsh-t2s1"), user("u1"))
        val step2 = listOf(assistant("dsh-t2s2"), assistant("dsh-t2s1"), user("u1"))
        assertEquals("t_u1", keyOf(step1, 0))
        assertEquals("t_u1", keyOf(step2, 0))
        assertEquals("t_u1", keyOf(step2, 1))
    }

    @Test
    fun `pending 锚不采纳回退旧公式`() {
        val msgs = listOf(assistant("dsh-t2s1"), user("pending-abc"))
        assertEquals("t_dsh-t2s1", keyOf(msgs, 0))
    }

    @Test
    fun `开放尾组无锚回退组首条`() {
        val msgs = listOf(user("u0"), assistant("a1"), assistant("a2"))
        assertEquals("t_a1", keyOf(msgs, 1))
        assertEquals("t_a1", keyOf(msgs, 2))
    }

    @Test
    fun `锚表缺省时保持旧公式兼容`() {
        val turn = listOf(user("u1"), assistant("a1"))
        // 真实 computeTurnGroups 下 assistant 组首条即 a1（旧公式），锚缺省不受影响。
        assertEquals("t_a1", chatEntryKey(computeTurnGroups(turn), 1, turn[1]))
    }
}
