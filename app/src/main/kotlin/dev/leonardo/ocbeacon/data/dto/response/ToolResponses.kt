package dev.leonardo.ocbeacon.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
    val color: String? = null
)

@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val hints: List<String> = emptyList()
)

@Serializable
data class SkillInfo(
    val name: String,
    val description: String? = null,
    val location: String = "",
    val content: String = ""
)
