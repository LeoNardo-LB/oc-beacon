package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知渠道 ID 契约锁（2026-08-24 收口时补上——InSessionFeedbackPlayer 旧注释
 * 声称"双向漂移由单测钉死"但该单测并不存在）。
 *
 * 渠道 ID 是 Android 系统侧持久契约：发布后改值 = 用户已调的重要性/振动
 * 设置全部重置 + 旧渠道残留。本测试钉死字面量，任何值变更必须显式改这里
 * 并理解上述后果。
 */
class NotificationChannelsTest {

    @Test
    fun channelIds_arePersistentSystemContract_lockedValues() {
        assertEquals("opencode_connection", NotificationChannels.CONNECTION)
        assertEquals("opencode_tasks", NotificationChannels.TASKS)
        assertEquals("opencode_tasks_silent", NotificationChannels.TASKS_SILENT)
        assertEquals("opencode_permissions", NotificationChannels.PERMISSIONS)
        assertEquals("opencode_questions", NotificationChannels.QUESTIONS)
    }

    @Test
    fun feedbackPlayer_aliases_shareSingleSource() {
        // 收口后 InSessionFeedbackPlayer 的别名与真相源同源——引用断言防止
        // 未来有人把别名改回字面量镜像
        assertEquals(NotificationChannels.TASKS, InSessionFeedbackPlayer.CHANNEL_TASKS)
        assertEquals(NotificationChannels.TASKS_SILENT, InSessionFeedbackPlayer.CHANNEL_TASKS_SILENT)
        assertEquals(NotificationChannels.PERMISSIONS, InSessionFeedbackPlayer.CHANNEL_PERMISSIONS)
        assertEquals(NotificationChannels.QUESTIONS, InSessionFeedbackPlayer.CHANNEL_QUESTIONS)
    }
}
