package dev.leonardo.ocbeacon.domain.model

/**
 * 所有应用设置的聚合。
 * 每个属性对应 [dev.leonardo.ocbeacon.data.repository.SettingsDataStore]
 * 管理的 DataStore preferences 中的一个键。
 */
data class AppSettings(
    // --- 外观 ---
    val appLanguage: String = "",
    val appTheme: String = "system",
    val dynamicColor: Boolean = true,
    val amoledDark: Boolean = false,

    // --- 聊天 ---
    val chatFontSize: String = "medium",
    /** "normal" 或 "compact"。由 chatFontSize + compactMessages 迁移而来。 */
    val chatDensity: String = "normal",
    val initialMessageCount: Int = 30,
    /** 快捷新建会话对话框中显示的最近目录数量上限。范围 5..50。 */
    val recentDirectoryCount: Int = 20,
    val confirmBeforeSend: Boolean = false,
    val compactMessages: Boolean = false,
    /** 工具卡片默认自动展开（#202 改名自 collapseTools；值语义不变：true=展开）。 */
    val autoExpandTools: Boolean = false,
    val expandReasoning: Boolean = false,
    val showTurnDividers: Boolean = true,

    // --- 通知 ---
    val notificationsEnabled: Boolean = true,
    val silentNotifications: Boolean = false,

    // --- 行为 ---
    val hapticFeedback: Boolean = true,
    val reconnectMode: String = "normal",
    val keepScreenOn: Boolean = false,
    /** 2026-08-16（用户需求）：权限请求到达时自动应答 always（服务器落持久规则）。
     *  默认 false——安全默认，仅对可信服务器手动开启。 */
    val autoAllowPermissions: Boolean = false,

    // --- 图片附件 ---
    val compressImageAttachments: Boolean = true,
    val imageAttachmentMaxLongSide: Int = 1440,
    val imageAttachmentWebpQuality: Int = 60,

    // --- 终端 ---
    val terminalFontSize: Float = 13f
)
