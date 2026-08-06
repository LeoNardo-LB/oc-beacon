package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储在 DataStore 中的应用级设置。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    internal val dataStore: DataStore<Preferences>,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private val LANGUAGE_KEY = stringPreferencesKey("app_language")
        private val THEME_KEY = stringPreferencesKey("app_theme")
        private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
        private val FONT_SIZE_KEY = stringPreferencesKey("chat_font_size")
        private val CHAT_DENSITY_KEY = stringPreferencesKey("chat_density")
        private val NOTIFICATIONS_KEY = booleanPreferencesKey("notifications_enabled")

        private val INITIAL_MESSAGE_COUNT_KEY = intPreferencesKey("initial_message_count")
        private val RECENT_DIRECTORY_COUNT_KEY = intPreferencesKey("recent_directory_count")
        private val CONFIRM_BEFORE_SEND_KEY = booleanPreferencesKey("confirm_before_send")
        private val AMOLED_DARK_KEY = booleanPreferencesKey("amoled_dark")
        private val COMPACT_MESSAGES_KEY = booleanPreferencesKey("compact_messages")
        private val COLLAPSE_TOOLS_KEY = booleanPreferencesKey("collapse_tools")
        private val EXPAND_REASONING_KEY = booleanPreferencesKey("expand_reasoning")
        private val SHOW_TURN_DIVIDERS_KEY = booleanPreferencesKey("show_turn_dividers")
        private val HAPTIC_FEEDBACK_KEY = booleanPreferencesKey("haptic_feedback")
        private val RECONNECT_MODE_KEY = stringPreferencesKey("reconnect_mode")
        private val KEEP_SCREEN_ON_KEY = booleanPreferencesKey("keep_screen_on")
        private val SILENT_NOTIFICATIONS_KEY = booleanPreferencesKey("silent_notifications")
        private val COMPRESS_IMAGE_ATTACHMENTS_KEY = booleanPreferencesKey("compress_image_attachments")
        private val IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY = intPreferencesKey("image_attachment_max_long_side")
        private val IMAGE_ATTACHMENT_WEBP_QUALITY_KEY = intPreferencesKey("image_attachment_webp_quality")
        private val TERMINAL_FONT_SIZE_KEY = floatPreferencesKey("terminal_font_size")

        /** 用于在 attachBaseContext 中同步读取 locale 的 SharedPreferences 名称。 */
        private const val LOCALE_PREFS = "locale_prefs"
        private const val LOCALE_PREFS_KEY = "app_language"

        private const val SERVER_MODEL_HIDDEN_PREFIX = "server_model_hidden_"

        /**
         * 从旧版 font size / compact 标志推导聊天密度。
         * 镜像了 [SettingsMigrationTest] 验证的逻辑。
         */
        internal fun migrateDensity(fontSize: String?, compact: Boolean?): String = when {
            compact == true -> "compact"
            fontSize == "small" -> "compact"
            else -> "normal"
        }

        /** 同步读取已存储的语言——可在 Hilt 初始化前安全调用。 */
        fun getStoredLanguage(context: Context): String {
            return context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
                .getString(LOCALE_PREFS_KEY, "") ?: ""
        }
    }

    // ============ 内部辅助 ============

    private fun <T> prefFlow(key: Preferences.Key<T>, default: T): Flow<T> =
        dataStore.data.map { it[key] ?: default }

    private suspend fun <T> setPref(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    // ============ 语言 ============

    /** 选定的语言代码（例如 "en"、"ru"、"de"），空字符串表示系统默认。 */
    val appLanguage: Flow<String> = prefFlow(LANGUAGE_KEY, "")

    /** 同时写入 SharedPreferences 以便在 attachBaseContext 中同步读取。 */
    suspend fun setAppLanguage(languageCode: String) {
        context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LOCALE_PREFS_KEY, languageCode)
            .apply()
        setPref(LANGUAGE_KEY, languageCode)
    }

    // ============ 外观 ============

    /** 主题："system"、"light" 或 "dark"。默认："system"。 */
    val appTheme: Flow<String> = prefFlow(THEME_KEY, "system")
    suspend fun setAppTheme(theme: String) = setPref(THEME_KEY, theme)

    /** 是否启用动态取色（Material You）。默认：true。 */
    val dynamicColor: Flow<Boolean> = prefFlow(DYNAMIC_COLOR_KEY, true)
    suspend fun setDynamicColor(enabled: Boolean) = setPref(DYNAMIC_COLOR_KEY, enabled)

    /** 是否启用 AMOLED 纯黑深色主题。默认：false。 */
    val amoledDark: Flow<Boolean> = prefFlow(AMOLED_DARK_KEY, false)
    suspend fun setAmoledDark(enabled: Boolean) = setPref(AMOLED_DARK_KEY, enabled)

    /** 聊天字体大小："small"、"medium"、"large"。默认："medium"。 */
    val chatFontSize: Flow<String> = prefFlow(FONT_SIZE_KEY, "medium")
    suspend fun setChatFontSize(size: String) = setPref(FONT_SIZE_KEY, size)

    /**
     * 聊天密度："normal" 或 "compact"。默认："normal"。
     * 迁移：当未设置时从旧版 [chatFontSize] 和 [compactMessages] 推导。
     */
    val chatDensity: Flow<String> = dataStore.data.map { preferences ->
        val stored = preferences[CHAT_DENSITY_KEY]
        if (stored != null) {
            stored
        } else {
            migrateDensity(
                fontSize = preferences[FONT_SIZE_KEY],
                compact = preferences[COMPACT_MESSAGES_KEY]
            )
        }
    }

    suspend fun setChatDensity(value: String) = setPref(CHAT_DENSITY_KEY, value)

    /** 默认终端字体大小（sp）。默认：13，范围 6..20。 */
    val terminalFontSize: Flow<Float> = dataStore.data.map { preferences ->
        (preferences[TERMINAL_FONT_SIZE_KEY] ?: 13f).coerceIn(6f, 20f)
    }

    suspend fun setTerminalFontSize(size: Float) {
        setPref(TERMINAL_FONT_SIZE_KEY, size.coerceIn(6f, 20f))
    }

    // ============ 行为 ============

    /** 发送消息前是否显示确认对话框。默认：false。 */
    val confirmBeforeSend: Flow<Boolean> = prefFlow(CONFIRM_BEFORE_SEND_KEY, false)
    suspend fun setConfirmBeforeSend(enabled: Boolean) = setPref(CONFIRM_BEFORE_SEND_KEY, enabled)

    /** 是否启用紧凑消息间距。默认：false。 */
    val compactMessages: Flow<Boolean> = prefFlow(COMPACT_MESSAGES_KEY, false)
    suspend fun setCompactMessages(enabled: Boolean) = setPref(COMPACT_MESSAGES_KEY, enabled)

    /** 工具卡片是否默认折叠。默认：false。 */
    val collapseTools: Flow<Boolean> = prefFlow(COLLAPSE_TOOLS_KEY, false)
    suspend fun setCollapseTools(enabled: Boolean) = setPref(COLLAPSE_TOOLS_KEY, enabled)

    /** 推理块是否默认展开。默认：false（折叠）。 */
    val expandReasoning: Flow<Boolean> = prefFlow(EXPAND_REASONING_KEY, false)
    suspend fun setExpandReasoning(enabled: Boolean) = setPref(EXPAND_REASONING_KEY, enabled)

    /** 是否在同一轮次的消息之间显示分隔线。默认：true。 */
    val showTurnDividers: Flow<Boolean> = prefFlow(SHOW_TURN_DIVIDERS_KEY, true)
    suspend fun setShowTurnDividers(enabled: Boolean) = setPref(SHOW_TURN_DIVIDERS_KEY, enabled)

    /** 是否启用触感反馈。默认：true。 */
    val hapticFeedback: Flow<Boolean> = prefFlow(HAPTIC_FEEDBACK_KEY, true)
    suspend fun setHapticFeedback(enabled: Boolean) = setPref(HAPTIC_FEEDBACK_KEY, enabled)

    /** 重连模式："aggressive"/"normal"/"conservative"。默认："normal"。 */
    val reconnectMode: Flow<String> = prefFlow(RECONNECT_MODE_KEY, "normal")
    suspend fun setReconnectMode(mode: String) = setPref(RECONNECT_MODE_KEY, mode)

    /** 流式传输期间是否保持屏幕常亮。默认：false。 */
    val keepScreenOn: Flow<Boolean> = prefFlow(KEEP_SCREEN_ON_KEY, false)
    suspend fun setKeepScreenOn(enabled: Boolean) = setPref(KEEP_SCREEN_ON_KEY, enabled)

    // ============ 通知 / 数据 ============

    /** 是否启用任务完成通知。默认：true。 */
    val notificationsEnabled: Flow<Boolean> = prefFlow(NOTIFICATIONS_KEY, true)
    suspend fun setNotificationsEnabled(enabled: Boolean) = setPref(NOTIFICATIONS_KEY, enabled)

    /** 通知是否静默（无声音/振动）。默认：false。 */
    val silentNotifications: Flow<Boolean> = prefFlow(SILENT_NOTIFICATIONS_KEY, false)
    suspend fun setSilentNotifications(enabled: Boolean) = setPref(SILENT_NOTIFICATIONS_KEY, enabled)

    /** 初始加载的消息数量。默认：30。 */
    val initialMessageCount: Flow<Int> = prefFlow(INITIAL_MESSAGE_COUNT_KEY, 30)
    suspend fun setInitialMessageCount(count: Int) = setPref(INITIAL_MESSAGE_COUNT_KEY, count)

    /** 快捷新建会话对话框中显示的最近目录数量。默认：20，限制在 5..50。 */
    val recentDirectoryCount: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[RECENT_DIRECTORY_COUNT_KEY] ?: 20).coerceIn(5, 50)
    }

    suspend fun setRecentDirectoryCount(count: Int) {
        setPref(RECENT_DIRECTORY_COUNT_KEY, count.coerceIn(5, 50))
    }

    // ============ 图片附件 ============

    /** 图片附件发送前是否优化（缩放 + WebP）。默认：true。 */
    val compressImageAttachments: Flow<Boolean> = prefFlow(COMPRESS_IMAGE_ATTACHMENTS_KEY, true)
    suspend fun setCompressImageAttachments(enabled: Boolean) = setPref(COMPRESS_IMAGE_ATTACHMENTS_KEY, enabled)

    /** 发送前缩放图片附件的最大长边（像素）。0 = 原始。默认：1440。 */
    val imageAttachmentMaxLongSide: Flow<Int> = dataStore.data.map { preferences ->
        val value = preferences[IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY] ?: 1440
        if (value <= 0) 0 else value.coerceIn(720, 4096)
    }

    suspend fun setImageAttachmentMaxLongSide(px: Int) {
        setPref(IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY, if (px <= 0) 0 else px.coerceIn(720, 4096))
    }

    /** 图片附件优化使用的 WebP 质量。默认：60，范围 1..100。 */
    val imageAttachmentWebpQuality: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[IMAGE_ATTACHMENT_WEBP_QUALITY_KEY] ?: 60).coerceIn(1, 100)
    }

    suspend fun setImageAttachmentWebpQuality(quality: Int) {
        setPref(IMAGE_ATTACHMENT_WEBP_QUALITY_KEY, quality.coerceIn(1, 100))
    }

    // ============ 模型可见性 ============

    private fun serverModelHiddenKey(serverId: String) =
        stringSetPreferencesKey(SERVER_MODEL_HIDDEN_PREFIX + serverId)

    /** 某服务器隐藏的模型键。键格式："providerId:modelId"。 */
    fun hiddenModels(serverId: String): Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[serverModelHiddenKey(serverId)] ?: emptySet()
    }

    /** visible=true 从隐藏集合移除，visible=false 添加。 */
    suspend fun setModelVisibility(serverId: String, providerId: String, modelId: String, visible: Boolean) {
        val key = "$providerId:$modelId"
        val prefsKey = serverModelHiddenKey(serverId)
        dataStore.edit { preferences ->
            val current = preferences[prefsKey] ?: emptySet()
            preferences[prefsKey] = if (visible) current - key else current + key
        }
    }

    // ============ 聚合 Flow ============

    /**
     * 将所有设置聚合为单个 [AppSettings] flow。
     * 从 DataStore preferences 原子读取——避免合并 30+ 个独立 flow。
     */
    val appSettingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            appLanguage = prefs[LANGUAGE_KEY] ?: "",
            appTheme = prefs[THEME_KEY] ?: "system",
            dynamicColor = prefs[DYNAMIC_COLOR_KEY] ?: true,
            amoledDark = prefs[AMOLED_DARK_KEY] ?: false,
            chatFontSize = prefs[FONT_SIZE_KEY] ?: "medium",
            chatDensity = prefs[CHAT_DENSITY_KEY] ?: migrateDensity(
                fontSize = prefs[FONT_SIZE_KEY],
                compact = prefs[COMPACT_MESSAGES_KEY]
            ),
            initialMessageCount = prefs[INITIAL_MESSAGE_COUNT_KEY] ?: 30,
            recentDirectoryCount = (prefs[RECENT_DIRECTORY_COUNT_KEY] ?: 20).coerceIn(5, 50),
            confirmBeforeSend = prefs[CONFIRM_BEFORE_SEND_KEY] ?: false,
            compactMessages = prefs[COMPACT_MESSAGES_KEY] ?: false,
            collapseTools = prefs[COLLAPSE_TOOLS_KEY] ?: false,
            expandReasoning = prefs[EXPAND_REASONING_KEY] ?: false,
            showTurnDividers = prefs[SHOW_TURN_DIVIDERS_KEY] ?: true,
            notificationsEnabled = prefs[NOTIFICATIONS_KEY] ?: true,
            silentNotifications = prefs[SILENT_NOTIFICATIONS_KEY] ?: false,
            hapticFeedback = prefs[HAPTIC_FEEDBACK_KEY] ?: true,
            reconnectMode = prefs[RECONNECT_MODE_KEY] ?: "normal",
            keepScreenOn = prefs[KEEP_SCREEN_ON_KEY] ?: false,
            compressImageAttachments = prefs[COMPRESS_IMAGE_ATTACHMENTS_KEY] ?: true,
            imageAttachmentMaxLongSide = (prefs[IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY] ?: 1440).let { if (it <= 0) 0 else it.coerceIn(720, 4096) },
            imageAttachmentWebpQuality = (prefs[IMAGE_ATTACHMENT_WEBP_QUALITY_KEY] ?: 60).coerceIn(1, 100),
            terminalFontSize = (prefs[TERMINAL_FONT_SIZE_KEY] ?: 13f).coerceIn(6f, 20f)
        )
    }
}
