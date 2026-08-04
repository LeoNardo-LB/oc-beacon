package dev.leonardo.ocbeacon.ui.screens.chat.terminal

/**
 * 单个终端标签页 PTY 连接的生命周期状态。
 *
 * 状态转移（由 [dev.leonardo.ocbeacon.ui.screens.chat.ServerTerminalWorkspace] 驱动）：
 * ```
 * Starting ──createPty+socket──► Connected
 *   │                               │
 *   │ (create failed)               │ (socket closed, ptyId still valid)
 *   ▼                               ▼
 * Exited                        Reconnecting ──openPtySocket──► Connected
 *                                  │
 *                                  │ (reconnect keeps failing)
 *                                  ▼
 *                             Disconnected ──manual reconnect──► Reconnecting/Connected
 * ```
 */
enum class TerminalTabState {
    /** 标签页刚创建；PTY 创建 / 首次 socket 打开进行中。 */
    Starting,

    /** PTY socket 已绑定并正在流式传输。 */
    Connected,

    /** socket 已断开但 PTY 在服务器上仍然存在；自动重连进行中。 */
    Reconnecting,

    /** 未连接且未在主动重连；可触发手动重连。 */
    Disconnected,

    /** PTY 在服务器上不再存在（已移除 / 从未创建）；必须重新创建。 */
    Exited,
}

/** 根据当前标签页状态以及 PTY 是否缺失（HTTP 404）决定的恢复策略。 */
enum class RecoveryAction {
    /** 仅重新打开 PTY socket（PTY 在服务器上仍然存在）。 */
    Reconnect,

    /** 完全重新创建 PTY 然后打开其 socket（PTY 已不存在）。 */
    Restart,

    /** 无需恢复——已连接，或连接尝试正在进行中。 */
    None,
}

/**
 * 纯函数：根据标签页的 [TerminalTabState] 以及服务器是否报告 PTY 缺失（404）来决定恢复动作。
 *
 * 真值表：
 *
 * | state        | isMissingPty=true | isMissingPty=false |
 * |--------------|-------------------|--------------------|
 * | Starting     | None              | None               |
 * | Connected    | None              | None               |
 * | Reconnecting | Restart           | None               |
 * | Disconnected | Restart           | Reconnect          |
 * | Exited       | Restart           | Restart            |
 *
 * 原理说明：
 * - `Starting` / `Connected` 永不中断——陈旧的 404 不能拆除一个进行中或活跃的连接。
 * - 当 PTY 缺失时，`Reconnect`（仅 socket）毫无意义，因此执行 `Restart`。
 *   唯一例外是 PTY 仍然存在时的 `Reconnecting` → 继续等待（`None`）。
 * - 无论 404 信号如何，`Exited` 始终需要 `Restart`（状态本身已表明 PTY 不存在）。
 */
fun terminalRecoveryAction(
    state: TerminalTabState,
    isMissingPty: Boolean,
): RecoveryAction = if (isMissingPty) {
    when (state) {
        // 永不中断进行中的创建或活跃的连接。
        TerminalTabState.Starting,
        TerminalTabState.Connected -> RecoveryAction.None
        // PTY 已不存在：仅 socket 重连毫无意义，因此重新创建。
        TerminalTabState.Reconnecting,
        TerminalTabState.Disconnected,
        TerminalTabState.Exited -> RecoveryAction.Restart
    }
} else {
    when (state) {
        TerminalTabState.Starting,
        TerminalTabState.Connected,
        TerminalTabState.Reconnecting -> RecoveryAction.None
        TerminalTabState.Disconnected -> RecoveryAction.Reconnect
        TerminalTabState.Exited -> RecoveryAction.Restart
    }
}
