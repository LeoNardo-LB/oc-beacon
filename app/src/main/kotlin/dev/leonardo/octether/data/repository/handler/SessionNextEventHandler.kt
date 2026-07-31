package dev.leonardo.octether.data.repository.handler

import android.util.Log
import dev.leonardo.octether.BuildConfig
import dev.leonardo.octether.domain.model.SessionNextEvent
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current tool execution progress for a single tool call.
 */
data class ToolProgressInfo(
    val callId: String,
    val partId: String,
    val tool: String,
    val status: String,
    val progress: String? = null,
    val title: String? = null,
    val output: String = ""
)

/**
 * Current step progress.
 */
data class StepProgressInfo(
    val step: Int,
    val agent: String = "",
    val model: String = ""
)

/**
 * Compaction state for a session.
 */
data class CompactionStateInfo(
    val isActive: Boolean,
    val reason: String = ""
)

/**
 * Shell execution state for a session.
 */
data class ShellStateInfo(
    val command: String
)

/**
 * Handles all session.next.* events for real-time status tracking.
 * Manages: agent/model switching, tool progress, step progress,
 * compaction state, and shell state.
 */
@Singleton
class SessionNextEventHandler @Inject constructor() : SseEventHandler {

    companion object {
        private const val TAG = "SessionNextEventHandler"
    }

    // ============ Public State (read-only) ============

    private val _currentAgent = MutableStateFlow<Map<String, String>>(emptyMap())
    val currentAgent: StateFlow<Map<String, String>> = _currentAgent.asStateFlow()

    private val _currentModel = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    val currentModel: StateFlow<Map<String, Pair<String, String>>> = _currentModel.asStateFlow()

    private val _activeToolProgress = MutableStateFlow<Map<String, List<ToolProgressInfo>>>(emptyMap())
    val activeToolProgress: StateFlow<Map<String, List<ToolProgressInfo>>> = _activeToolProgress.asStateFlow()

    private val _stepProgress = MutableStateFlow<Map<String, StepProgressInfo>>(emptyMap())
    val stepProgress: StateFlow<Map<String, StepProgressInfo>> = _stepProgress.asStateFlow()

    private val _compactionState = MutableStateFlow<Map<String, CompactionStateInfo>>(emptyMap())
    val compactionState: StateFlow<Map<String, CompactionStateInfo>> = _compactionState.asStateFlow()

    private val _shellState = MutableStateFlow<Map<String, ShellStateInfo>>(emptyMap())
    val shellState: StateFlow<Map<String, ShellStateInfo>> = _shellState.asStateFlow()

    private val _retryState = MutableStateFlow<Map<String, Int>>(emptyMap())
    val retryState: StateFlow<Map<String, Int>> = _retryState.asStateFlow()

    private val _lastEventSeq = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastEventSeq: StateFlow<Map<String, Long>> = _lastEventSeq.asStateFlow()

    private val _gapDetected = MutableStateFlow<Set<String>>(emptySet())
    val gapDetected: StateFlow<Set<String>> = _gapDetected.asStateFlow()

    // ============ SseEventHandler ============

    override fun handle(event: SseEvent, serverId: String): Boolean {
        if (event is SseEvent.SessionNext) {
            handleSessionNextEvent(event.event)
            return true
        }
        return false
    }

    // ============ Event Processing ============

    fun handleSessionNextEvent(event: SessionNextEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Processing: ${event::class.simpleName}")
        when (event) {
            is SessionNextEvent.AgentSwitched -> handleAgentSwitched(event)
            is SessionNextEvent.ModelSwitched -> handleModelSwitched(event)

            // Text/Reasoning streaming events carry no state to track here — part content
            // and time.end are updated via message.part.* events in MessagePartHandler.
            is SessionNextEvent.TextStarted -> Unit
            is SessionNextEvent.TextDelta -> Unit
            is SessionNextEvent.TextEnded -> Unit

            is SessionNextEvent.ReasoningStarted -> Unit
            is SessionNextEvent.ReasoningDelta -> Unit
            is SessionNextEvent.ReasoningEnded -> Unit

            is SessionNextEvent.ToolInputStarted -> handleToolInputStarted(event)
            is SessionNextEvent.ToolInputDelta -> { /* delta tracked, no state change */ }
            is SessionNextEvent.ToolCalled -> { /* full input available, no state change */ }
            is SessionNextEvent.ToolProgress -> handleToolProgress(event)
            is SessionNextEvent.ToolSuccess -> handleToolComplete(event.sessionId, event.callId)
            is SessionNextEvent.ToolFailed -> handleToolComplete(event.sessionId, event.callId)

            is SessionNextEvent.StepStarted -> handleStepStarted(event)
            is SessionNextEvent.StepEnded -> handleStepEnded(event.sessionId)
            is SessionNextEvent.StepFailed -> handleStepEnded(event.sessionId)

            is SessionNextEvent.ShellStarted -> handleShellStarted(event)
            is SessionNextEvent.ShellEnded -> handleShellEnded(event.sessionId)

            is SessionNextEvent.CompactionStarted -> handleCompactionStarted(event)
            is SessionNextEvent.CompactionDelta -> { /* delta tracked */ }
            is SessionNextEvent.CompactionEnded -> handleCompactionEnded(event.sessionId)

            is SessionNextEvent.Prompted -> { /* informational */ }
            is SessionNextEvent.Retried -> {
                _retryState.update { it + (event.sessionId to event.attempt) }
            }
            is SessionNextEvent.Synthetic -> { /* informational */ }
            is SessionNextEvent.Unknown -> {
                Log.w(TAG, "Unhandled session.next event: ${event.rawType}")
            }
        }
    }

