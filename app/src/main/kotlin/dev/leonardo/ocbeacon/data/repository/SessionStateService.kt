package dev.leonardo.ocbeacon.data.repository

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.di.ApplicationScope
import dev.leonardo.ocbeacon.domain.model.*
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

fun interface DirectoryResolver { fun resolve(sessionId: String): String? }
fun interface IncompleteAssistantChecker { fun hasIncomplete(sessionId: String): Boolean }
fun interface MessageForceCompleter { fun markIdle(sessionId: String) }
fun interface MessageRefresher { fun replaceMessages(sessionId: String, messages: List<MessageWithParts>) }

private const val TAG = "SessionStateService"
private const val HISTORY_MAX = 20
private const val STALENESS_CHECK_INTERVAL_MS = 5_000L
private const val STALENESS_THRESHOLD_MS = 15_000L

/** Result of a full REST → FSM sync, exposed for callers (e.g. manual refresh) to observe. */
data class SyncResult(val totalSessions: Int, val busyCount: Int)

@Singleton
class SessionStateService @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    private val sessionRepoProvider: Provider<SessionRepository>,
) {
    // Injected post-construction (breaks circular dep with EventDispatcher)
    var directoryResolver: DirectoryResolver = DirectoryResolver { null }
    var incompleteChecker: IncompleteAssistantChecker = IncompleteAssistantChecker { false }
    var messageForceCompleter: MessageForceCompleter = MessageForceCompleter {}
    var messageRefresher: MessageRefresher = MessageRefresher { _, _ -> }

    @Volatile private var currentServerId: String? = null

    private var stalenessJob: Job? = null

    /**
     * RS-012 fix: Dedup map for in-flight REST validations. Keyed by sessionId.
     * When a new validation is requested for a session that already has one in flight,
     * the old job is cancelled and replaced. This prevents REST request storms when
     * multiple triggers fire rapidly (staleness guard + suspicious transition + external).
     */
    private val activeValidations = ConcurrentHashMap<String, Job>()

    init { startStalenessGuard() }

    private fun startStalenessGuard() {
        stalenessJob?.cancel()
        stalenessJob = appScope.launch {
            while (isActive) {
                delay(STALENESS_CHECK_INTERVAL_MS)
                checkStaleness()
            }
        }
    }

    private fun checkStaleness() {
        val now = System.currentTimeMillis()
        _fsmStates.value.forEach { (sessionId, state) ->
            if (state.core is SessionStatus.Busy && now - state.lastEventAt > STALENESS_THRESHOLD_MS) {
                Log.w(TAG, "[$sessionId] L2 stale for ${now - state.lastEventAt}ms, triggering REST validation")
                triggerRestValidation(sessionId)
            }
            if (state.core is SessionStatus.Idle && incompleteChecker.hasIncomplete(sessionId)) {
                Log.w(TAG, "[$sessionId] L5 inconsistency: Idle but has incomplete messages")
                triggerRestValidation(sessionId)
            }
        }
    }

    private val _fsmStates = MutableStateFlow<Map<String, SessionFSMState>>(emptyMap())
    private val _histories = MutableStateFlow<Map<String, List<TransitionRecord>>>(emptyMap())

    val statusFlow: StateFlow<Map<String, SessionStatus>> = _fsmStates
        .map { it.mapValues { e -> e.value.core } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    val activityFlow: StateFlow<Map<String, SessionActivity?>> = _fsmStates
        .map { it.mapValues { e -> e.value.activity } }
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    val historyFlow: StateFlow<Map<String, List<TransitionRecord>>> = _histories
        .stateIn(appScope, SharingStarted.Eagerly, emptyMap())

    fun setServerId(serverId: String) { currentServerId = serverId }

    /**
     * Public wrapper for [triggerRestValidation] — lets external callers (e.g.
     * [SessionActionsDelegate] single-session entry/resume) request an authoritative
     * REST status check for one session. The FSM's forceComplete mechanism handles
     * incomplete-message fixing automatically when REST confirms Idle.
     */
    fun requestValidation(sessionId: String) = triggerRestValidation(sessionId)

    // ============ Event entry points ============
    fun onClientSendParts(sessionId: String) {
        applyTransition(sessionId, FsmEvent.ClientSendParts)
    }
    fun onClientAbort(sessionId: String) = applyTransition(sessionId, FsmEvent.ClientAbort)
    fun onRestValidation(sessionId: String, status: SessionStatus) =
        applyTransition(sessionId, FsmEvent.RestValidation(status))

    fun onSseEvent(event: SseEvent, sessionId: String) {
        val fsmEvent = mapSseEventToFsm(event) ?: return
        applyTransition(sessionId, fsmEvent)
    }

    private fun mapSseEventToFsm(event: SseEvent): FsmEvent? = when (event) {
        is SseEvent.SessionStatus -> FsmEvent.SseStatus(event.status)
        is SseEvent.SessionIdle -> FsmEvent.SseIdle
        is SseEvent.SessionError -> FsmEvent.SseError(event.error)
        is SseEvent.SessionNext -> mapSessionNextEvent(event.event)
        else -> null
    }

    private fun mapSessionNextEvent(event: SessionNextEvent): FsmEvent? = when (event) {
        is SessionNextEvent.StepStarted -> FsmEvent.StepStarted
        is SessionNextEvent.TextStarted -> FsmEvent.TextStarted
        is SessionNextEvent.TextDelta -> FsmEvent.TextDelta(event.delta)
        is SessionNextEvent.TextEnded -> FsmEvent.TextEnded
        is SessionNextEvent.ToolInputStarted -> FsmEvent.ToolInputStarted(event.tool, event.callId)
        // SessionNextEvent.StepEnded has no finish field — pass null. FSM treats non-"tool-calls"
        // finish as "keep current Activity, wait for Core Idle".
        is SessionNextEvent.StepEnded -> FsmEvent.StepEnded(finish = null)
        is SessionNextEvent.CompactionStarted -> FsmEvent.CompactionStarted
        is SessionNextEvent.CompactionEnded -> FsmEvent.CompactionEnded
        else -> null
    }

    // ============ Core pipeline ============
    //
    // RS-010 fix: The entire read-compute-write happens inside `.update{}` so that
    // concurrent transitions participate in CAS retries. The previous pattern
    // (read outside, compute outside, write via `.update{}`) lost transitions when
    // two threads read the same stale snapshot and one overwrote the other.
    fun applyTransition(sessionId: String, event: FsmEvent) {
        // Holders for values captured inside the CAS-protected lambda; used by the
        // post-update side effects (history logging, forceComplete, etc.).
        var fromState: SessionFSMState? = null
        var result: SessionStateFSM.TransitionResult? = null
        _fsmStates.update { states ->
            val current = states[sessionId] ?: SessionFSMState.initial()
            val transitionResult = SessionStateFSM.transition(current, event)
            fromState = current
            result = transitionResult
            states + (sessionId to transitionResult.newState)
        }
        val from = fromState!!
        val res = result!!
        recordHistory(sessionId, from, res, event)
        if (BuildConfig.DEBUG) logTransition(sessionId, from, res, event)
        // Side effects
        if (res.forceComplete) messageForceCompleter.markIdle(sessionId)
        if (res.isSuspicious) triggerRestValidation(sessionId)
    }

    private fun recordHistory(sessionId: String, from: SessionFSMState, result: SessionStateFSM.TransitionResult, event: FsmEvent) {
        val record = TransitionRecord(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            event = event::class.simpleName ?: "Unknown",
            fromCore = from.core::class.simpleName ?: "?",
            toCore = result.newState.core::class.simpleName ?: "?",
            fromActivity = from.activity?.let { it::class.simpleName },
            toActivity = result.newState.activity?.let { it::class.simpleName },
            isSuspicious = result.isSuspicious,
            reason = null,
        )
        _histories.update { h ->
            val list = (h[sessionId] ?: emptyList()) + record
            val trimmed = if (list.size > HISTORY_MAX) list.takeLast(HISTORY_MAX) else list
            h + (sessionId to trimmed)
        }
    }

    private fun logTransition(sessionId: String, from: SessionFSMState, result: SessionStateFSM.TransitionResult, event: FsmEvent) {
        val actFrom = from.activity?.let { "/${it::class.simpleName}" } ?: ""
        val actTo = result.newState.activity?.let { "/${it::class.simpleName}" } ?: ""
        val flags = buildString {
            if (result.isSuspicious) append(" [SUSPICIOUS]")
            if (result.forceComplete) append(" [force-complete]")
        }
        Log.d(TAG, "[$sessionId] ${from.core::class.simpleName}$actFrom --${event::class.simpleName}--> ${result.newState.core::class.simpleName}$actTo$flags")
    }

    // ============ Lifecycle ============
    fun clearSession(sessionId: String) {
        _fsmStates.update { it - sessionId }
        _histories.update { it - sessionId }
        // Cancel in-flight REST validation for this session (RS-012)
        activeValidations.remove(sessionId)?.cancel()
    }

    fun clearForServer(sessionIds: Set<String>) {
        _fsmStates.update { it - sessionIds }
        _histories.update { it - sessionIds }
        // Cancel in-flight REST validations for cleared sessions (RS-012)
        for (sessionId in sessionIds) {
            activeValidations.remove(sessionId)?.cancel()
        }
    }

    fun clearAll() {
        // RS-011 fix: use .update{} to participate in CAS, preventing a concurrent
        // applyTransition from resurrecting cleared state via its own CAS write.
        _fsmStates.update { emptyMap() }
        _histories.update { emptyMap() }
        // Cancel all in-flight REST validations (RS-012)
        activeValidations.values.forEach { it.cancel() }
        activeValidations.clear()
    }

    // ============ L3: REST validation (absence=idle closed loop) ============
    //
    // Triggered by:
    //   - applyTransition when result.isSuspicious (lost SSE)
    //   - checkStaleness (L2 stale Busy / L5 Idle-with-incomplete)
    //   - External callers (e.g. manual refresh)
    //
    // Absence semantics: when the queried [directory] is the session's own directory and the
    // session is absent from the server's status map, treat it as Idle (server drops idle
    // sessions from the map). When [directory] is null (unknown instance), absence is ambiguous
    // — skip to avoid false Idle.
    internal fun triggerRestValidation(sessionId: String) {
        val sid = currentServerId ?: return
        val directory = directoryResolver.resolve(sessionId)
        // RS-012 fix: deduplicate concurrent validations for the same session.
        // Pattern: launch → merge (atomic replace, cancels previous) → invokeOnCompletion (cleanup).
        // The merge function only cancels the old job and returns the new one — it does NOT
        // modify activeValidations, avoiding ConcurrentHashMap.compute() deadlock.
        val job = appScope.launch {
            try {
                val result = sessionRepoProvider.get().fetchSessionStatuses(sid, directory)
                result.onSuccess { statuses ->
                    val serverStatus = statuses[sessionId]
                    if (serverStatus != null) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: server says ${serverStatus::class.simpleName}")
                        onRestValidation(sessionId, serverStatus)
                    } else if (directory != null) {
                        // Server deletes idle sessions from its status map — absence means idle.
                        // Only trust this when we queried the session's own directory.
                        if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST validation: absent from own directory -> idle")
                        onRestValidation(sessionId, SessionStatus.Idle)
                    }
                    // directory == null + absent -> skip (avoid false idle on unknown instance)

                    // Also refresh messages — staleness/suspicious recovery should catch up
                    // on any messages SSE missed during the stale period.
                    sessionRepoProvider.get().listMessages(sid, sessionId, limit = 0)
                        .onSuccess { messages ->
                            messageRefresher.replaceMessages(sessionId, messages)
                            if (BuildConfig.DEBUG) Log.d(TAG, "[$sessionId] L3 REST message refresh: ${messages.size} msgs")
                        }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$sessionId] L3 REST validation failed: ${e.message}")
            }
        }
        // Atomically replace any existing job for this session (cancels the old one)
        activeValidations.merge(sessionId, job) { oldJob, newJob ->
            oldJob.cancel()
            newJob
        }
        // Cleanup: remove this job from the dedup map when it completes.
        // invokeOnCompletion fires even if the job is already complete.
        // Uses remove(key, value) so it only removes its own entry, not a newer job's.
        job.invokeOnCompletion { activeValidations.remove(sessionId, job) }
    }

    // ============ L4: Full REST sync (unify recovery) ============
    //
    // Pull every session status from the server across [projects]' directories (or a single
    // instance-wide query when [projects] is empty), then fold in local absence semantics:
    // a local non-Idle session absent from REST is treated as Idle — unless it has incomplete
    // assistant messages, in which case the local state is preserved (SSE may still be streaming).
    //
    // Note: [SessionRepository.fetchSessionStatuses] already maps the raw REST DTO
    // (`RestSessionStatusInfo`) to the domain [SessionStatus], so no per-entry conversion here.
    suspend fun syncFromRest(projects: List<Project>): SyncResult {
        val sid = currentServerId ?: return SyncResult(0, 0)
        val aggregated = mutableMapOf<String, SessionStatus>()
        val dirs: List<String?> = if (projects.isEmpty()) listOf(null) else projects.map { it.worktree }
        for (dir in dirs) {
            sessionRepoProvider.get().fetchSessionStatuses(sid, dir)
                .onSuccess { aggregated += it }
        }
        // Absence semantics: local non-Idle absent from REST
        for ((sessionId, state) in _fsmStates.value) {
            if (state.core !is SessionStatus.Idle && sessionId !in aggregated) {
                aggregated[sessionId] = if (incompleteChecker.hasIncomplete(sessionId)) state.core  // protect (SSE may still stream)
                                         else SessionStatus.Idle                                       // absent = idle
            }
        }
        for ((sessionId, status) in aggregated) applyTransition(sessionId, FsmEvent.RestValidation(status))
        return SyncResult(aggregated.size, aggregated.count { it.value is SessionStatus.Busy })
    }
}

