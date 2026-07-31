package dev.leonardo.octether.domain.model

/**
 * Domain model for an agent (mirrors data.dto.response.AgentInfo fields).
 * Impl will use mapper.toDomain() to convert.
 */
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
    val color: String? = null,
)
