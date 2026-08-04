package dev.leonardo.ocbeacon.domain.model

/**
 * agent 的领域模型（字段与 data.dto.response.AgentInfo 对应）。
 * 实现层使用 mapper.toDomain() 进行转换。
 */
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary",
    val hidden: Boolean = false,
    val color: String? = null,
)
