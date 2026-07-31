package dev.leonardo.octether.domain.model

/**
 * Layer 2 Activity — derived state, only meaningful when Core = Busy.
 * Used for UI feedback and fine-grained staleness detection.
 */
sealed class SessionActivity {
    /** Busy just started, waiting for assistant message creation */
    data object Waiting : SessionActivity()

    /** Receiving text stream (text.started ~ text.ended) */
    data object Streaming : SessionActivity()

    /** Tool call in progress (tool.input.started ~ tool.success/failed) */
    data class ToolCalling(
        val toolName: String?,
        val callId: String?
    ) : SessionActivity()

    /** Context compaction in progress; saves the prior activity to restore on CompactionEnded */
    data class Compacting(val savedActivity: SessionActivity?) : SessionActivity()
}

/**
 * Complete FSM state for a single session.
 *
 * @param core Layer 1 status — mirrors server's SessionStatus (Idle/Busy/Retry)
 * @param activity Layer 2 activity detail (only non-null when core is Busy)
 * @param lastEventAt Timestamp of the last SSE event received (for L2 staleness detection)
 * @param lastCoreTransitionAt Timestamp of the last Core status change
 * @param savedActivity Activity saved before Compacting (restored on CompactionEnded)
 */
data class SessionFSMState(
    val core: SessionStatus,
    val activity: SessionActivity?,
    val lastEventAt: Long,
    val lastCoreTransitionAt: Long,
    val savedActivity: SessionActivity? = null
) {
    companion object {
        fun initial(now: Long = System.currentTimeMillis()): SessionFSMState = SessionFSMState(
            core = SessionStatus.Idle,
            activity = null,
            lastEventAt = now,
            lastCoreTransitionAt = now
        )
    }
}

/**
 * Events that drive FSM transitions.
 */
sealed class FsmEvent {
    // === Core events ===
    data class SseStatus(val status: SessionStatus) : FsmEvent()
    data object SseIdle : FsmEvent()
    data class SseError(val message: String) : FsmEvent()
    data object ClientSendParts : FsmEvent()
    data object ClientAbort : FsmEvent()
    data class RestValidation(val status: SessionStatus) : FsmEvent()

    // === Activity events (session.next.*) ===
    data object StepStarted : FsmEvent()
    data object TextStarted : FsmEvent()
    data class TextDelta(val delta: String) : FsmEvent()
    data object TextEnded : FsmEvent()
    data class ToolInputStarted(val toolName: String?, val callId: String?) : FsmEvent()
    data class StepEnded(val finish: String?) : FsmEvent()
    data object CompactionStarted : FsmEvent()
    data object CompactionEnded : FsmEvent()
}
