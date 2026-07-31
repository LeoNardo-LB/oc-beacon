package dev.leonardo.octether.domain.model

/**
 * Domain model for a command (mirrors data.dto.response.CommandInfo fields).
 * Impl will use mapper.toDomain() to convert.
 */
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val hints: List<String> = emptyList(),
)
