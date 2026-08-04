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
import dev.leonardo.ocbeacon.domain.model.FavoriteSessionSnapshot
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.domain.model.favoriteKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 存储在 DataStore 中的应用级设置。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
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
        private val CODE_WORD_WRAP_KEY = booleanPreferencesKey("code_word_wrap")
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

        // 会话分类——全局列表（JSON 数组）+ 按服务器的分配（JSON map）。
        private val SESSION_CATEGORIES_KEY = stringPreferencesKey("session_categories")
        private const val SESSION_CATEGORY_ASSIGNMENTS_PREFIX = "session_category_assignments_"
        private val categoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val categoryListSerializer = ListSerializer(SessionCategory.serializer())
        private val assignmentMapSerializer = MapSerializer(String.serializer(), String.serializer())

        // ============ 跨服务器会话收藏 ============
        // 每服务器收藏的会话 id（stringSet）。前缀 + serverId。
        private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"
        // 全局跨服务器收藏顺序——"serverId:sessionId" 键的列表（JSON）。
        private val CROSS_SERVER_FAVORITE_ORDER_KEY = stringPreferencesKey("cross_server_favorite_order")
        // 以 "serverId:sessionId" 为键的离线快照（JSON map）。
        private val FAVORITE_SESSION_SNAPSHOTS_KEY = stringPreferencesKey("favorite_session_snapshots")
        private val favoriteSnapshotMapSerializer =
            MapSerializer(String.serializer(), FavoriteSessionSnapshot.serializer())
        private val favoriteOrderSerializer = ListSerializer(String.serializer())

        /**
         * 从旧版 font size / compact 标志推导聊天密度。
         * 镜像了 [SettingsMigrationTest] 验证的逻辑。
         */
        private fun migrateDensity(fontSize: String?, compact: Boolean?): String = when {
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

    private fun serverModelHiddenKey(serverId: String) =
        stringSetPreferencesKey(SERVER_MODEL_HIDDEN_PREFIX + serverId)

    /**
     * 选定的语言代码（例如 "en"、"ru"、"de"），空字符串表示系统默认。
     */
    val appLanguage: Flow<String> = dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: ""
    }

    /**
     * 选定的主题："system"、"light" 或 "dark"。
     */
    val appTheme: Flow<String> = dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: "system"
    }

    /**
     * 设置应用语言。传入空字符串以使用系统默认。
     * 同时写入 SharedPreferences 以便在 attachBaseContext 中同步读取。
     */
    suspend fun setAppLanguage(languageCode: String) {
        context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LOCALE_PREFS_KEY, languageCode)
            .apply()
        dataStore.edit { preferences ->
            preferences[LANGUAGE_KEY] = languageCode
        }
    }

    /**
     * 设置应用主题。有效值："system"、"light"、"dark"。
     */
    suspend fun setAppTheme(theme: String) {
        dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }

    /**
     * 是否启用动态取色（Material You）。默认：true。
     */
    val dynamicColor: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[DYNAMIC_COLOR_KEY] ?: true
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR_KEY] = enabled
        }
    }

    /**
     * 聊天字体大小："small"、"medium"、"large"。默认："medium"。
     */
    val chatFontSize: Flow<String> = dataStore.data.map { preferences ->
        preferences[FONT_SIZE_KEY] ?: "medium"
    }

    suspend fun setChatFontSize(size: String) {
        dataStore.edit { preferences ->
            preferences[FONT_SIZE_KEY] = size
        }
    }

    /**
     * 聊天密度："normal" 或 "compact"。默认："normal"。
     *
     * 迁移：当 [CHAT_DENSITY_KEY] 未设置时，从旧版
     * [chatFontSize]（"small" → compact）和 [compactMessages]（true → compact）推导。
     * 一旦用户选择了值，该键即被设置，旧版字段将被忽略。
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

    suspend fun setChatDensity(value: String) {
        dataStore.edit { preferences ->
            preferences[CHAT_DENSITY_KEY] = value
        }
    }

    /**
     * 是否启用任务完成通知。默认：true。
     */
    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[NOTIFICATIONS_KEY] ?: true
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[NOTIFICATIONS_KEY] = enabled
        }
    }

    /** 初始加载的消息数量。默认：30。 */
    val initialMessageCount: Flow<Int> = dataStore.data.map { preferences ->
        preferences[INITIAL_MESSAGE_COUNT_KEY] ?: 30
    }

    suspend fun setInitialMessageCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[INITIAL_MESSAGE_COUNT_KEY] = count
        }
    }

    /** 快捷新建会话对话框中显示的最近目录数量。默认：20，限制在 5..50。 */
    val recentDirectoryCount: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[RECENT_DIRECTORY_COUNT_KEY] ?: 20).coerceIn(5, 50)
    }

    suspend fun setRecentDirectoryCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[RECENT_DIRECTORY_COUNT_KEY] = count.coerceIn(5, 50)
        }
    }

    /**
     * 代码块是否自动换行（true）或水平滚动（false）。默认：false。
     */
    val codeWordWrap: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[CODE_WORD_WRAP_KEY] ?: false
    }

    suspend fun setCodeWordWrap(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CODE_WORD_WRAP_KEY] = enabled
        }
    }

    /**
     * 发送消息前是否显示确认对话框。默认：false。
     */
    val confirmBeforeSend: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[CONFIRM_BEFORE_SEND_KEY] ?: false
    }

    suspend fun setConfirmBeforeSend(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CONFIRM_BEFORE_SEND_KEY] = enabled
        }
    }

    /**
     * 是否启用 AMOLED 纯黑深色主题。默认：false。
     */
    val amoledDark: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[AMOLED_DARK_KEY] ?: false
    }

    suspend fun setAmoledDark(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AMOLED_DARK_KEY] = enabled
        }
    }

    /**
     * 是否启用紧凑消息间距。默认：false。
     */
    val compactMessages: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[COMPACT_MESSAGES_KEY] ?: false
    }

    suspend fun setCompactMessages(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COMPACT_MESSAGES_KEY] = enabled
        }
    }

    /**
     * 工具卡片是否默认折叠。默认：false。
     */
    val collapseTools: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[COLLAPSE_TOOLS_KEY] ?: false
    }

    suspend fun setCollapseTools(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COLLAPSE_TOOLS_KEY] = enabled
        }
    }

    /**
     * 推理块是否默认展开。默认：false（折叠）。
     */
    val expandReasoning: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[EXPAND_REASONING_KEY] ?: false
    }

    suspend fun setExpandReasoning(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[EXPAND_REASONING_KEY] = enabled
        }
    }

    /**
     * 是否在同一轮次的消息之间显示分隔线。默认：true。
     */
    val showTurnDividers: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SHOW_TURN_DIVIDERS_KEY] ?: true
    }

    suspend fun setShowTurnDividers(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_TURN_DIVIDERS_KEY] = enabled
        }
    }

    /**
     * 是否启用触感反馈。默认：true。
     */
    val hapticFeedback: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[HAPTIC_FEEDBACK_KEY] ?: true
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[HAPTIC_FEEDBACK_KEY] = enabled
        }
    }

    /**
     * 重连模式："aggressive"（1-5s）、"normal"（1-30s）、"conservative"（1-60s）。
     * 默认："normal"。
     */
    val reconnectMode: Flow<String> = dataStore.data.map { preferences ->
        preferences[RECONNECT_MODE_KEY] ?: "normal"
    }

    suspend fun setReconnectMode(mode: String) {
        dataStore.edit { preferences ->
            preferences[RECONNECT_MODE_KEY] = mode
        }
    }

    /**
     * 流式传输期间是否保持屏幕常亮。默认：false。
     */
    val keepScreenOn: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEEP_SCREEN_ON_KEY] ?: false
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEEP_SCREEN_ON_KEY] = enabled
        }
    }

    /**
     * 通知是否静默（无声音/振动）。默认：false。
     */
    val silentNotifications: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SILENT_NOTIFICATIONS_KEY] ?: false
    }

    suspend fun setSilentNotifications(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SILENT_NOTIFICATIONS_KEY] = enabled
        }
    }

    /**
     * 图片附件发送前是否优化（缩放 + WebP）。默认：true。
     */
    val compressImageAttachments: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[COMPRESS_IMAGE_ATTACHMENTS_KEY] ?: true
    }

    suspend fun setCompressImageAttachments(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[COMPRESS_IMAGE_ATTACHMENTS_KEY] = enabled
        }
    }

    /**
     * 发送前缩放图片附件时使用的最大长边（像素）。
     * 使用 0 保留原始分辨率。默认：1440。
     */
    val imageAttachmentMaxLongSide: Flow<Int> = dataStore.data.map { preferences ->
        val value = preferences[IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY] ?: 1440
        if (value <= 0) 0 else value.coerceIn(720, 4096)
    }

    suspend fun setImageAttachmentMaxLongSide(px: Int) {
        dataStore.edit { preferences ->
            preferences[IMAGE_ATTACHMENT_MAX_LONG_SIDE_KEY] = if (px <= 0) 0 else px.coerceIn(720, 4096)
        }
    }

    /**
     * 图片附件优化使用的 WebP 质量。默认：60。
     */
    val imageAttachmentWebpQuality: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[IMAGE_ATTACHMENT_WEBP_QUALITY_KEY] ?: 60).coerceIn(1, 100)
    }

    suspend fun setImageAttachmentWebpQuality(quality: Int) {
        dataStore.edit { preferences ->
            preferences[IMAGE_ATTACHMENT_WEBP_QUALITY_KEY] = quality.coerceIn(1, 100)
        }
    }

    /**
     * 默认终端字体大小（sp）。默认：13。
     */
    val terminalFontSize: Flow<Float> = dataStore.data.map { preferences ->
        (preferences[TERMINAL_FONT_SIZE_KEY] ?: 13f).coerceIn(6f, 20f)
    }

    suspend fun setTerminalFontSize(size: Float) {
        dataStore.edit { preferences ->
            preferences[TERMINAL_FONT_SIZE_KEY] = size.coerceIn(6f, 20f)
        }
    }

    /**
     * 某服务器隐藏的模型键。键格式："providerId:modelId"。
     */
    fun hiddenModels(serverId: String): Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[serverModelHiddenKey(serverId)] ?: emptySet()
    }

    /**
     * 设置某服务器的模型可见性。
     * visible=true 从隐藏集合移除，visible=false 添加。
     */
    suspend fun setModelVisibility(serverId: String, providerId: String, modelId: String, visible: Boolean) {
        val key = "$providerId:$modelId"
        val prefsKey = serverModelHiddenKey(serverId)
        dataStore.edit { preferences ->
            val current = preferences[prefsKey] ?: emptySet()
            preferences[prefsKey] = if (visible) {
                current - key
            } else {
                current + key
            }
        }
    }

    private fun sessionCategoryAssignmentsKey(serverId: String) =
        stringPreferencesKey(SESSION_CATEGORY_ASSIGNMENTS_PREFIX + serverId)

    /** 用户自定义的会话分类全局列表。 */
    val sessionCategories: Flow<List<SessionCategory>> = dataStore.data.map { preferences ->
        val json = preferences[SESSION_CATEGORIES_KEY]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { categoryJson.decodeFromString(categoryListSerializer, json) }
                .getOrDefault(emptyList())
        }
    }

    /** 按服务器的 会话→分类 id 分配。 */
    fun sessionCategoryAssignments(serverId: String): Flow<Map<String, String>> =
        dataStore.data.map { preferences ->
            val json = preferences[sessionCategoryAssignmentsKey(serverId)]
            if (json.isNullOrBlank()) {
                emptyMap()
            } else {
                runCatching { categoryJson.decodeFromString(assignmentMapSerializer, json) }
                    .getOrDefault(emptyMap())
            }
        }

    /** 添加或替换分类（按 id 匹配）。 */
    suspend fun addSessionCategory(category: SessionCategory) {
        dataStore.edit { preferences ->
            val current = preferences[SESSION_CATEGORIES_KEY]?.let {
                runCatching { categoryJson.decodeFromString(categoryListSerializer, it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            val updated = current.filterNot { it.id == category.id } + category
            preferences[SESSION_CATEGORIES_KEY] = categoryJson.encodeToString(categoryListSerializer, updated)
        }
    }

    /** 移除分类并清除引用它的所有分配。 */
    suspend fun removeSessionCategory(categoryId: String) {
        dataStore.edit { preferences ->
            val current = preferences[SESSION_CATEGORIES_KEY]?.let {
                runCatching { categoryJson.decodeFromString(categoryListSerializer, it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            preferences[SESSION_CATEGORIES_KEY] =
                categoryJson.encodeToString(categoryListSerializer, current.filterNot { it.id == categoryId })
        }
    }

    /** 为给定服务器将一个会话分配到某分类。 */
    suspend fun assignSessionCategory(serverId: String, sessionId: String, categoryId: String) {
        val prefsKey = sessionCategoryAssignmentsKey(serverId)
        dataStore.edit { preferences ->
            val current = preferences[prefsKey]?.let {
                runCatching { categoryJson.decodeFromString(assignmentMapSerializer, it) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            preferences[prefsKey] =
                categoryJson.encodeToString(assignmentMapSerializer, current + (sessionId to categoryId))
        }
    }

    /** 移除给定服务器中某会话的分类分配。 */
    suspend fun unassignSessionCategory(serverId: String, sessionId: String) {
        val prefsKey = sessionCategoryAssignmentsKey(serverId)
        dataStore.edit { preferences ->
            val current = preferences[prefsKey]?.let {
                runCatching { categoryJson.decodeFromString(assignmentMapSerializer, it) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            preferences[prefsKey] =
                categoryJson.encodeToString(assignmentMapSerializer, current - sessionId)
        }
    }

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
            codeWordWrap = prefs[CODE_WORD_WRAP_KEY] ?: false,
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

    // ============ 跨服务器会话收藏 ============

    private fun favoriteSessionsKey(serverId: String) =
        stringSetPreferencesKey(FAVORITE_SESSIONS_PREFIX + serverId)

    /** 特定服务器收藏的会话 id。 */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[favoriteSessionsKey(serverId)] ?: emptySet()
    }

    /** 全局跨服务器收藏顺序——"serverId:sessionId" 键的列表。 */
    val crossServerFavoriteOrder: Flow<List<String>> = dataStore.data.map { preferences ->
        val json = preferences[CROSS_SERVER_FAVORITE_ORDER_KEY]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { categoryJson.decodeFromString(favoriteOrderSerializer, json) }
                .getOrDefault(emptyList())
        }
    }

    /** 以 "serverId:sessionId" 为键的离线快照。 */
    val favoriteSessionSnapshots: Flow<Map<String, FavoriteSessionSnapshot>> =
        dataStore.data.map { preferences ->
            val json = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]
            if (json.isNullOrBlank()) {
                emptyMap()
            } else {
                runCatching { categoryJson.decodeFromString(favoriteSnapshotMapSerializer, json) }
                    .getOrDefault(emptyMap())
            }
        }

    /** 将某会话加入服务器的收藏，并持久化其离线快照。 */
    suspend fun addFavoriteSession(
        serverId: String,
        sessionId: String,
        snapshot: FavoriteSessionSnapshot,
    ) {
        val key = favoriteKey(serverId, sessionId)
        dataStore.edit { preferences ->
            val favKey = favoriteSessionsKey(serverId)
            preferences[favKey] = (preferences[favKey] ?: emptySet()) + sessionId
            val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
                runCatching { categoryJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
                categoryJson.encodeToString(favoriteSnapshotMapSerializer, snaps + (key to snapshot))
        }
    }

    /** 将某会话从服务器收藏移除，并清除其快照。 */
    suspend fun removeFavoriteSession(serverId: String, sessionId: String) {
        val key = favoriteKey(serverId, sessionId)
        dataStore.edit { preferences ->
            val favKey = favoriteSessionsKey(serverId)
            val current = preferences[favKey] ?: emptySet()
            if (sessionId in current) {
                preferences[favKey] = current - sessionId
            }
            val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
                runCatching { categoryJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            if (key in snaps) {
                preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
                    categoryJson.encodeToString(favoriteSnapshotMapSerializer, snaps - key)
            }
        }
    }

    /** 替换整个跨服务器收藏顺序。 */
    suspend fun setCrossServerFavoriteOrder(order: List<String>) {
        dataStore.edit { preferences ->
            preferences[CROSS_SERVER_FAVORITE_ORDER_KEY] =
                categoryJson.encodeToString(favoriteOrderSerializer, order)
        }
    }

    /** 在跨服务器顺序列表中 upsert 或移除单个收藏键。 */
    suspend fun setCrossServerFavoriteOrderItem(key: String, favorite: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[CROSS_SERVER_FAVORITE_ORDER_KEY]?.let {
                runCatching { categoryJson.decodeFromString(favoriteOrderSerializer, it) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            val updated = if (favorite) {
                if (key in current) current else current + key
            } else {
                current - key
            }
            preferences[CROSS_SERVER_FAVORITE_ORDER_KEY] =
                categoryJson.encodeToString(favoriteOrderSerializer, updated)
        }
    }

    /** 保存或替换 (server, session) 对的快照。 */
    suspend fun saveFavoriteSessionSnapshot(
        serverId: String,
        sessionId: String,
        snapshot: FavoriteSessionSnapshot,
    ) {
        val key = favoriteKey(serverId, sessionId)
        dataStore.edit { preferences ->
            val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
                runCatching { categoryJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
                categoryJson.encodeToString(favoriteSnapshotMapSerializer, snaps + (key to snapshot))
        }
    }

    /** 清除 (server, session) 对的快照。 */
    suspend fun clearFavoriteSessionSnapshot(serverId: String, sessionId: String) {
        val key = favoriteKey(serverId, sessionId)
        dataStore.edit { preferences ->
            val snaps = preferences[FAVORITE_SESSION_SNAPSHOTS_KEY]?.let {
                runCatching { categoryJson.decodeFromString(favoriteSnapshotMapSerializer, it) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
            if (key in snaps) {
                preferences[FAVORITE_SESSION_SNAPSHOTS_KEY] =
                    categoryJson.encodeToString(favoriteSnapshotMapSerializer, snaps - key)
            }
        }
    }
}
