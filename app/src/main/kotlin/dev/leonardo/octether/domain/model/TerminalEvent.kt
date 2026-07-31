package dev.leonardo.octether.domain.model

/**
 * Domain model for PTY terminal stream events.
 * Represents data flowing through the WebSocket PTY connection.
 */
sealed class TerminalEvent {
    data class Output(val data: String) : TerminalEvent()
    data class Error(val message: String) : TerminalEvent()
    data object Closed : TerminalEvent()
}
