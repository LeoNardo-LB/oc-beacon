package dev.leonardo.octether.data.api

/**
 * Lightweight session-status snapshot extracted from the REST `GET /session` polling
 * response — only the fields needed to correct SSE-derived state.
 *
 * Used for REST-based state correction when SSE events may have been missed.
 *
 * @property type one of `"idle"`, `"busy"`, `"retry"`.
 * @property attempt retry attempt number; non-null only when [type] == `"retry"`.
 * @property message optional human-readable status detail.
 * @property next optional epoch-millis timestamp of the next scheduled attempt.
 */
data class RestSessionStatusInfo(
    val type: String,          // "idle" | "busy" | "retry"
    val attempt: Int? = null,  // only for "retry"
    val message: String? = null,
    val next: Long? = null
)
