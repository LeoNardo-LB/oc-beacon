package dev.leonardo.octether.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class TodoItem(
    val id: String = "",
    val content: String,
    val status: String = "pending",
    val priority: String = "medium"
)

@Serializable
data class SessionStatusInfo(
    val id: String = "",
    val status: Map<String, String> = emptyMap()
)

@Serializable
data class ShellInfo(
    val path: String,
    val name: String,
    val acceptable: Boolean = true
)

@Serializable
data class SymbolInfo(
    val name: String,
    val kind: String = "",
    val path: String = "",
    val line: Int? = null,
    val language: String? = null
)

@Serializable
data class FileStatusInfo(
    val path: String,
    val status: String,
    val staged: Boolean = false
)
