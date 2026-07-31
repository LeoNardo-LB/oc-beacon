package dev.leonardo.ocbeacon.ui.screens.chat.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exhaustive truth-table coverage for [terminalRecoveryAction].
 *
 * | state        | isMissingPty=true | isMissingPty=false |
 * |--------------|-------------------|--------------------|
 * | Starting     | None              | None               |
 * | Connected    | None              | None               |
 * | Reconnecting | Restart           | None               |
 * | Disconnected | Restart           | Reconnect          |
 * | Exited       | Restart           | Restart            |
 */
class TerminalRecoveryActionTest {

    @Test
    fun `Starting is never interrupted even when PTY reported missing`() {
        assertEquals(RecoveryAction.None, terminalRecoveryAction(TerminalTabState.Starting, isMissingPty = true))
        assertEquals(RecoveryAction.None, terminalRecoveryAction(TerminalTabState.Starting, isMissingPty = false))
    }

    @Test
    fun `Connected never needs recovery`() {
        assertEquals(RecoveryAction.None, terminalRecoveryAction(TerminalTabState.Connected, isMissingPty = true))
        assertEquals(RecoveryAction.None, terminalRecoveryAction(TerminalTabState.Connected, isMissingPty = false))
    }

    @Test
    fun `Reconnecting restarts only when PTY is gone`() {
        // PTY still present → keep waiting on the in-flight reconnect.
        assertEquals(RecoveryAction.None, terminalRecoveryAction(TerminalTabState.Reconnecting, isMissingPty = false))
        // PTY gone → a socket-only reconnect is pointless, must recreate.
        assertEquals(RecoveryAction.Restart, terminalRecoveryAction(TerminalTabState.Reconnecting, isMissingPty = true))
    }

    @Test
    fun `Disconnected reconnects socket when PTY present, restarts when missing`() {
        assertEquals(RecoveryAction.Reconnect, terminalRecoveryAction(TerminalTabState.Disconnected, isMissingPty = false))
        assertEquals(RecoveryAction.Restart, terminalRecoveryAction(TerminalTabState.Disconnected, isMissingPty = true))
    }

    @Test
    fun `Exited always restarts regardless of missing-PTY signal`() {
        assertEquals(RecoveryAction.Restart, terminalRecoveryAction(TerminalTabState.Exited, isMissingPty = true))
        assertEquals(RecoveryAction.Restart, terminalRecoveryAction(TerminalTabState.Exited, isMissingPty = false))
    }

    @Test
    fun `every state x isMissingPty combination matches the truth table`() {
        val expected = listOf(
            // state, isMissingPty, expected action
            Triple(TerminalTabState.Starting, true, RecoveryAction.None),
            Triple(TerminalTabState.Starting, false, RecoveryAction.None),
            Triple(TerminalTabState.Connected, true, RecoveryAction.None),
            Triple(TerminalTabState.Connected, false, RecoveryAction.None),
            Triple(TerminalTabState.Reconnecting, true, RecoveryAction.Restart),
            Triple(TerminalTabState.Reconnecting, false, RecoveryAction.None),
            Triple(TerminalTabState.Disconnected, true, RecoveryAction.Restart),
            Triple(TerminalTabState.Disconnected, false, RecoveryAction.Reconnect),
            Triple(TerminalTabState.Exited, true, RecoveryAction.Restart),
            Triple(TerminalTabState.Exited, false, RecoveryAction.Restart),
        )
        expected.forEach { (state, missing, action) ->
            assertEquals(
                "state=$state isMissingPty=$missing should yield $action",
                action,
                terminalRecoveryAction(state, missing),
            )
        }
    }
}
