package dev.leonardo.ocbeacon.domain.model

/**
 * Pure-function Finite State Machine for session status.
 *
 * Two-layer architecture:
 * - Layer 1 (Core): Idle / Busy / Retry — mirrors server's SessionStatus
 * - Layer 2 (Activity): Waiting / Streaming / ToolCalling / Compacting — derived detail
 *
 * Statelessness: This object holds no mutable state. All state lives in
 * [dev.leonardo.ocbeacon.data.repository.SessionStateService]'s Map<sessionId, SessionFSMState>.
 *
 * Testability: transition() is a pure function — given (state, event), always
 * produces the same TransitionResult. No side effects.
 */
object SessionStateFSM {

    data class TransitionResult(
        val newState: SessionFSMState,
        /** True if the transition indicates a likely lost SSE event (e.g., Activity event in Idle state) */
        val isSuspicious: Boolean,
        /** True if incomplete message markers should be force-completed (e.g., abort, REST confirms Idle) */
        val forceComplete: Boolean
    )

    fun transition(state: SessionFSMState, event: FsmEvent): TransitionResult {
        val now = System.currentTimeMillis()
        return when (event) {
            // === Core events ===
            FsmEvent.ClientSendParts -> clientSendParts(state, now)
            FsmEvent.ClientAbort -> toIdle(state, now, forceComplete = true)
            is FsmEvent.SseStatus -> handleSseStatus(state, event.status, now)
            FsmEvent.SseIdle -> toIdle(state, now, forceComplete = true)
            is FsmEvent.SseError -> toIdle(state, now, forceComplete = true)
            is FsmEvent.RestValidation -> restValidation(state, event.status, now)

            // === Activity events (session.next.*) ===
            FsmEvent.StepStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Waiting) }
            FsmEvent.TextStarted -> activityEvent(state, now) { it.copy(activity = SessionActivity.Streaming) }
            is FsmEvent.TextDelta -> activityEvent(state, now) {
                if (it.activity is SessionActivity.Streaming) it else it.copy(activity = SessionActivity.Streaming)
            }
            FsmEvent.TextEnded -> activityEvent(state, now) { it.copy(activity = SessionActivity.Waiting) }
            is FsmEvent.ToolInputStarted -> activityEvent(state, now) {
                it.copy(activity = SessionActivity.ToolCalling(event.toolName, event.callId))
            }
            is FsmEvent.StepEnded -> stepEnded(state, event.finish, now)
            FsmEvent.CompactionStarted -> activityEvent(state, now) {
                it.copy(activity = SessionActivity.Compacting(savedActivity = it.activity))
            }
            FsmEvent.CompactionEnded -> activityEvent(state, now) {
                it.copy(activity = (it.activity as? SessionActivity.Compacting)?.savedActivity)
            }
        }
    }

    private fun clientSendParts(state: SessionFSMState, now: Long): TransitionResult = when (state.core) {
        is SessionStatus.Idle -> TransitionResult(
            newState = state.copy(
                core = SessionStatus.Busy,
                activity = SessionActivity.Waiting,
                lastEventAt = now,
                lastCoreTransitionAt = now
            ),
            isSuspicious = false,
            forceComplete = false
        )
        else -> TransitionResult(state.copy(lastEventAt = now), isSuspicious = false, forceComplete = false)
    }

    private fun toIdle(state: SessionFSMState, now: Long, forceComplete: Boolean): TransitionResult = TransitionResult(
        newState = state.copy(
            core = SessionStatus.Idle,
            activity = null,
            savedActivity = null,
            lastEventAt = now,
            lastCoreTransitionAt = now
        ),
        isSuspicious = false,
        forceComplete = forceComplete
    )

    private fun handleSseStatus(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult = when (status) {
        is SessionStatus.Busy -> {
            val isTransition = state.core !is SessionStatus.Busy
            TransitionResult(
                newState = state.copy(
                    core = SessionStatus.Busy,
                    activity = if (isTransition) SessionActivity.Waiting else state.activity,
                    lastEventAt = now,
                    lastCoreTransitionAt = if (isTransition) now else state.lastCoreTransitionAt
                ),
                isSuspicious = false,
                forceComplete = false
            )
        }
        is SessionStatus.Idle -> toIdle(state, now, forceComplete = true)
        is SessionStatus.Retry -> TransitionResult(
            newState = state.copy(
                core = status,
                activity = null,
                savedActivity = null,
                lastEventAt = now,
                lastCoreTransitionAt = now
            ),
            isSuspicious = false,
            forceComplete = false
        )
    }

    private fun restValidation(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult = TransitionResult(
        newState = state.copy(
            core = status,
            activity = if (status is SessionStatus.Busy) SessionActivity.Waiting else null,
            savedActivity = null,
            lastEventAt = now,
            lastCoreTransitionAt = now
        ),
        isSuspicious = false,
        forceComplete = status is SessionStatus.Idle
    )

    /**
     * Activity events: valid only when Core is Busy; otherwise suspicious (likely missed Busy).
     */
    private inline fun activityEvent(
        state: SessionFSMState,
        now: Long,
        update: (SessionFSMState) -> SessionFSMState
    ): TransitionResult = if (state.core is SessionStatus.Busy) {
        TransitionResult(update(state).copy(lastEventAt = now), isSuspicious = false, forceComplete = false)
    } else {
        TransitionResult(state.copy(lastEventAt = now), isSuspicious = true, forceComplete = false)
    }

    private fun stepEnded(state: SessionFSMState, finish: String?, now: Long): TransitionResult {
        if (state.core !is SessionStatus.Busy) {
            return TransitionResult(state.copy(lastEventAt = now), isSuspicious = true, forceComplete = false)
        }
        val newActivity = if (finish == "tool-calls") SessionActivity.Waiting else state.activity
        return TransitionResult(state.copy(activity = newActivity, lastEventAt = now), isSuspicious = false, forceComplete = false)
    }
}
