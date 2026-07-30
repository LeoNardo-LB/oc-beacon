package dev.leonardo.ocremoteplus.domain.model

/**
 * Aggregate of all application settings.
 * Each property corresponds to a key in the DataStore preferences
 * managed by [dev.leonardo.ocremoteplus.data.repository.SettingsDataStore].
 */
data class AppSettings(
    // --- Appearance ---
    val appLanguage: String = "",
    val appTheme: String = "system",
    val dynamicColor: Boolean = true,
    val amoledDark: Boolean = false,

    // --- Chat ---
    val chatFontSize: String = "medium",
    /** "normal" or "compact". Migrated from chatFontSize + compactMessages. */
    val chatDensity: String = "normal",
    val initialMessageCount: Int = 30,
    /** Max number of recent directories shown in the quick new-session dialog. 5..50. */
    val recentDirectoryCount: Int = 20,
    val codeWordWrap: Boolean = false,
    val confirmBeforeSend: Boolean = false,
    val compactMessages: Boolean = false,
    val collapseTools: Boolean = false,
    val expandReasoning: Boolean = false,
    val showTurnDividers: Boolean = true,

    // --- Notifications ---
    val notificationsEnabled: Boolean = true,
    val silentNotifications: Boolean = false,

    // --- Behavior ---
    val hapticFeedback: Boolean = true,
    val reconnectMode: String = "normal",
    val keepScreenOn: Boolean = false,

    // --- Image Attachments ---
    val compressImageAttachments: Boolean = true,
    val imageAttachmentMaxLongSide: Int = 1440,
    val imageAttachmentWebpQuality: Int = 60,

    // --- Terminal ---
    val terminalFontSize: Float = 13f,

    // --- Local Runtime: UI ---
    val showLocalRuntime: Boolean = true,
    val localSetupCompleted: Boolean = false,

    // --- Local Runtime: Proxy ---
    val localProxyEnabled: Boolean = false,
    val localProxyUrl: String = "",
    val localProxyNoProxy: String = "",

    // --- Local Runtime: Server ---
    val localServerAllowLan: Boolean = false,
    val localServerUsername: String = "",
    val localServerPassword: String = "",
    val localServerRunInBackground: Boolean = true,
    val localServerAutoStart: Boolean = false,
    val localServerStartupTimeoutSec: Int = 30
)
