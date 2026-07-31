package dev.leonardo.ocbeacon.ui.screens.chat.terminal

/**
 * Lifecycle state of a single terminal tab's PTY connection.
 *
 * State transitions (driven by [dev.leonardo.ocbeacon.ui.screens.chat.ServerTerminalWorkspace]):
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
    /** Tab just created; PTY creation / first socket open in progress. */
    Starting,

    /** PTY socket bound and streaming. */
    Connected,

    /** Socket dropped but PTY still exists on the server; auto-reconnect in progress. */
    Reconnecting,

    /** Not connected and not actively reconnecting; a manual reconnect can be triggered. */
    Disconnected,

    /** PTY no longer exists on the server (removed / never created); must recreate it. */
    Exited,
}

/** Recovery strategy decided from the current tab state and whether the PTY is missing (HTTP 404). */
enum class RecoveryAction {
    /** Re-open the PTY socket only (PTY still exists on the server). */
    Reconnect,

    /** Recreate the PTY entirely then open its socket (PTY is gone). */
    Restart,

    /** No recovery needed — already connected, or a connection attempt is in flight. */
    None,
}

/**
 * Pure function: decide the recovery action from the tab's [TerminalTabState] and whether
 * the server reported the PTY as missing (404).
 *
 * Truth table:
 *
 * | state        | isMissingPty=true | isMissingPty=false |
 * |--------------|-------------------|--------------------|
 * | Starting     | None              | None               |
 * | Connected    | None              | None               |
 * | Reconnecting | Restart           | None               |
 * | Disconnected | Restart           | Reconnect          |
 * | Exited       | Restart           | Restart            |
 *
 * Rationale:
 * - `Starting` / `Connected` are never interrupted — a stale 404 must not tear down an
 *   in-flight or live connection.
 * - When the PTY is missing, `Reconnect` (socket-only) is pointless, so we `Restart`.
 *   The sole exception is `Reconnecting` while PTY is still present → keep waiting (`None`).
 * - `Exited` always requires `Restart` regardless of the 404 signal (state already says PTY is gone).
 */
fun terminalRecoveryAction(
    state: TerminalTabState,
    isMissingPty: Boolean,
): RecoveryAction = if (isMissingPty) {
    when (state) {
        // Never interrupt an in-flight creation or a live connection.
        TerminalTabState.Starting,
        TerminalTabState.Connected -> RecoveryAction.None
        // PTY is gone: a socket-only reconnect is pointless, so recreate it.
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
