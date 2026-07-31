package dev.leonardo.octether.domain.model

/** Immutable record of one FSM transition, for traceability/diagnostics. */
data class TransitionRecord(
    val sessionId: String,
    val timestamp: Long,
    val event: String,
    val fromCore: String,
    val toCore: String,
    val fromActivity: String?,
    val toActivity: String?,
    val isSuspicious: Boolean,
    val reason: String?,
)
