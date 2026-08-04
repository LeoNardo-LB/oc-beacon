package dev.leonardo.ocbeacon.domain.model

/** 一次 FSM 转移的不可变记录，用于可追溯性/诊断。 */
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