    private fun handleAgentSwitched(event: SessionNextEvent.AgentSwitched) {
        _currentAgent.update { it + (event.sessionId to event.agent) }
    }

    private fun handleModelSwitched(event: SessionNextEvent.ModelSwitched) {
        _currentModel.update { it + (event.sessionId to (event.providerId to event.modelId)) }
    }

    private fun handleToolInputStarted(event: SessionNextEvent.ToolInputStarted) {
        _activeToolProgress.update { current ->
            val sessionTools = current[event.sessionId]?.toMutableList() ?: mutableListOf()
            sessionTools.add(ToolProgressInfo(
                callId = event.callId,
                partId = event.partId,
                tool = event.tool,
                status = "started"
            ))
            current + (event.sessionId to sessionTools)
        }
    }

    private fun handleToolProgress(event: SessionNextEvent.ToolProgress) {
        _activeToolProgress.update { current ->
            val sessionTools = current[event.sessionId] ?: return@update current
            val outputDelta = event.content.joinToString("") { it.text }
            val updated = sessionTools.map { tool ->
                if (tool.callId == event.callId) {
                    tool.copy(
                        status = "running",
                        progress = event.progress,
                        title = event.title,
                        output = tool.output + outputDelta
                    )
                } else tool
            }
            current + (event.sessionId to updated)
        }
    }

    private fun handleToolComplete(sessionId: String, callId: String) {
        _activeToolProgress.update { current ->
            val sessionTools = current[sessionId]?.filter { it.callId != callId } ?: emptyList()
            current + (sessionId to sessionTools)
        }
    }

    private fun handleStepStarted(event: SessionNextEvent.StepStarted) {
        _stepProgress.update { it + (event.sessionId to StepProgressInfo(
            step = event.step,
            agent = event.agent,
            model = event.model
        )) }
    }

    private fun handleStepEnded(sessionId: String) {
        _stepProgress.update { it - sessionId }
    }

    private fun handleShellStarted(event: SessionNextEvent.ShellStarted) {
        _shellState.update { it + (event.sessionId to ShellStateInfo(command = event.command)) }
    }

    private fun handleShellEnded(sessionId: String) {
        _shellState.update { it - sessionId }
    }

    private fun handleCompactionStarted(event: SessionNextEvent.CompactionStarted) {
        _compactionState.update { it + (event.sessionId to CompactionStateInfo(
            isActive = true,
            reason = event.reason
        )) }
    }

    private fun handleCompactionEnded(sessionId: String) {
        _compactionState.update { it - sessionId }
    }

    fun trackSequence(sessionId: String, seq: Long) {
        val last = _lastEventSeq.value[sessionId]
        if (last != null && seq > last + 1) {
            Log.w(TAG, "Sequence gap detected for session $sessionId: expected ${last + 1}, got $seq (missed ${seq - last - 1} events)")
            _gapDetected.update { it + sessionId }
        }
        _lastEventSeq.update { it + (sessionId to seq) }
    }

    fun clearGap(sessionId: String) {
        _gapDetected.update { it - sessionId }
    }

    // ============ Cleanup ============

    fun clearForSession(sessionId: String) {
        _currentAgent.update { it - sessionId }
        _currentModel.update { it - sessionId }
        _activeToolProgress.update { it - sessionId }
        _stepProgress.update { it - sessionId }
        _compactionState.update { it - sessionId }
        _shellState.update { it - sessionId }
        _retryState.update { it - sessionId }
        _lastEventSeq.update { it - sessionId }
        _gapDetected.update { it - sessionId }
    }

    fun clearForServer(sessionIds: Set<String>) {
        for (sessionId in sessionIds) {
            clearForSession(sessionId)
        }
    }

    fun clearAll() {
        _currentAgent.value = emptyMap()
        _currentModel.value = emptyMap()
        _activeToolProgress.value = emptyMap()
        _stepProgress.value = emptyMap()
        _compactionState.value = emptyMap()
        _shellState.value = emptyMap()
        _retryState.value = emptyMap()
        _lastEventSeq.value = emptyMap()
        _gapDetected.value = emptySet()
    }
}
