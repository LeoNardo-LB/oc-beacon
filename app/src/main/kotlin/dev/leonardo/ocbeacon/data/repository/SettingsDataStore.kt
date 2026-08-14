package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.model.TagType
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

        /** #136（D2-L56）收敛决策：镜像与真相源不一致时返回应以 DataStore 为准的值；一致返回 null（无需回写）。 */
        internal fun resolveLanguageMirror(stored: String, mirror: String): String? =
            if (stored != mirror) stored else null

        // ============ 已读状态 keys / 序列化 ============

        private const val SESSION_READ_TIMES_PREFIX = "session_read_times_"
        private const val ALL_READ_PREFIX = "all_read_"
        private const val UNREAD_STATE_V2_MIGRATED_KEY = "unread_state_v2_migrated"
        private val LAST_REPLY_TIME_KEY = stringPreferencesKey("session_last_reply_time")

        private val readTimesJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val readTimesSerializer = MapSerializer(String.serializer(), Long.serializer())

        private fun readTimesKey(serverId: String) = stringPreferencesKey(SESSION_READ_TIMES_PREFIX + serverId)
        private fun allReadKey(serverId: String) = longPreferencesKey(ALL_READ_PREFIX + serverId)

        // ============ 会话标签 keys / 序列化 ============

        private const val TAG_DIAG = "TagDiag"
        private const val SESSION_TAGS_PREFIX = "session_tags_"
        private const val SESSION_TAG_ASSIGNMENTS_PREFIX = "session_tag_assignments_"
        /** 旧收藏 key（迁移源）—— SettingsDataStoreFavorites.kt 历史格式：stringSetPreferencesKey("favorite_sessions_" + serverId)。 */
        private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"

        private val tagJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val tagListSerializer = ListSerializer(Tag.serializer())
        private val assignmentMapSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

        private fun tagsKey(serverId: String) = stringPreferencesKey(SESSION_TAGS_PREFIX + serverId)
        private fun assignmentsKey(serverId: String) = stringPreferencesKey(SESSION_TAG_ASSIGNMENTS_PREFIX + serverId)
        private fun legacyFavoriteKey(serverId: String) = stringSetPreferencesKey(FAVORITE_SESSIONS_PREFIX + serverId)

        /** 内置收藏标签（每服务器固定一个，不可删改）。 */
        fun builtinFavoriteTag(): Tag = Tag(
            id = FAVORITE_TAG_ID,
            name = "收藏",
            color = "amber",
            icon = "star",
            type = TagType.FAVORITE,
            createdAt = 0,
        )
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
            .putString(LOCALE_PREFS_KEY, languageCode)
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
            .getString(LOCALE_PREFS_KEY, "") ?: ""
        resolveLanguageMirror(stored, mirror)?.let { corrected ->
            AppLogger.d("SettingsDataStore", "Language mirror mismatch: prefs=" + mirror + ", datastore=" + stored + " -> restoring mirror")
            context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(LOCALE_PREFS_KEY, corrected)
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
            prefs[COLLAPSE_TOOLS_KEY] = settings.collapseTools
            prefs[EXPAND_REASONING_KEY] = settings.expandReasoning
            prefs[SHOW_TURN_DIVIDERS_KEY] = settings.showTurnDividers
            prefs[NOTIFICATIONS_KEY] = settings.notificationsEnabled
            prefs[SILENT_NOTIFICATIONS_KEY] = settings.silentNotifications
            prefs[HAPTIC_FEEDBACK_KEY] = settings.hapticFeedback
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
            .putString(LOCALE_PREFS_KEY, settings.appLanguage)
            .apply()
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

    // ============ 已读状态 ============

    /** 该服务器的"一键已读"时间戳（服务器 completed）：此前的所有回复都算已读。无记录为 0。 */
    fun allReadAt(serverId: String): Flow<Long> =
        dataStore.data.map { prefs -> prefs[allReadKey(serverId)] ?: 0L }

    /** 一键已读：记录全局已读位置（已知会话最后完成消息的 completed，服务器时刻），消除所有小红点。
     * maxOf 单调保护：全量重同步旧数据/服务器时钟异常导致 globalMax 变小时不回退 allReadAt。 */
    suspend fun markAllSessionsRead(serverId: String, globalMax: Long) {
        dataStore.edit { prefs ->
            prefs[allReadKey(serverId)] = maxOf(prefs[allReadKey(serverId)] ?: 0L, globalMax)
        }
    }

    /** 该服务器各会话的最后已读时间（sessionId → 最后消费的完成消息 completed），用于未读提示判定。 */
    fun sessionReadTimes(serverId: String): Flow<Map<String, Long>> =
        dataStore.data.map { prefs ->
            val json = prefs[readTimesKey(serverId)]
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
        }

    /** 将会话标记为已读（记录最后消费的完成消息 completed，服务器时刻）。
     * maxOf 单调保护：双 VM 乱序写入时已读位置不回退。 */
    suspend fun markSessionRead(serverId: String, sessionId: String, completedTs: Long) {
        dataStore.edit { prefs ->
            val current = prefs[readTimesKey(serverId)]?.let {
                runCatching { readTimesJson.decodeFromString(readTimesSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            prefs[readTimesKey(serverId)] = readTimesJson.encodeToString(
                readTimesSerializer,
                current + (sessionId to maxOf(current[sessionId] ?: 0L, completedTs))
            )
        }
    }

    /** 最后完成回复时间（持久化）：sessionId → 最后完成 assistant 消息的 completed（**服务器时刻**）。
     *  EventDispatcher 后台收集，应用重启后未读红点可恢复。 */
    fun lastCompletedReplyTimes(): Flow<Map<String, Long>> =
        dataStore.data.map { prefs ->
            val json = prefs[LAST_REPLY_TIME_KEY]
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { readTimesJson.decodeFromString(readTimesSerializer, json) }.getOrDefault(emptyMap())
        }

    /** 全量保存最后完成回复时间 map（值域：服务器 completed）。 */
    suspend fun saveLastCompletedReplyTimes(times: Map<String, Long>) {
        dataStore.edit { prefs ->
            prefs[LAST_REPLY_TIME_KEY] = readTimesJson.encodeToString(readTimesSerializer, times)
        }
        AppLogger.d("UnreadDiag", "[persist] saved ${times.size} entries: ${times.entries.take(3)}")
    }

    /**
     * 一次性迁移：清空已读标记（readTimes/allReadAt/旧 lastReplyTime）——值域从客户端 now
     * 变为服务器 completed，旧值不可比。幂等。
     */
    suspend fun runUnreadStateV2Migration() {
        dataStore.edit { prefs ->
            if (prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] == true) return@edit
            val keys = prefs.asMap().keys.filter {
                it.name.startsWith(SESSION_READ_TIMES_PREFIX) ||
                    it.name.startsWith(ALL_READ_PREFIX) ||
                    it == LAST_REPLY_TIME_KEY // 旧客户端 now 域值不可比，迁移时清空（之后复用存服务器域 maxCompleted）
            }
            keys.forEach { prefs.remove(it) }
            prefs[booleanPreferencesKey(UNREAD_STATE_V2_MIGRATED_KEY)] = true
        }
    }

    // ============ 会话标签 ============

    /** 该服务器的标签集（不含内置收藏标签）。 */
    fun sessionTags(serverId: String): Flow<List<Tag>> =
        dataStore.data.map { prefs ->
            val json = prefs[tagsKey(serverId)]
            val tags = if (json.isNullOrBlank()) emptyList()
            else runCatching { tagJson.decodeFromString(tagListSerializer, json) }.getOrDefault(emptyList())
            tags.filter { it.type != TagType.FAVORITE }
        }

    /** 统一分配 map（sessionId → tagIds，含内置收藏标签）。 */
    fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
        dataStore.data.map { prefs ->
            val json = prefs[assignmentsKey(serverId)]
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { tagJson.decodeFromString(assignmentMapSerializer, json) }.getOrDefault(emptyMap())
        }

    suspend fun addSessionTag(serverId: String, tag: Tag) {
        dataStore.edit { prefs ->
            val current = prefs[tagsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tag.id } + tag)
        }
    }

    suspend fun updateSessionTag(serverId: String, tag: Tag) = addSessionTag(serverId, tag)

    suspend fun removeSessionTag(serverId: String, tagId: String) {
        dataStore.edit { prefs ->
            val current = prefs[tagsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
            } ?: emptyList()
            prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tagId })
            // 同一 edit：清理所有会话的该标签分配（原子）
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            if (assignments.values.any { tagId in it }) {
                prefs[assignmentsKey(serverId)] = tagJson.encodeToString(
                    assignmentMapSerializer,
                    assignments.mapValues { (_, ids) -> ids.filterNot { it == tagId } }
                )
            }
        }
        AppLogger.d(TAG_DIAG, "[removeTag] done server=$serverId tag=$tagId")
    }

    suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) {
        dataStore.edit { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val current = assignments[sessionId].orEmpty().filter { it == FAVORITE_TAG_ID } // 保留收藏，只替换 USER 标签
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(
                assignmentMapSerializer,
                assignments + (sessionId to (current + tagIds).distinct())
            )
        }
        AppLogger.d(TAG_DIAG, "[setTags] done server=$serverId session=$sessionId tags=$tagIds")
    }

    suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) {
        dataStore.edit { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val updated = assignments[sessionId].orEmpty().filterNot { it == tagId }
            val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
        }
    }

    suspend fun toggleFavorite(serverId: String, sessionId: String) {
        dataStore.edit { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            val current = assignments[sessionId].orEmpty()
            val updated = if (FAVORITE_TAG_ID in current) {
                current.filterNot { it == FAVORITE_TAG_ID }
            } else {
                current + FAVORITE_TAG_ID
            }
            val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
        }
    }

    /**
     * 收藏会话 id（从统一分配 map 派生）——纯读取，无副作用。
     * #137（D2-L59）：旧实现把一次性迁移（dataStore.edit）藏在 flow map 内——
     * 每次数据发射都检查并可能写库（隐蔽副作用）；迁移改为显式
     * [migrateLegacyFavoritesIfNeeded]，由使用方（SessionListViewModel）在 init 触发。
     */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>> =
        dataStore.data.map { prefs ->
            val assignments = prefs[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            assignments.filterValues { FAVORITE_TAG_ID in it }.keys
        }

    /**
     * #137（D2-L59）：旧独立收藏 key（stringSet）→ 内置标签分配的一次性迁移。
     * 幂等：迁移成功后删除源 key——否则用户取消全部收藏后 fromAssignments 重新变空，
     * 迁移条件再次满足会导致已取消的收藏被重新迁移"复活"（见 SettingsDataStoreTagsTest
     * `favoriteSessionIds migrate then unfavorite all does not resurrect`）。
     */
    suspend fun migrateLegacyFavoritesIfNeeded(serverId: String) {
        dataStore.edit { p ->
            val legacy = p[legacyFavoriteKey(serverId)] ?: return@edit
            if (legacy.isEmpty()) {
                p.remove(legacyFavoriteKey(serverId))
                return@edit
            }
            val cur = p[assignmentsKey(serverId)]?.let {
                runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
            } ?: emptyMap()
            if (cur.values.any { FAVORITE_TAG_ID in it }) {
                // 已有收藏分配——不再迁移（避免覆盖用户新状态）
                p.remove(legacyFavoriteKey(serverId))
                return@edit
            }
            p[assignmentsKey(serverId)] = tagJson.encodeToString(
                assignmentMapSerializer,
                legacy.fold(cur) { acc, sid -> acc + (sid to (acc[sid].orEmpty() + FAVORITE_TAG_ID).distinct()) }
            )
            p.remove(legacyFavoriteKey(serverId))
        }
        AppLogger.d(TAG_DIAG, "[favoriteMigrate] server=" + serverId)
    }
}
