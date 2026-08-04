package dev.leonardo.ocbeacon.domain.model

/**
 * 命令的领域模型（字段与 data.dto.response.CommandInfo 对应）。
 * 实现层使用 mapper.toDomain() 进行转换。
 */
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val hints: List<String> = emptyList(),
)
