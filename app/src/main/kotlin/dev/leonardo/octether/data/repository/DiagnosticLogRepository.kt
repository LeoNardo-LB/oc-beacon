package dev.leonardo.octether.data.repository

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * A single diagnostic log entry persisted in the local SQLite database.
 *
 * @param timestamp Epoch milliseconds.
 * @param level     Log level string: `ERROR`, `WARN`, `INFO`, `DEBUG`, or `FATAL` (crash).
 * @param category  Source tag / component name (e.g. `"SSE"`, `"REST"`, `"Uncaught exception"`).
 * @param message   Human-readable summary.
 * @param details   Structured key-value pairs (stack trace, cause, thread name, …).
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
 * Persistent diagnostic log repository backed by [DiagnosticLogDatabase] (SQLiteOpenHelper).
 *
 * All writes go through [sanitize] to strip credentials, tokens, IP addresses and
 * local file paths before persistence. The [entries] flow exposes the most recent
 * 1000 sanitized entries for UI display.
 *
 * The persistent log level ([logLevel]) controls which levels [AppLogger] persists.
 */
@Singleton
class DiagnosticLogRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    @ApplicationContext private val context: Context,
) {
    private var database = DiagnosticLogDatabase(context, json)
    private val logLevelKey = stringPreferencesKey("diagnostic_log_level")
    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())

    val logLevel: Flow<String> = dataStore.data.map { it[logLevelKey] ?: "INFO" }

    val entries: Flow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    /** Load entries from the database into [_entries]. Called once at app start. */
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
            withDatabaseRecovery { it.insert(entries.map(::sanitizeEntry)) }
            refresh()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            withDatabaseRecovery { it.clear() }
            refresh()
        }
    }

    suspend fun setLogLevel(level: String) {
        dataStore.edit { it[logLevelKey] = level.takeIf { value -> value in LOG_LEVELS } ?: "INFO" }
    }

    // ---- Sanitization ----------------------------------------------------

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
         * Privacy-aware redaction of a single string field.
         *
         * Strips: HTTP auth headers, bearer/basic tokens, password/secret/key fields,
         * OAuth query params, URL credentials, IPv4/IPv6 addresses and local user paths.
         * Each field is truncated to 1000 characters.
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

    private fun refresh() {
        _entries.value = withDatabaseRecovery { it.latest() }
    }

    @Synchronized
    private fun <T> withDatabaseRecovery(block: (DiagnosticLogDatabase) -> T): T {
        return try {
            block(database)
        } catch (error: SQLiteException) {
            runCatching { database.close() }
            context.deleteDatabase(DiagnosticLogDatabase.DATABASE_NAME)
            database = DiagnosticLogDatabase(context, json)
            block(database)
        }
    }
}
