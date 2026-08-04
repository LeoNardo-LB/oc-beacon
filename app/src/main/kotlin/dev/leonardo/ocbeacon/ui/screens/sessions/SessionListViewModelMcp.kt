package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

fun SessionListViewModel.loadMcpServers() {
    viewModelScope.launch {
        _mcpInitialLoading.value = true
        mcpRepository.getMcpServers()
            .onSuccess { _mcpServers.value = it }
            .onFailure {
                _mcpError.emit(it.message ?: "Failed to load MCP servers")
            }
        _mcpInitialLoading.value = false
    }
}

fun SessionListViewModel.toggleMcpServer(name: String) {
    if (_mcpLoading.value == name) return
    val server = _mcpServers.value.find { it.name == name } ?: return
    val connect = server.status != "connected"
    _mcpLoading.value = name

    viewModelScope.launch {
        mcpRepository.toggleMcpServer(name, connect)
            .onSuccess {
                mcpRepository.getMcpServers()
                    .onSuccess { _mcpServers.value = it }
            }
            .onFailure {
                _mcpError.emit("Failed to ${if (connect) "connect" else "disconnect"} $name")
            }
        _mcpLoading.value = null
    }
}
