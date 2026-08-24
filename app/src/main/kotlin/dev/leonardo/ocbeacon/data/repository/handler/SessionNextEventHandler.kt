package dev.leonardo.ocbeacon.data.repository.handler

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val output: String = "",
    /** #180：tool.progress metadata.sessionID——subagent Running 期子智能体会话推断源。 */
    val childSessionId: String? = null,
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
    val reason: String = "",
    /** 2026-08-24（#217 分割线包揽）：压缩摘要流式累积文本（session.compaction.delta 逐段拼接）；空 = 尚无输出。 */
    val deltaText: String = "",
    /** 服务器 compaction 消息 id（started 事件 inputID 同源）——预留终态对位，空 = 未知。 */
    val messageId: String = ""
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

    /** #219：压缩失败广播（sessionId to 服务器 error.message）——UI snackbar 数据源。 */
    private val _compactionFailures = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val compactionFailures: SharedFlow<Pair<String, String>> = _compactionFailures.asSharedFlow()

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
            is SessionNextEvent.CompactionDelta -> handleCompactionDelta(event)
            is SessionNextEvent.CompactionEnded -> handleCompactionEnded(event)

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
            // #180（2026-08-21，宿主机 SSE 抓帧实证）：subagent 的 progress
            // metadata 携带 {sessionID: 子智能体会话, status: running}——Running 期
            // 子智能体会话跳转的推断源（tool.called 无此信息，success 才有终态 id）。
            val childSessionId = event.metadata?.get("sessionID")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            val updated = sessionTools.map { tool ->
                if (tool.callId == event.callId) {
                    tool.copy(
                        status = "running",
                        progress = event.progress,
                        title = event.title,
                        output = replacementOutput ?: (tool.output + outputDelta),
                        childSessionId = childSessionId ?: tool.childSessionId,
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
        // 2026-08-24（#217）：messageId 记录（started.inputID 即 compaction 消息 id）；
        // deltaText 清零——同会话二次压缩重新累积。
        _compactionState.update { it + (event.sessionId to CompactionStateInfo(
            isActive = true,
            reason = event.reason,
            deltaText = "",
            messageId = event.messageId
        )) }
    }

    /**
     * 2026-08-24（#217 分割线包揽）：压缩摘要流式累积——session.compaction.delta
     * 的 text 逐段拼接进进行中状态，驱动「进行中分割线」展开区的实时摘要。
     * 未 started 先到 delta（事件乱序防御）时置 isActive=true 兜底。
     */
    private fun handleCompactionDelta(event: SessionNextEvent.CompactionDelta) {
        if (event.delta.isEmpty()) return
        _compactionState.update { current ->
            val existing = current[event.sessionId]
            val base = existing ?: CompactionStateInfo(isActive = true, reason = "")
            current + (event.sessionId to base.copy(
                deltaText = base.deltaText + event.delta
            ))
        }
    }

    private fun handleCompactionEnded(event: SessionNextEvent.CompactionEnded) {
        // #219（2026-08-25）：失败事件（error 非空）先广播——V2 HTTP 秒回受理，
        // 压缩失败只从 SSE session.compaction.failed 到达，此前静默结束。
        if (event.error.isNotBlank()) {
            _compactionFailures.tryEmit(event.sessionId to event.error)
        }
        _compactionState.update { it - event.sessionId }
    }

    /**
     * 2026-08-19：跨 handler 入口——SessionCompacted（服务器压缩真实完成：
     * V2 session.compaction.ended 映射 / legacy session.compacted）时终结
     * 压缩横幅。用户发起路径的 HTTP 回调注入（SessionNext(CompactionEnded)）
     * 已覆盖且幂等；auto-compaction 只有服务器事件，此前横幅会永久停留。
     * 由 EventDispatcher.processEvent 调用（每事件类唯一 handler 的注册表
     * 分发模型下，跨 handler 逻辑显式写在 dispatcher）。
     */
    fun endCompaction(sessionId: String) {
        handleCompactionEnded(SessionNextEvent.CompactionEnded(sessionId = sessionId, messageId = ""))
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
