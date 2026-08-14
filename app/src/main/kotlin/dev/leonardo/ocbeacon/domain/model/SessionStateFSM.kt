package dev.leonardo.ocbeacon.domain.model

/**
 * 会话状态的纯函数有限状态机。
 *
 * 两层架构：
 * - 第 1 层（Core）：Idle / Busy / Retry —— 镜像服务器的 SessionStatus
 * - 第 2 层（Activity）：Waiting / Streaming / ToolCalling / Compacting —— 派生详情
 *
 * 无状态性：此对象不持有任何可变状态。所有状态都保存在
 * [dev.leonardo.ocbeacon.data.repository.SessionStateService] 的 Map<sessionId, SessionFSMState> 中。
 *
 * 可测试性：transition() 是纯函数 —— 给定 (state, event)，始终
 * 产生相同的 TransitionResult。无副作用。
 */
object SessionStateFSM {

    data class TransitionResult(
        val newState: SessionFSMState,
        /** 若该转移提示可能丢失了 SSE 事件则为 true（例如 Idle 状态下出现 Activity 事件） */
        val isSuspicious: Boolean,
        /** 若应强制完成未完成的消息标记则为 true（例如 abort、REST 确认 Idle） */
        val forceComplete: Boolean
    )

    fun transition(state: SessionFSMState, event: FsmEvent): TransitionResult {
        val now = System.currentTimeMillis()
        return when (event) {
            // === Core 事件 ===
            FsmEvent.ClientSendParts -> clientSendParts(state, now)
            FsmEvent.ClientAbort -> toIdle(state, now, forceComplete = true)
            is FsmEvent.SseStatus -> handleSseStatus(state, event.status, now)
            FsmEvent.SseIdle -> toIdle(state, now, forceComplete = true)
            is FsmEvent.SseError -> toIdle(state, now, forceComplete = true)
            is FsmEvent.RestValidation -> restValidation(state, event.status, now)

            // === Activity 事件（session.next.*）===
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
        // 2026-08-14：Asking 是列表层合成的状态（pending question），
        // 服务器 SSE 不下发——保持当前状态不动
        is SessionStatus.Asking -> TransitionResult(
            newState = state.copy(lastEventAt = now),
            isSuspicious = false,
            forceComplete = false
        )
    }

    private fun restValidation(state: SessionFSMState, status: SessionStatus, now: Long): TransitionResult = TransitionResult(
        newState = state.copy(
            core = status,
            activity = if (status is SessionStatus.Busy) SessionActivity.Waiting else null,
            savedActivity = null,
            // 2026-08-14 修正：REST 校验**不是会话活动**——不刷新 lastEventAt。
            // 原实现 lastEventAt=now 导致"服务器 Busy + 无真实事件超阈值"的
            // 僵尸判定永远无法触发（每次校验都刷新）。保留 lastCoreTransitionAt
            // 更新（校验仍是 core 状态转移）。L2 stale 检测（Busy + lastEventAt
            // 15s 旧 → 触发校验）在无真实事件时持续触发校验（周期 15s，原 20s），
            // 校验请求是轻量状态查询，频率可接受。
            lastCoreTransitionAt = now
        ),
        isSuspicious = false,
        forceComplete = status is SessionStatus.Idle
    )

    /**
     * Activity 事件：仅当 Core 为 Busy 时有效；否则视为可疑（很可能错过了 Busy）。
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
