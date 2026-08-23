package dev.leonardo.ocbeacon.domain.model

/**
 * 第 2 层 Activity —— 派生状态，仅在 Core = Busy 时有意义。
 * 用于 UI 反馈和细粒度的僵尸检测。
 */
sealed class SessionActivity {
    /** Busy 刚开始，等待 assistant 消息创建 */
    data object Waiting : SessionActivity()

    /** 正在接收文本流（text.started ~ text.ended） */
    data object Streaming : SessionActivity()

    /** 工具调用进行中（tool.input.started ~ tool.success/failed） */
    data class ToolCalling(
        val toolName: String?,
        val callId: String?
    ) : SessionActivity()

    /** 压缩进行中；保存先前的 activity 以便 CompactionEnded 时恢复 */
    data class Compacting(val savedActivity: SessionActivity?) : SessionActivity()
}

/**
 * 单个会话的完整 FSM 状态。
 *
 * @param core 第 1 层状态 —— 镜像服务器状态 + 客户端合成 Asking（列表层；Idle/Busy/Retry）
 * @param activity 第 2 层 activity 详情（仅当 core 为 Busy 时非空）
 * @param lastEventAt 最近一次收到 SSE 事件的时间戳（用于 L2 僵尸检测）
 * @param lastCoreTransitionAt 最近一次 Core 状态变更的时间戳
 * @param savedActivity Compacting 之前保存的 activity（CompactionEnded 时恢复）
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
 * 驱动 FSM 状态转移的事件。
 */
sealed class FsmEvent {
    // === Core 事件 ===
    data class SseStatus(val status: SessionStatus) : FsmEvent()
    data object SseIdle : FsmEvent()
    data class SseError(val message: String) : FsmEvent()
    data object ClientSendParts : FsmEvent()
    data object ClientAbort : FsmEvent()
    data class RestValidation(val status: SessionStatus) : FsmEvent()

    // === Activity 事件（session.next.*）===
    data object StepStarted : FsmEvent()
    data object TextStarted : FsmEvent()
    data class TextDelta(val delta: String) : FsmEvent()
    data object TextEnded : FsmEvent()
    data class ToolInputStarted(val toolName: String?, val callId: String?) : FsmEvent()
    data class StepEnded(val finish: String?) : FsmEvent()
    data object CompactionStarted : FsmEvent()
    data object CompactionEnded : FsmEvent()
}
