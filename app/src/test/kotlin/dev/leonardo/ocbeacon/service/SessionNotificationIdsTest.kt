package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.PendingInteractionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * #320：会话事件通知 id / kind 映射契约锁。
 *
 * 通知 id 空间是系统侧持久契约（更新不堆叠依赖同 kind 同 id）——offset 与
 * stableHash 算法一旦发布即不可改值（同 NotificationChannels 的约束语义）。
 */
class SessionNotificationIdsTest {

    // ============ kind → id offset 契约 ============

    @Test
    fun `kind offsets are stable contract`() {
        assertEquals(0, SessionNotificationKind.TURN_COMPLETE.idOffset)
        assertEquals(1000, SessionNotificationKind.PERMISSION.idOffset)
        assertEquals(2000, SessionNotificationKind.QUESTION.idOffset)
        assertEquals(3000, SessionNotificationKind.ERROR.idOffset)
    }

    @Test
    fun `all four kinds have distinct offsets`() {
        val offsets = SessionNotificationKind.entries.map { it.idOffset }
        assertEquals(SessionNotificationKind.entries.size, offsets.toSet().size)
    }

    // ============ PendingInteractionKind → 通知 kind 映射 ============

    @Test
    fun `approval maps to permission notification kind`() {
        assertEquals(
            SessionNotificationKind.PERMISSION,
            SessionNotificationKind.forPendingInteraction(PendingInteractionKind.APPROVAL),
        )
    }

    @Test
    fun `question and plan-review map to question notification kind`() {
        assertEquals(
            SessionNotificationKind.QUESTION,
            SessionNotificationKind.forPendingInteraction(PendingInteractionKind.QUESTION),
        )
        assertEquals(
            SessionNotificationKind.QUESTION,
            SessionNotificationKind.forPendingInteraction(PendingInteractionKind.PLAN_REVIEW),
        )
    }

    // ============ 通知 id：稳定 + 同会话按 kind 分槽 ============

    @Test
    fun `same server session kind yields stable id across calls`() {
        val a = SessionNotificationIds.of("server1", "session1", SessionNotificationKind.PERMISSION)
        val b = SessionNotificationIds.of("server1", "session1", SessionNotificationKind.PERMISSION)
        assertEquals(a, b)
    }

    @Test
    fun `different kinds same session yield different ids`() {
        val base = listOf(
            SessionNotificationKind.TURN_COMPLETE,
            SessionNotificationKind.PERMISSION,
            SessionNotificationKind.QUESTION,
            SessionNotificationKind.ERROR,
        ).map { SessionNotificationIds.of("server1", "session1", it) }
        assertEquals(base.size, base.toSet().size)
    }

    @Test
    fun `same session id on different servers yields different ids`() {
        val a = SessionNotificationIds.of("server1", "session1", SessionNotificationKind.PERMISSION)
        val b = SessionNotificationIds.of("server2", "session1", SessionNotificationKind.PERMISSION)
        assertNotEquals(a, b)
    }

    @Test
    fun `stable hash matches legacy FNV-1a implementation`() {
        // 与既有 AppNotificationManager.stableHash / CancelSessionNotificationsTest 复制版同算法
        var hash = 0x811c9dc5.toInt()
        for (part in arrayOf("server1", "session1")) {
            for (i in part.indices) {
                hash = (hash xor part[i].code) * 0x01000193
            }
        }
        assertEquals(hash, SessionNotificationIds.of("server1", "session1", SessionNotificationKind.TURN_COMPLETE))
    }

    // ============ 通知标题：会话 title 回退 id ============

    @Test
    fun `notification title prefers session title`() {
        assertEquals("Refactor parser", SessionNotificationIds.notificationTitle("Refactor parser", "ses_123"))
    }

    @Test
    fun `notification title falls back to session id when title null`() {
        assertEquals("ses_123", SessionNotificationIds.notificationTitle(null, "ses_123"))
    }

    @Test
    fun `notification title falls back to session id when title blank`() {
        assertEquals("ses_123", SessionNotificationIds.notificationTitle("  ", "ses_123"))
    }
}
