package dev.leonardo.ocbeacon.domain.model

/**
 * PTY 终端流事件的领域模型。
 * 表示通过 WebSocket PTY 连接传输的数据。
 */
sealed class TerminalEvent {
    data class Output(val data: String) : TerminalEvent()
    data class Error(val message: String) : TerminalEvent()
    data object Closed : TerminalEvent()
}
