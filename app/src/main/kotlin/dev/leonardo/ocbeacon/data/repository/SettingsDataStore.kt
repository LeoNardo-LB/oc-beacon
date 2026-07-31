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
 * App-wide settings stored in DataStore.
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
        private val SHOW_LOCAL_RUNTIME_KEY = booleanPreferencesKey("show_local_runtime")
        private val TERMINAL_FONT_SIZE_KEY = floatPreferencesKey("terminal_font_size")
        private val LOCAL_SETUP_COMPLETED_KEY = booleanPreferencesKey("local_setup_completed")
        private val LOCAL_PROXY_ENABLED_KEY = booleanPreferencesKey("local_proxy_enabled")
        private val LOCAL_PROXY_URL_KEY = stringPreferencesKey("local_proxy_url")
        private val LOCAL_PROXY_NO_PROXY_KEY = stringPreferencesKey("local_proxy_no_proxy")
        private val LOCAL_SERVER_ALLOW_LAN_KEY = booleanPreferencesKey("local_server_allow_lan")
        private val LOCAL_SERVER_USERNAME_KEY = stringPreferencesKey("local_server_username")
        private val LOCAL_SERVER_PASSWORD_KEY = stringPreferencesKey("local_server_password")
        private val LOCAL_SERVER_RUN_IN_BACKGROUND_KEY = booleanPreferencesKey("local_server_run_in_background")
        private val LOCAL_SERVER_AUTO_START_KEY = booleanPreferencesKey("local_server_auto_start")
        private val LOCAL_SERVER_STARTUP_TIMEOUT_SEC_KEY = intPreferencesKey("local_server_startup_timeout_sec")

        /** SharedPreferences name used for synchronous locale reads in attachBaseContext. */
        private const val LOCALE_PREFS = "locale_prefs"
        private const val LOCALE_PREFS_KEY = "app_language"

        private const val SERVER_MODEL_HIDDEN_PREFIX = "server_model_hidden_"

        // Session categories — global list (JSON array) + per-server assignments (JSON map).
        private val SESSION_CATEGORIES_KEY = stringPreferencesKey("session_categories")
        private const val SESSION_CATEGORY_ASSIGNMENTS_PREFIX = "session_category_assignments_"
        private val categoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val categoryListSerializer = ListSerializer(SessionCategory.serializer())
        private val assignmentMapSerializer = MapSerializer(String.serializer(), String.serializer())

        // ============ Cross-server session favorites ============
        // Per-server favorite session ids (stringSet). Prefix + serverId.
        private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"
        // Global cross-server favorite order — list of "serverId:sessionId" keys (JSON).
        private val CROSS_SERVER_FAVORITE_ORDER_KEY = stringPreferencesKey("cross_server_favorite_order")
        // Offline snapshots keyed by "serverId:sessionId" (JSON map).
        private val FAVORITE_SESSION_SNAPSHOTS_KEY = stringPreferencesKey("favorite_session_snapshots")
        private val favoriteSnapshotMapSerializer =
            MapSerializer(String.serializer(), FavoriteSessionSnapshot.serializer())
        private val favoriteOrderSerializer = ListSerializer(String.serializer())

        /**
         * Derive chat density from legacy font size / compact flags.
         * Mirrors the logic validated by [SettingsMigrationTest].
         */
        private fun migrateDensity(fontSize: String?, compact: Boolean?): String = when {
            compact == true -> "compact"
            fontSize == "small" -> "compact"
            else -> "normal"
        }

        /** Read stored language synchronously — safe to call before Hilt init. */
        fun getStoredLanguage(context: Context): String {
            return context.getSharedPreferences(LOCALE_PREFS, Context.MODE_PRIVATE)
                .getString(LOCALE_PREFS_KEY, "") ?: ""
        }
    }

    private fun serverModelHiddenKey(serverId: String) =
        stringSetPreferencesKey(SERVER_MODEL_HIDDEN_PREFIX + serverId)

    /**
     * Selected language code (e.g. "en", "ru", "de") or empty string for system default.
     */
    val appLanguage: Flow<String> = dataStore.data.map { preferences ->
        preferences[LANGUAGE_KEY] ?: ""
    }

    /**
     * Selected theme: "system", "light", or "dark".
     */
    val appTheme: Flow<String> = dataStore.data.map { preferences ->
        preferences[THEME_KEY] ?: "system"
    }

    /**
     * Set the app language. Pass empty string to use system default.
     * Also writes to SharedPreferences for synchronous read in attachBaseContext.
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
     * Set the app theme. Valid values: "system", "light", "dark".
     */
    suspend fun setAppTheme(theme: String) {
        dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme
        }
    }

    /**
     * Whether dynamic colors (Material You) are enabled. Default: true.
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
     * Chat font size: "small", "medium", "large". Default: "medium".
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
     * Chat density: "normal" or "compact". Default: "normal".
     *
     * Migration: when [CHAT_DENSITY_KEY] is unset, derives from legacy
     * [chatFontSize] ("small" → compact) and [compactMessages] (true → compact).
     * Once the user picks a value, the key is set and legacy fields are ignored.
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
     * Whether task completion notifications are enabled. Default: true.
     */
    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[NOTIFICATIONS_KEY] ?: true
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[NOTIFICATIONS_KEY] = enabled
        }
    }

    /** Number of messages to load initially. Default: 30. */
    val initialMessageCount: Flow<Int> = dataStore.data.map { preferences ->
        preferences[INITIAL_MESSAGE_COUNT_KEY] ?: 30
    }

    suspend fun setInitialMessageCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[INITIAL_MESSAGE_COUNT_KEY] = count
        }
    }

    /** Number of recent directories shown in the quick new-session dialog. Default: 20, clamped to 5..50. */
    val recentDirectoryCount: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[RECENT_DIRECTORY_COUNT_KEY] ?: 20).coerceIn(5, 50)
    }

    suspend fun setRecentDirectoryCount(count: Int) {
        dataStore.edit { preferences ->
            preferences[RECENT_DIRECTORY_COUNT_KEY] = count.coerceIn(5, 50)
        }
    }

    /**
     * Whether code blocks use word wrap (true) or horizontal scroll (false). Default: false.
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
     * Whether to show confirmation dialog before sending a message. Default: false.
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
     * Whether AMOLED pure black dark theme is enabled. Default: false.
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
     * Whether compact message spacing is enabled. Default: false.
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
     * Whether tool cards are collapsed by default. Default: false.
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
     * Whether reasoning blocks are expanded by default. Default: false (collapsed).
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
     * Whether to show dividers between messages in the same turn. Default: true.
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
     * Whether haptic feedback is enabled. Default: true.
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
     * Reconnect mode: "aggressive" (1-5s), "normal" (1-30s), "conservative" (1-60s).
     * Default: "normal".
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
     * Whether to keep screen on during streaming. Default: false.
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
     * Whether notifications are silent (no sound/vibration). Default: false.
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
     * Whether image attachments are optimized (resize + WebP) before sending. Default: true.
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
     * Max long side (in px) used when resizing image attachments before sending.
     * Use 0 to keep original resolution. Default: 1440.
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
     * WebP quality used for image attachment optimization. Default: 60.
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
     * Whether to show local runtime controls on Home screen. Default: true.
     */
    val showLocalRuntime: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[SHOW_LOCAL_RUNTIME_KEY] ?: true
    }

    suspend fun setShowLocalRuntime(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHOW_LOCAL_RUNTIME_KEY] = enabled
        }
    }

    /**
     * Default terminal font size in sp. Default: 13.
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
     * Whether the local Termux setup has been completed at least once.
     */
    val localSetupCompleted: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LOCAL_SETUP_COMPLETED_KEY] ?: false
    }

    suspend fun setLocalSetupCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SETUP_COMPLETED_KEY] = completed
        }
    }

    /**
     * Whether local runtime should use an outbound proxy. Default: false.
     */
    val localProxyEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LOCAL_PROXY_ENABLED_KEY] ?: false
    }

    suspend fun setLocalProxyEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOCAL_PROXY_ENABLED_KEY] = enabled
        }
    }

    /**
     * Proxy URL for local runtime outbound requests (e.g., http://host:port).
     * Empty string means disabled/not set.
     */
    val localProxyUrl: Flow<String> = dataStore.data.map { preferences ->
        preferences[LOCAL_PROXY_URL_KEY] ?: ""
    }

    suspend fun setLocalProxyUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[LOCAL_PROXY_URL_KEY] = url.trim()
        }
    }

    /**
     * NO_PROXY/NO_PROXY exclusions used by local runtime.
     */
    val localProxyNoProxy: Flow<String> = dataStore.data.map { preferences ->
        preferences[LOCAL_PROXY_NO_PROXY_KEY] ?: LocalServerManager.DEFAULT_NO_PROXY_LIST
    }

    suspend fun setLocalProxyNoProxy(value: String) {
        dataStore.edit { preferences ->
            val normalized = value
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(",")
            preferences[LOCAL_PROXY_NO_PROXY_KEY] = if (normalized.isBlank()) {
                LocalServerManager.DEFAULT_NO_PROXY_LIST
            } else {
                normalized
            }
        }
    }

    /**
     * Whether local runtime should bind to all interfaces (0.0.0.0). Default: false.
     */
    val localServerAllowLan: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LOCAL_SERVER_ALLOW_LAN_KEY] ?: false
    }

    suspend fun setLocalServerAllowLan(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SERVER_ALLOW_LAN_KEY] = enabled
        }
    }

    /**
     * Optional username used by local runtime server auth.
     * Empty means server default username is used.
     */
    val localServerUsername: Flow<String> = dataStore.data.map { preferences ->
        preferences[LOCAL_SERVER_USERNAME_KEY] ?: ""
    }

    suspend fun setLocalServerUsername(value: String) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SERVER_USERNAME_KEY] = value.trim()
        }
    }

    /**
     * Password used by local runtime server (OPENCODE_SERVER_PASSWORD).
     * Empty means unsecured local server.
     */
    val localServerPassword: Flow<String> = dataStore.data.map { preferences ->
        preferences[LOCAL_SERVER_PASSWORD_KEY] ?: ""
    }

    suspend fun setLocalServerPassword(value: String) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SERVER_PASSWORD_KEY] = value.trim()
        }
    }

    /**
     * Whether local runtime start command should run in background via Termux RunCommandService.
     */
    val localServerRunInBackground: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LOCAL_SERVER_RUN_IN_BACKGROUND_KEY] ?: true
    }

    suspend fun setLocalServerRunInBackground(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SERVER_RUN_IN_BACKGROUND_KEY] = enabled
        }
    }

    /**
     * Whether to auto-start local runtime on app launch when it is installed but not running.
     */
    val localServerAutoStart: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LOCAL_SERVER_AUTO_START_KEY] ?: false
    }

    suspend fun setLocalServerAutoStart(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SERVER_AUTO_START_KEY] = enabled
        }
    }

    /**
     * Startup timeout (seconds) for waiting local runtime readiness. Default: 30.
     */
    val localServerStartupTimeoutSec: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[LOCAL_SERVER_STARTUP_TIMEOUT_SEC_KEY] ?: 30).coerceIn(10, 120)
    }

    suspend fun setLocalServerStartupTimeoutSec(value: Int) {
        dataStore.edit { preferences ->
            preferences[LOCAL_SERVER_STARTUP_TIMEOUT_SEC_KEY] = value.coerceIn(10, 120)
        }
    }

    /**
     * Hidden model keys for a server. Key format: "providerId:modelId".
     */
    fun hiddenModels(serverId: String): Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[serverModelHiddenKey(serverId)] ?: emptySet()
    }

    /**
     * Set model visibility for a server.
     * visible=true removes it from hidden set, visible=false adds it.
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

    /** Global list of user-defined session categories. */
    val sessionCategories: Flow<List<SessionCategory>> = dataStore.data.map { preferences ->
        val json = preferences[SESSION_CATEGORIES_KEY]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { categoryJson.decodeFromString(categoryListSerializer, json) }
                .getOrDefault(emptyList())
        }
    }

    /** Per-server session→category id assignments. */
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

    /** Add or replace a category (matched by id). */
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

    /** Remove a category and clear any assignments referencing it. */
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

    /** Assign a session to a category for the given server. */
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

    /** Remove a session's category assignment for the given server. */
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
     * Aggregate all settings into a single [AppSettings] flow.
     * Reads atomically from DataStore preferences — avoids combining 30+ individual flows.
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
            terminalFontSize = (prefs[TERMINAL_FONT_SIZE_KEY] ?: 13f).coerceIn(6f, 20f),
            showLocalRuntime = prefs[SHOW_LOCAL_RUNTIME_KEY] ?: true,
            localSetupCompleted = prefs[LOCAL_SETUP_COMPLETED_KEY] ?: false,
            localProxyEnabled = prefs[LOCAL_PROXY_ENABLED_KEY] ?: false,
            localProxyUrl = prefs[LOCAL_PROXY_URL_KEY] ?: "",
            localProxyNoProxy = prefs[LOCAL_PROXY_NO_PROXY_KEY] ?: LocalServerManager.DEFAULT_NO_PROXY_LIST,
            localServerAllowLan = prefs[LOCAL_SERVER_ALLOW_LAN_KEY] ?: false,
            localServerUsername = prefs[LOCAL_SERVER_USERNAME_KEY] ?: "",
            localServerPassword = prefs[LOCAL_SERVER_PASSWORD_KEY] ?: "",
            localServerRunInBackground = prefs[LOCAL_SERVER_RUN_IN_BACKGROUND_KEY] ?: true,
            localServerAutoStart = prefs[LOCAL_SERVER_AUTO_START_KEY] ?: false,
            localServerStartupTimeoutSec = (prefs[LOCAL_SERVER_STARTUP_TIMEOUT_SEC_KEY] ?: 30).coerceIn(10, 120)
        )
    }

    // ============ Cross-server session favorites ============

    private fun favoriteSessionsKey(serverId: String) =
        stringSetPreferencesKey(FAVORITE_SESSIONS_PREFIX + serverId)

    /** Favorite session ids for a specific server. */
    fun favoriteSessionIds(serverId: String): Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[favoriteSessionsKey(serverId)] ?: emptySet()
    }

    /** Global cross-server favorite order — list of "serverId:sessionId" keys. */
    val crossServerFavoriteOrder: Flow<List<String>> = dataStore.data.map { preferences ->
        val json = preferences[CROSS_SERVER_FAVORITE_ORDER_KEY]
        if (json.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { categoryJson.decodeFromString(favoriteOrderSerializer, json) }
                .getOrDefault(emptyList())
        }
    }

    /** Offline snapshots keyed by "serverId:sessionId". */
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

    /** Add a session to favorites for a server, persisting its offline snapshot. */
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

    /** Remove a session from favorites for a server, clearing its snapshot. */
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

    /** Replace the entire cross-server favorite order. */
    suspend fun setCrossServerFavoriteOrder(order: List<String>) {
        dataStore.edit { preferences ->
            preferences[CROSS_SERVER_FAVORITE_ORDER_KEY] =
                categoryJson.encodeToString(favoriteOrderSerializer, order)
        }
    }

    /** Upsert or remove a single favorite key in the cross-server order list. */
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

    /** Save or replace a snapshot for a (server, session) pair. */
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

    /** Clear a snapshot for a (server, session) pair. */
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
