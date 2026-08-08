package dev.leonardo.ocbeacon.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.data.local.LogEntity
import dev.leonardo.ocbeacon.data.local.LogStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())

    val logLevel: Flow<String> = dataStore.data.map { it[logLevelKey] ?: "INFO" }

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
            refresh()
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

    private fun toEntity(entry: DiagnosticLogEntry): LogEntity = LogEntity(
        timestamp = entry.timestamp,
        level = entry.level,
        category = entry.category,
        message = entry.message,
        details = json.encodeToString(entry.details),
        byteSize = entry.estimatedByteSize(json.encodeToString(entry.details)),
    )

    private fun fromEntity(entity: LogEntity): DiagnosticLogEntry = DiagnosticLogEntry(
        timestamp = entity.timestamp,
        level = entity.level,
        category = entity.category,
        message = entity.message,
        details = runCatching {
            json.decodeFromString<Map<String, String>>(entity.details)
        }.getOrDefault(emptyMap()),
    )

    private fun DiagnosticLogEntry.estimatedByteSize(encodedDetails: String): Int =
        (level.length + category.length + message.length + encodedDetails.length) * 2 + 32

    private suspend fun refresh() {
        _entries.value = logStore.latest().map(::fromEntity).asReversed()
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
            return value
                .replace(Regex("(?im)^(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=].*$"), "$1: [REDACTED]")
                .replace(Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n,]+"), "$1[REDACTED]")
                .replace(Regex("(?i)\\b(bearer|basic)\\s+[^\\s,]+"), "$1 [REDACTED]")
                .replace(Regex("(?i)(password|passwd|secret|client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|oauth[_-]?code|code[_-]?verifier|code[_-]?challenge|credential)(\\s*[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;&\"'}]+"), "$1$2[REDACTED]")
                .replace(Regex("(?i)([?&](?:code|state|code_challenge|code_verifier|access_token|refresh_token|api_key|key)=)[^&\\s]+"), "$1[REDACTED]")
                .replace(Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[REDACTED]@")
                .replace(Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?![A-Za-z0-9])"), "[IP]")
                .replace(
                    Regex("(?i)(?<![A-F0-9:])(?:(?:[A-F0-9]{1,4}:){4,7}[A-F0-9]{0,4}|(?:[A-F0-9]{0,4}:){1,7}:[A-F0-9]{0,4})(?![A-F0-9:])"),
                    "[IP]",
                )
                .replace(Regex("(?:/home/|/Users/|/build/)[^\\s,;]+"), "[PATH]")
                .replace(Regex("(?i)[A-Z]:\\\\Users\\\\[^\\s,;]+"), "[PATH]")
                .take(1000)
        }

        internal fun sanitizeEntry(entry: DiagnosticLogEntry): DiagnosticLogEntry = entry.copy(
            category = sanitize(entry.category),
            message = sanitize(entry.message),
            details = entry.details.entries.take(MAX_DETAIL_FIELDS)
                .associate { sanitize(it.key) to sanitize(it.value) },
        )

        private const val MAX_DETAIL_FIELDS = 20
    }
}
