package dev.leonardo.ocbeacon.ui.screens.chat.components

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageSerializer
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.ui.screens.chat.ChatMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #252 勘误二（2026-08-28 真机 UI dump + Room 实证定音）：
 * V2 服务器为每次 !cmd 创建 role='shell' 零 parts 信封消息。MessageSerializer
 * 按 role 分发时 'shell'（及 agent-switched/model-switched）落入 else 回退分支
 * 反序列化为 Message.User——按 Message.Assistant 判定的过滤永不命中，空气泡
 *（48dp/条）照常渲染，15 条占位累积成消息与通知卡之间的半屏鸿沟。
 *
 * 契约：
 * 1. role='shell' 的 JSON 反序列化为 Message.User（回退行为锁定——若未来
 *    MessageSerializer 引入专用分支，本测试提醒同步审视过滤条件）；
 * 2. buildChatEntries 对 SYNTHETIC_ENVELOPE_ROLES 消息零发射（不渲染空气泡）；
 * 3. 真正的 user/assistant 消息不受过滤影响，displayEntryStart 索引保持一致。
 */
class SyntheticEnvelopeFilterTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** V2 shell 信封 payload 实测形态（Room cached_messages.payload，零 parts）。 */
    private val shellEnvelopeJson = """
        {"id":"msg_shell1","sessionID":"s1","role":"shell","time":{"created":1,"completed":2}}
    """.trimIndent()

    @Test
    fun `shell envelope deserializes to User fallback with role preserved`() {
        val msg = json.decodeFromString(MessageSerializer, shellEnvelopeJson)
        // 回退分支：不是 Assistant（原过滤 bug 的根源）
        assertTrue("role='shell' 应走 else 回退为 Message.User", msg is Message.User)
        // role 字段保留原始值（过滤判据的载体）
        assertEquals("shell", msg.role)
    }

    @Test
    fun `buildChatEntries skips synthetic envelope roles`() {
        fun envelope(id: String, role: String) = ChatMessage(
            message = Message.User(id = id, sessionId = "s1", role = role, time = TimeInfo(1)),
            parts = emptyList(),
        )
        fun realUser(id: String) = ChatMessage(
            message = Message.User(id = id, sessionId = "s1", time = TimeInfo(1)),
            parts = listOf(Part.Text(id = id + "_p", sessionId = "s1", messageId = id, text = "正文")),
        )

        // 最新在前：[shell 信封(零载荷), assistant-switched 信封, model-switched 信封, 真 user 消息]
        val displayItems = listOf(
            0 to envelope("m_shell", "shell"),
            1 to envelope("m_asw", "agent-switched"),
            2 to envelope("m_msw", "model-switched"),
            3 to realUser("m_u1"),
        )
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = emptyMap(),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
        )
        // 仅真 user 消息发射 1 个 entry；零载荷信封不发射（无内容可渲染）
        assertEquals(listOf("u_m_u1"), chat.entries.map { it.key })
        // 索引一致性：跳过消息的 displayEntryStart 保持单调（不崩溃、不串位）
        assertEquals(0, chat.displayEntryStart[0])
        assertEquals(0, chat.displayEntryStart[1])
        assertEquals(0, chat.displayEntryStart[2])
        assertEquals(0, chat.displayEntryStart[3])
    }

    @Test
    fun `shell envelope with shell part payload is emitted on timeline`() {
        // #252 时间线化：带 Part.Shell 载荷的 shell 消息（V2Mappers 映射 REST 完整
        // 载荷）按消息时间线发射——渲染层在其位置出通知卡，新消息顶上去。
        val shellMsg = ChatMessage(
            message = Message.User(id = "m_sh", sessionId = "s1", role = "shell", time = TimeInfo(10)),
            parts = listOf(Part.Shell(
                id = "m_sh_shell",
                sessionId = "s1",
                messageId = "m_sh",
                shellId = "sh_1",
                command = "echo gapcheck",
                status = "exited",
                exit = 0,
                output = "gapcheck",
            )),
        )
        val newerUser = ChatMessage(
            message = Message.User(id = "m_u2", sessionId = "s1", time = TimeInfo(20)),
            parts = listOf(Part.Text(id = "m_u2_p", sessionId = "s1", messageId = "m_u2", text = "后续消息")),
        )
        // 最新在前：新消息 index 0，shell 消息 index 1（更早）
        val displayItems = listOf(0 to newerUser, 1 to shellMsg)
        val chat = buildChatEntries(
            displayItems = displayItems,
            turnGroups = emptyMap(),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
        )
        // 两条都发射：shell 卡在时间线位置（entry 序 1），新消息在其后（视觉更下方）
        assertEquals(listOf("u_m_u2", "u_m_sh"), chat.entries.map { it.key })
        assertEquals(1, chat.displayEntryStart[1])
    }

    @Test
    fun `real user and assistant messages are not filtered`() {
        val u = ChatMessage(
            message = Message.User(id = "m_u", sessionId = "s1", time = TimeInfo(1)),
            parts = listOf(Part.Text(id = "p_u", sessionId = "s1", messageId = "m_u", text = "问")),
        )
        val a = ChatMessage(
            message = Message.Assistant(id = "m_a", sessionId = "s1", time = TimeInfo(2), parentId = "p0"),
            parts = listOf(Part.Text(id = "p_a", sessionId = "s1", messageId = "m_a", text = "答")),
        )
        val chat = buildChatEntries(
            displayItems = listOf(0 to a, 1 to u),
            turnGroups = mapOf(1 to listOf(a)),
            streamingMsgId = null,
            chunkPlans = emptyMap(),
            recentStreamedTurnKeys = emptySet(),
        )
        assertEquals(listOf("t_m_a", "u_m_u"), chat.entries.map { it.key })
    }
}
