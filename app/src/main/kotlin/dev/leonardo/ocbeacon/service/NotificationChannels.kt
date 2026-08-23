package dev.leonardo.ocbeacon.service

/**
 * 通知渠道 ID 单一真相源（2026-08-24 收口）。
 *
 * 渠道 ID 是 Android 系统侧持久契约：发布后即不可改值——改 ID 等于
 * 用户已调的重要性/振动设置全部重置，且旧渠道残留。本对象只收口
 * 定义处（此前 AppNotificationManager 与 InSessionFeedbackPlayer
 * 双处镜像声明同值常量，无编译器/测试保护），**不改任何值**。
 *
 * 值变更由 NotificationChannelsTest 契约锁钉死。
 */
object NotificationChannels {
    const val CONNECTION = "opencode_connection"
    const val TASKS = "opencode_tasks"
    const val TASKS_SILENT = "opencode_tasks_silent"
    const val PERMISSIONS = "opencode_permissions"
    const val QUESTIONS = "opencode_questions"
}
