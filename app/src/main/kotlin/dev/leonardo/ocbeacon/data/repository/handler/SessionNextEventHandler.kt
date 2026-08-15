package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单次工具调用的当前工具执行进度。
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
 * 当前步骤进度。
 */
data class StepProgressInfo(
    val step: Int,
    val agent: String = "",
    val model: String = ""
)

/**
 * 会话的压缩状态。
 */
data class CompactionStateInfo(
    val isActive: Boolean,
    val reason: String = ""
)

/**
 * 会话的 shell 执行状态。
 */
data class ShellStateInfo(
    val command: String
)

/**
 * 处理所有 session.next.* 事件以进行实时状态跟踪。
 * 管理：agent/model 切换、工具进度、步骤进度、
 * 压缩状态和 shell 状态。
 */
@Singleton
class SessionNextEventHandler @Inject constructor(
    /** 2026-08-15：session 级 token 用量（usage.updated）直写统计跟踪器。 */
    private val tokenStatsTracker: dev.leonardo.ocbeacon.domain.tracker.TokenStatsTracker,
) : SseEventHandler {

    companion object {
        private const val TAG = "SessionNextEventHandler"
    }

    // ============ 公共状态（只读）============

    /** 2026-08-15（research/11 P1）：session.next.moved → 会话缓存 directory 更新回调（EventDispatcher 装配）。 */
    @Volatile
    var sessionMovedListener: ((sessionId: String, location: String, subdirectory: String?) -> Unit)? = null

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

    // ============ 事件处理 ============

    fun handleSessionNextEvent(event: SessionNextEvent) {
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "Processing: ${event::class.simpleName}")
        when (event) {
            is SessionNextEvent.AgentSwitched -> handleAgentSwitched(event)
            is SessionNextEvent.ModelSwitched -> handleModelSwitched(event)
            // 2026-08-15（research/11 P1）：会话跨目录移动——更新本地会话缓存
            // 的 directory（对齐官方 TUI sync.tsx:300-314 增量更新；分组随
            // sessionsFlow 自动重算）。
            is SessionNextEvent.Moved -> sessionMovedListener?.invoke(event.sessionId, event.location, event.subdirectory)

            // 文本/推理流式事件不携带需在此跟踪的状态——part 内容
            // 和 time.end 通过 MessagePartHandler 中的 message.part.* 事件更新。
            is SessionNextEvent.TextStarted -> Unit
            is SessionNextEvent.TextDelta -> Unit
            is SessionNextEvent.TextEnded -> Unit

            is SessionNextEvent.ReasoningStarted -> Unit
            is SessionNextEvent.ReasoningDelta -> Unit
            is SessionNextEvent.ReasoningEnded -> Unit

            is SessionNextEvent.ToolInputStarted -> handleToolInputStarted(event)
            is SessionNextEvent.ToolInputDelta -> { /* delta 已跟踪，无状态变更 */ }
            is SessionNextEvent.ToolCalled -> { /* 完整输入可用，无状态变更 */ }
            is SessionNextEvent.ToolProgress -> handleToolProgress(event)
            is SessionNextEvent.ToolSuccess -> handleToolComplete(event.sessionId, event.callId)
            is SessionNextEvent.ToolFailed -> handleToolComplete(event.sessionId, event.callId)

            is SessionNextEvent.StepStarted -> handleStepStarted(event)
            is SessionNextEvent.StepEnded -> handleStepEnded(event)
            is SessionNextEvent.StepFailed -> { _stepProgress.update { it - event.sessionId } }

            is SessionNextEvent.ShellStarted -> handleShellStarted(event)
            is SessionNextEvent.ShellEnded -> handleShellEnded(event.sessionId)

            is SessionNextEvent.CompactionStarted -> handleCompactionStarted(event)
            is SessionNextEvent.CompactionDelta -> { /* delta 已跟踪 */ }
            is SessionNextEvent.CompactionEnded -> handleCompactionEnded(event.sessionId)

            is SessionNextEvent.Prompted -> { /* 信息性 */ }
            is SessionNextEvent.Retried -> {
                _retryState.update { it + (event.sessionId to event.attempt) }
            }
            is SessionNextEvent.UsageUpdated -> handleUsageUpdated(event)
            is SessionNextEvent.Synthetic -> { /* 信息性 */ }
            is SessionNextEvent.Unknown -> {
                AppLogger.w(TAG, "Unhandled session.next event: ${event.rawType}")
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
            // 2026-08-15（research/08 P0）：对齐官方 V2 契约——progress 输出是
            // **整体替换**语义（非拼接）。优先级：metadata.output（当前部署版
            // 实测抓帧）> structured.output（主干 .next schema）> content 拼接
            // （旧契约兼容）。success 后由 Completed.output 终态覆盖。
            val replacementOutput = event.metadata?.get("output")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
                ?: event.structured?.get("output")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            val outputDelta = event.content.joinToString("") { it.text }
            val updated = sessionTools.map { tool ->
                if (tool.callId == event.callId) {
                    tool.copy(
                        status = "running",
                        progress = event.progress,
                        title = event.title,
                        output = replacementOutput ?: (tool.output + outputDelta)
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

    private fun handleStepEnded(event: SessionNextEvent.StepEnded) {
        _stepProgress.update { it - event.sessionId }
    }

    private fun handleShellStarted(event: SessionNextEvent.ShellStarted) {
        _shellState.update { it + (event.sessionId to ShellStateInfo(command = event.command)) }
    }

    // ============ 会话级 token 用量（2026-08-15：顶部 context 指示器数据源） ============

    private val _sessionUsage = MutableStateFlow<Map<String, SessionNextEvent.UsageUpdated>>(emptyMap())
    /** 按 sessionId 的最新 usage（V2 session.usage.updated 实时推送）。 */
    val sessionUsage: StateFlow<Map<String, SessionNextEvent.UsageUpdated>> = _sessionUsage.asStateFlow()

    /**
     * 2026-08-15：session 级 token 用量（V2 session.usage.updated）——服务器
     * 权威累计值。**只记录不写全局 tracker**（tracker 是单会话作用域，而本
     * handler 收到服务器全部会话的事件——直接写入会跨会话污染）。由
     * ChatViewModel 按当前会话订阅消费。
     */
    private fun handleUsageUpdated(event: SessionNextEvent.UsageUpdated) {
        _sessionUsage.update { it + (event.sessionId to event) }
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
            AppLogger.w(TAG, "Sequence gap detected for session $sessionId: expected ${last + 1}, got $seq (missed ${seq - last - 1} events)")
            _gapDetected.update { it + sessionId }
        }
        _lastEventSeq.update { it + (sessionId to seq) }
    }

    fun clearGap(sessionId: String) {
        _gapDetected.update { it - sessionId }
    }

    // ============ 清理 ============

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
        _sessionUsage.update { it - sessionId }
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
        _sessionUsage.value = emptyMap()
    }
}
