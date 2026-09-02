package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.data.local.LogEntity
import dev.leonardo.ocbeacon.data.local.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import dev.leonardo.ocbeacon.util.runCatchingCancellable

/**
 * 持久化到本地 SQLite 数据库的单条诊断日志条目。
 *
 * @param timestamp Epoch 毫秒。
 * @param level     日志级别字符串：`ERROR`、`WARN`、`INFO`、`DEBUG` 或 `FATAL`（崩溃）。
 * @param category  来源标签 / 组件名（例如 `"SSE"`、`"REST"`、`"Uncaught exception"`）。
 * @param message   人类可读摘要。
 * @param details   结构化键值对（堆栈跟踪、原因、线程名 等）。
 */
@Serializable
data class DiagnosticLogEntry(
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * 由 [LogStore]（Room）支持的持久化诊断日志 repository。
 *
 * 所有写入都经过 [sanitize] 处理，在持久化前剥离凭据、令牌、IP 地址和
 * 本地文件路径。[entries] flow 暴露最近的 1000 条已脱敏条目供 UI 显示。
 *
 * 持久化日志级别（[logLevel]）控制 [AppLogger] 持久化哪些级别。
 */
@Singleton
class DiagnosticLogRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    private val logStore: LogStore,
) {
    private val logLevelKey = stringPreferencesKey("diagnostic_log_level")

    /** #154a：崩溃提示已确认时刻（此前 的 FATAL 不再提示）。 */
    private val crashNoticeAckKey = longPreferencesKey("crash_notice_ack_at")
    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    /** #102（M-3）：刷新节流——recordBatch 每批全量查 latest()（AppLogger 消费 ~6 批/s），
     *  节流为至少 1s 一次；跳过的批由后续批覆盖（诊断页非实时，可接受）。 */
    private val refreshThrottle = java.util.concurrent.atomic.AtomicLong(0L)

    val logLevel: Flow<String> = dataStore.data.map { it[logLevelKey] ?: "INFO" }

    /** #154a：最近一条未确认的崩溃（晚于确认水位才显示；无 → null）。 */
    suspend fun latestUnacknowledgedCrash(): DiagnosticLogEntry? = withContext(Dispatchers.IO) {
        val ack = runCatchingCancellable {
            dataStore.data.first()[crashNoticeAckKey] ?: 0L
        }.getOrDefault(0L)
        logStore.latestFatal()?.let { fromEntity(it) }?.takeIf { it.timestamp > ack }
    }

    /** #154a：确认崩溃提示（水位 = 该崩溃时刻；后续新崩溃仍会提示）。 */
    suspend fun acknowledgeCrashNotice(atMillis: Long) {
        dataStore.edit { prefs ->
            val prev = prefs[crashNoticeAckKey] ?: 0L
            if (atMillis > prev) prefs[crashNoticeAckKey] = atMillis
        }
    }

    val entries: Flow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    /** 从数据库加载条目到 [_entries]。在应用启动时调用一次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        refresh()
    }

    suspend fun record(
        level: String,
        category: String,
        message: String,
        details: Map<String, String> = emptyMap(),
    ) {
        recordBatch(
            listOf(
                DiagnosticLogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    category = category,
                    message = message,
                    details = details,
                ),
            ),
        )
    }

    suspend fun recordBatch(entries: List<DiagnosticLogEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            logStore.insert(entries.map(::sanitizeEntry).map(::toEntity))
            refreshThrottled()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            logStore.clear()
            refresh()
        }
    }

    suspend fun setLogLevel(level: String) {
        dataStore.edit { it[logLevelKey] = level.takeIf { value -> value in LOG_LEVELS } ?: "INFO" }
    }

    // ---- 映射 ----------------------------------------------------

    private fun toEntity(entry: DiagnosticLogEntry): LogEntity {
        val encodedDetails = json.encodeToString(entry.details)
        return LogEntity(
            timestamp = entry.timestamp,
            level = entry.level,
            category = entry.category,
            message = entry.message,
            details = encodedDetails,
            byteSize = entry.estimatedByteSize(encodedDetails),
        )
    }

    private fun fromEntity(entity: LogEntity): DiagnosticLogEntry = DiagnosticLogEntry(
        timestamp = entity.timestamp,
        level = entity.level,
        category = entity.category,
        message = entity.message,
        details = runCatchingCancellable {
            json.decodeFromString<Map<String, String>>(entity.details)
        }.getOrDefault(emptyMap()),
    )

    private fun DiagnosticLogEntry.estimatedByteSize(encodedDetails: String): Int =
        (level.length + category.length + message.length + encodedDetails.length) * 2 + 32

    private suspend fun refresh() {
        _entries.value = logStore.latest().map(::fromEntity).asReversed()
    }

    /** #102（M-3）：刷新节流——至少 [REFRESH_MIN_INTERVAL_MS] 间隔才实际查询。 */
    private suspend fun refreshThrottled() {
        val now = System.currentTimeMillis()
        if (now - refreshThrottle.get() < REFRESH_MIN_INTERVAL_MS) return
        refreshThrottle.set(now)
        refresh()
    }

    // ---- 脱敏 ----------------------------------------------------

    companion object {
        val LOG_LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG")

        fun export(entries: List<DiagnosticLogEntry>): String =
            entries.map(::sanitizeEntry).joinToString("\n\n") { entry ->
                buildString {
                    append(java.time.Instant.ofEpochMilli(entry.timestamp))
                    append(" [${entry.level}] ${entry.category}: ${entry.message}")
                    entry.details.toSortedMap().forEach { (key, value) -> append("\n$key=$value") }
                }
            }

        /**
         * 注重隐私的单字段脱敏。
         *
         * 剥离：HTTP 认证头、bearer/basic 令牌、password/secret/key 字段、
         * OAuth 查询参数、URL 凭据、IPv4/IPv6 地址和本地用户路径。
         * 每个字段截断为 1000 字符。
         */
                internal fun sanitize(value: String): String {
            // #102（M-3）：正则预编译（SANITIZE_REGEX_* 常量）——原每次调用现场编译
            return value
                .replace(SANITIZE_REGEX_0, "$1: [REDACTED]")
                .replace(SANITIZE_REGEX_1, "$1[REDACTED]")
                .replace(SANITIZE_REGEX_2, "$1 [REDACTED]")
                .replace(SANITIZE_REGEX_3, "$1$2[REDACTED]")
                .replace(SANITIZE_REGEX_4, "$1[REDACTED]")
                .replace(SANITIZE_REGEX_5, "$1[REDACTED]@")
                .replace(SANITIZE_REGEX_6, "[IP]")
                .replace(SANITIZE_REGEX_IPV6, "[IP]")
                .replace(SANITIZE_REGEX_7, "[PATH]")
                .replace(SANITIZE_REGEX_8, "[PATH]")
                .take(1000)
        }

        internal fun sanitizeEntry(entry: DiagnosticLogEntry): DiagnosticLogEntry = entry.copy(
            category = sanitize(entry.category),
            message = sanitize(entry.message),
            details = entry.details.entries.take(MAX_DETAIL_FIELDS)
                .associate { sanitize(it.key) to sanitize(it.value) },
        )

        private const val MAX_DETAIL_FIELDS = 20

        /** #102（M-3）：脱敏正则预编译——原每次 sanitize 现场编译 10 个 Regex。 */
        private val SANITIZE_REGEX_0 = Regex("(?im)^(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=].*$")
        private val SANITIZE_REGEX_1 = Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n,]+")
        private val SANITIZE_REGEX_2 = Regex("(?i)\\b(bearer|basic)\\s+[^\\s,]+")
        private val SANITIZE_REGEX_3 = Regex("(?i)(password|passwd|secret|client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|oauth[_-]?code|code[_-]?verifier|code[_-]?challenge|credential)(\\s*[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;&\"'}]+")
        private val SANITIZE_REGEX_4 = Regex("(?i)([?&](?:code|state|code_challenge|code_verifier|access_token|refresh_token|api_key|key)=)[^&\\s]+")
        private val SANITIZE_REGEX_5 = Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@")
        private val SANITIZE_REGEX_6 = Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?![A-Za-z0-9])")
        private val SANITIZE_REGEX_IPV6 = Regex("(?i)(?<![A-F0-9:])(?:(?:[A-F0-9]{1,4}:){4,7}[A-F0-9]{0,4}|(?:[A-F0-9]{0,4}:){1,7}:[A-F0-9]{0,4})(?![A-F0-9:])" )
        private val SANITIZE_REGEX_7 = Regex("(?:/home/|/Users/|/build/)[^\\s,;]+")
        private val SANITIZE_REGEX_8 = Regex("(?i)[A-Z]:\\\\Users\\\\[^\\s,;]+")
        /** #102（M-3）：刷新最小间隔（原每批全量查询）。 */
        private const val REFRESH_MIN_INTERVAL_MS = 1_000L
    }
}
