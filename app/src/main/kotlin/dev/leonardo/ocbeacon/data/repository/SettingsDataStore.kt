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
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储在 DataStore 中的应用级设置 + 模型可见性/默认模型。
 *
 * C5 存储归属拆分（2026-08-26）：未读红点存储迁至 [UnreadStateStore]、
 * 会话标签迁至 [SessionTagStore]（同一 DataStore 实例、同键名，零数据迁移）——
 * 本类收缩为纯设置与模型可见性，防止无归属模块再度挤入（223→660 行回涨的根因）。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    internal val dataStore: DataStore<Preferences>,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private val LANGUAGE_KEY = stringPreferencesKey(APP_LANGUAGE_KEY_NAME)
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
        /**
         * 自动展开工具结果（#202 / CONTEXT.md 词条「自动展开工具结果」Phase 2 改名）：
         * true=默认展开。历史键 collapse_tools 是名实不符遗留——**值语义从未反转**
         * （UI 开关文案自始为 Auto-expand，checked 原值绑定），故迁移为纯键名搬家、
         * 值原样保留。读取带回退（迁移完成前旧用户不闪回默认值），迁移后旧键删除。
         */
        private val AUTO_EXPAND_TOOLS_KEY = booleanPreferencesKey("auto_expand_tools")
        private val LEGACY_COLLAPSE_TOOLS_KEY = booleanPreferencesKey("collapse_tools")
        private val EXPAND_REASONING_KEY = booleanPreferencesKey("expand_reasoning")
        private val SHOW_TURN_DIVIDERS_KEY = booleanPreferencesKey("show_turn_dividers")
        private val HAPTIC_FEEDBACK_KEY = booleanPreferencesKey("haptic_feedback")
        private val AUTO_ALLOW_PERMISSIONS_KEY = booleanPreferencesKey("auto_allow_permissions")
        private val RECONNECT_MODE_KEY = stringPreferencesKey("reconnect_mode")
        private val KEEP_SCREEN_ON_KEY = booleanPreferencesKey("keep_screen_on")
        private val SILENT_NOTIFICATIONS_KEY = booleanPreferencesKey("silent_notifications")
        private val COMPRESS_IMAGE_ATTACHMENTS_KEY = booleanPreferencesKey("compress_image_attachments")
        private val IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY = intPreferencesKey("image_attachment_max_long_side")
        private val IMAGE_ATTACHMENT_WEBP_QUALITY_KEY = intPreferencesKey("image_attachment_webp_quality")
        private val TERMINAL_FONT_SIZE_KEY = floatPreferencesKey("terminal_font_size")

        /** 用于在 attachBaseContext 中同步读取 locale 的 SharedPreferences 名称。 */
        private const val LOCALE_PREFS = "locale_prefs"

        /**
         * app_language 键名单一真相源（2026-08-24 收口）：DataStore 主存键与
         * locale_prefs SP 镜像键共用同一字面量（#136 双写设计——镜像仅供
         * attachBaseContext 同步读，真相源仍是 DataStore）。改键名必须两处同改，
         * 引用本常量后双写点天然同步。
         */
        private const val APP_LANGUAGE_KEY_NAME = "app_language"

        private const val SERVER_MODEL_HIDDEN_PREFIX = "server_model_hidden_"
        private const val SERVER_DEFAULT_MODEL_PREFIX = "server_default_model_"

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
                .getString(APP_LANGUAGE_KEY_NAME, "") ?: ""
        }

        /** #136（D2-L56）收敛决策：镜像与真相源不一致时返回应以 DataStore 为准的值；一致返回 null（无需回写）。 */
        internal fun resolveLanguageMirror(stored: String, mirror: String): String? =
            if (stored != mirror) stored else null
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

    /**
     * 同时写入 SharedPreferences 以便在 attachBaseContext 中同步读取。
     * #136（D2-L56）：先写 DataStore（真相源）再写镜像——任何写入窗口崩溃
     * 都只让镜像"落后"而非"超前"，配合 [reconcileLanguageMirror] 启动收敛。
     */
    suspend fun setAppLanguage(languageCode: String) {
        setPref(LANGUAGE_KEY, languageCode)
        context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(APP_LANGUAGE_KEY_NAME, languageCode)
            .apply()
    }

    /**
     * 启动校验：DataStore（真相源）与 SharedPreferences 语言镜像不一致时，
     * 以 DataStore 为准回写镜像（[resolveLanguageMirror] 为纯决策，便于单测）。
     * 镜像值优先被 attachBaseContext 同步读取——不收敛则语言漂移。
     */
    suspend fun reconcileLanguageMirror() {
        val stored = dataStore.data.first()[LANGUAGE_KEY] ?: ""
        val mirror = context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
            .getString(APP_LANGUAGE_KEY_NAME, "") ?: ""
        resolveLanguageMirror(stored, mirror)?.let { corrected ->
            AppLogger.d("SettingsDataStore", "Language mirror mismatch: prefs=" + mirror + ", datastore=" + stored + " -> restoring mirror")
            context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(APP_LANGUAGE_KEY_NAME, corrected)
                .apply()
        }
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

    /**
     * #134（D2-L57）：批量写入全部设置——单次 DataStore edit 原子落盘。
     * 替代 [SettingsRepositoryImpl.updateSettings] 的 21 次独立 edit
     * （每次 edit 都全文件重写+同步；中途崩溃留下半套设置的窗口）。
     * 语言镜像同步（attachBaseContext 同步读取）一并更新。
     */
    suspend fun updateAll(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[LANGUAGE_KEY] = settings.appLanguage
            prefs[THEME_KEY] = settings.appTheme
            prefs[DYNAMIC_COLOR_KEY] = settings.dynamicColor
            prefs[AMOLED_DARK_KEY] = settings.amoledDark
            prefs[FONT_SIZE_KEY] = settings.chatFontSize
            prefs[CHAT_DENSITY_KEY] = settings.chatDensity
            prefs[INITIAL_MESSAGE_COUNT_KEY] = settings.initialMessageCount
            prefs[RECENT_DIRECTORY_COUNT_KEY] = settings.recentDirectoryCount
            prefs[CONFIRM_BEFORE_SEND_KEY] = settings.confirmBeforeSend
            prefs[COMPACT_MESSAGES_KEY] = settings.compactMessages
            prefs[AUTO_EXPAND_TOOLS_KEY] = settings.autoExpandTools
            prefs[EXPAND_REASONING_KEY] = settings.expandReasoning
            prefs[SHOW_TURN_DIVIDERS_KEY] = settings.showTurnDividers
            prefs[NOTIFICATIONS_KEY] = settings.notificationsEnabled
            prefs[SILENT_NOTIFICATIONS_KEY] = settings.silentNotifications
            prefs[HAPTIC_FEEDBACK_KEY] = settings.hapticFeedback
            prefs[AUTO_ALLOW_PERMISSIONS_KEY] = settings.autoAllowPermissions
            prefs[RECONNECT_MODE_KEY] = settings.reconnectMode
            prefs[KEEP_SCREEN_ON_KEY] = settings.keepScreenOn
            prefs[COMPRESS_IMAGE_ATTACHMENTS_KEY] = settings.compressImageAttachments
            prefs[IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY] = settings.imageAttachmentMaxLongSide
            prefs[IMAGE_ATTACHMENT_WEBP_QUALITY_KEY] = settings.imageAttachmentWebpQuality
            prefs[TERMINAL_FONT_SIZE_KEY] = settings.terminalFontSize
        }
        // 语言镜像（#136 D2-L56 同源机制）：真相源已写，镜像同步
        context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(APP_LANGUAGE_KEY_NAME, settings.appLanguage)
            .apply()
    }

    // ============ 行为 ============

    /** 发送消息前是否显示确认对话框。默认：false。 */
    val confirmBeforeSend: Flow<Boolean> = prefFlow(CONFIRM_BEFORE_SEND_KEY, false)
    suspend fun setConfirmBeforeSend(enabled: Boolean) = setPref(CONFIRM_BEFORE_SEND_KEY, enabled)

    /** 是否启用紧凑消息间距。默认：false。 */
    val compactMessages: Flow<Boolean> = prefFlow(COMPACT_MESSAGES_KEY, false)
    suspend fun setCompactMessages(enabled: Boolean) = setPref(COMPACT_MESSAGES_KEY, enabled)

    /** 工具卡片是否默认自动展开（#202 改名自 collapseTools，语义即存储值方向：true=展开）。
     *  读取回退旧键——迁移（[runAutoExpandToolsKeyMigration]）完成前的读取窗口不失值。 */
    val autoExpandTools: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AUTO_EXPAND_TOOLS_KEY] ?: preferences[LEGACY_COLLAPSE_TOOLS_KEY] ?: false
    }

    suspend fun setAutoExpandTools(enabled: Boolean) = setPref(AUTO_EXPAND_TOOLS_KEY, enabled)

    /**
     * #202 一次性迁移：collapse_tools → auto_expand_tools 键名搬家（值原样，**无取反**）。
     * 幂等：新键已存在则只清旧键。由 EventDispatcher init 触发（unread v2 迁移同款纪律）。
     */
    suspend fun runAutoExpandToolsKeyMigration() {
        dataStore.edit { prefs ->
            val legacy = prefs[LEGACY_COLLAPSE_TOOLS_KEY]
            if (legacy != null && prefs[AUTO_EXPAND_TOOLS_KEY] == null) {
                prefs[AUTO_EXPAND_TOOLS_KEY] = legacy
            }
            prefs.remove(LEGACY_COLLAPSE_TOOLS_KEY)
        }
    }

    /** 推理块是否默认展开。默认：false（折叠）。 */
    val expandReasoning: Flow<Boolean> = prefFlow(EXPAND_REASONING_KEY, false)
    suspend fun setExpandReasoning(enabled: Boolean) = setPref(EXPAND_REASONING_KEY, enabled)

    /** 是否在同一轮次的消息之间显示分隔线。默认：true。 */
    val showTurnDividers: Flow<Boolean> = prefFlow(SHOW_TURN_DIVIDERS_KEY, true)
    suspend fun setShowTurnDividers(enabled: Boolean) = setPref(SHOW_TURN_DIVIDERS_KEY, enabled)

    /** 是否启用触感反馈。默认：true。 */
    val hapticFeedback: Flow<Boolean> = prefFlow(HAPTIC_FEEDBACK_KEY, true)
    suspend fun setHapticFeedback(enabled: Boolean) = setPref(HAPTIC_FEEDBACK_KEY, enabled)
    val autoAllowPermissions: Flow<Boolean> = prefFlow(AUTO_ALLOW_PERMISSIONS_KEY, false)
    suspend fun setAutoAllowPermissions(enabled: Boolean) = setPref(AUTO_ALLOW_PERMISSIONS_KEY, enabled)

    /** 重连模式："aggressive"/"normal"/"conservative"。默认："normal"。 */
    val reconnectMode: Flow<String> = prefFlow(RECONNECT_MODE_KEY, "normal")
    suspend fun setReconnectMode(mode: String) = setPref(RECONNECT_MODE_KEY, mode)

    /** 流式传输期间是否保持屏幕常亮。默认：false。 */
    val keepScreenOn: Flow<Boolean> = prefFlow(KEEP_SCREEN_ON_KEY, false)
    suspend fun setKeepScreenOn(enabled: Boolean) = setPref(KEEP_SCREEN_ON_KEY, enabled)

    // ============ 通知 / 数据 ============

    /** 是否启用轮次完成通知。默认：true。 */
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

    // ============ 默认模型（2026-08-16 方案 A） ============

    private fun serverDefaultModelKey(serverId: String) =
        stringPreferencesKey(SERVER_DEFAULT_MODEL_PREFIX + serverId)

    /** 某服务器的本地默认模型（JSON "providerId|modelId|variant"，null=未设）。
     *  🟠 妥协标记：V2 服务器 config.model 只读（PATCH 404），默认模型只能
     *  客户端本地存（换设备/清数据丢失）——换 V1/V2 行为统一的代价。 */
    fun defaultModel(serverId: String): Flow<String?> = dataStore.data.map { preferences ->
        preferences[serverDefaultModelKey(serverId)]
    }

    /** 设置/清除默认模型（value=null 清除）。 */
    suspend fun setDefaultModel(serverId: String, value: String?) {
        val prefsKey = serverDefaultModelKey(serverId)
        dataStore.edit { preferences ->
            if (value == null) preferences.remove(prefsKey)
            else preferences[prefsKey] = value
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
            autoExpandTools = prefs[AUTO_EXPAND_TOOLS_KEY] ?: prefs[LEGACY_COLLAPSE_TOOLS_KEY] ?: false,
            expandReasoning = prefs[EXPAND_REASONING_KEY] ?: false,
            showTurnDividers = prefs[SHOW_TURN_DIVIDERS_KEY] ?: true,
            notificationsEnabled = prefs[NOTIFICATIONS_KEY] ?: true,
            silentNotifications = prefs[SILENT_NOTIFICATIONS_KEY] ?: false,
            hapticFeedback = prefs[HAPTIC_FEEDBACK_KEY] ?: true,
            autoAllowPermissions = prefs[AUTO_ALLOW_PERMISSIONS_KEY] ?: false,
            reconnectMode = prefs[RECONNECT_MODE_KEY] ?: "normal",
            keepScreenOn = prefs[KEEP_SCREEN_ON_KEY] ?: false,
            compressImageAttachments = prefs[COMPRESS_IMAGE_ATTACHMENTS_KEY] ?: true,
            imageAttachmentMaxLongSide = (prefs[IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY] ?: 1440).let { if (it <= 0) 0 else it.coerceIn(720, 4096) },
            imageAttachmentWebpQuality = (prefs[IMAGE_ATTACHMENT_WEBP_QUALITY_KEY] ?: 60).coerceIn(1, 100),
            terminalFontSize = (prefs[TERMINAL_FONT_SIZE_KEY] ?: 13f).coerceIn(6f, 20f)
        )
    }

}
