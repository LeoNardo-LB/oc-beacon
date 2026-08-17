package dev.leonardo.ocbeacon.ui.screens.chat.input

/**
 * 发送按钮 busy 指示的显示侧平滑器（2026-08-17 修复：流式输出期间进度圈闪烁）。
 *
 * 根因链（logcat 实证 + 代码走查）：
 * V2 服务器在 turn 结束时发 execution.succeeded → FSM SseIdle → Idle（按钮指示消失，
 * 此时正确）；但服务器 drain 窗口内 /active 仍返回 running → active 正向对账
 * （TaskDelegate.refreshActiveSessions → SessionStateService.reconcileWithActiveSessions
 * streak=2 ≈10s 确认）触发 L3 REST 校验 → REST 也说 Busy（同源快照）→ FSM 复活 Busy；
 * 随后 lastEventAt 不再刷新（RestValidation 不刷新 lastEventAt，见 SessionStateFSM 注释）
 * → 15s 后 L2 stale 再触发 L3……直到 drain 完成/zombie 判定（3min）→ Busy↔Idle 循环。
 * FSM 语义正确（忠实跟随服务器状态），问题只在显示层：用户看到圈/停止按钮
 * 「一会儿有一会儿没有」。
 *
 * 修复策略（不动 FSM，不违反 SessionStateService 单一真相源铁律）：
 * 显示侧**下降沿延迟**——busy/sending 变 true 立即传导；两者都变 false 后需
 * 持续稳定 [releaseDelayMs] 才传导 false；期间任一变 true 则取消挂起的释放。
 * 同时吸收第二个缝隙：POST 完成（isSending=false）到 FSM 置 Busy（isBusy=true）
 * 之间两个 StateFlow 到达组合点的时序竞速（1-2 帧）——isSending=true 期间视为
 * busy，下降沿同样走释放延迟，等 isBusy 接管。
 *
 * 纯逻辑类（时间注入），由 [dev.leonardo.ocbeacon.ui.screens.chat] 的
 * rememberStableBusyIndicator Composable 驱动；单测见 BusyIndicatorSmootherTest。
 */
class BusyIndicatorSmoother(
    private val releaseDelayMs: Long = DEFAULT_RELEASE_DELAY_MS,
) {
    /** 当前稳定输出值（true=显示 busy 指示）。 */
    var value: Boolean = false
        private set

    /** 挂起的释放时刻（epoch ms）；-1 = 无挂起（当前为 true 或从未置位）。 */
    private var pendingReleaseAt: Long = -1L

    /**
     * 输入原始 (busy, sending)，返回稳定输出值。
     *
     * @param busy FSM 原始 isBusy（Busy || Retry）
     * @param sending 本地发送中（POST 进行时）
     * @param nowMs 当前时刻（注入便于测试）
     */
    fun update(busy: Boolean, sending: Boolean, nowMs: Long): Boolean {
        if (busy || sending) {
            pendingReleaseAt = -1L
            value = true
        } else if (value) {
            // 下降沿（首次）：挂起释放计时，保持 true
            if (pendingReleaseAt == -1L) {
                pendingReleaseAt = nowMs + releaseDelayMs
            } else if (nowMs >= pendingReleaseAt) {
                // 到期：释放并清除挂起（后续 remaining=-1，不再有事）
                value = false
                pendingReleaseAt = -1L
            }
        }
        // value=false 时无任何挂起（从未置位或已释放）
        return value
    }

    /**
     * 距释放还剩多少 ms；<0 表示无挂起（不需要等待）。驱动方 delay 后再调
     * [update] 完成释放；期间输入变 true 会使挂起作废（返回 <0）。
     */
    fun remainingMs(nowMs: Long): Long =
        if (pendingReleaseAt == -1L) -1L else (pendingReleaseAt - nowMs).coerceAtLeast(0)

    companion object {
        /** 释放延迟：覆盖正向对账确认窗口（≈10s）内最短抖动周期的一半以上，
         *  同时保证任务真正结束后指示最多多显示 2.5s（不粘死）。 */
        const val DEFAULT_RELEASE_DELAY_MS = 2_500L
    }
}
