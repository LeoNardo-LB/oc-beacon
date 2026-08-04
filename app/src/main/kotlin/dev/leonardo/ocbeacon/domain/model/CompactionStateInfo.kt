package dev.leonardo.ocbeacon.domain.model

/**
 * 压缩状态的领域模型。
 * 对应 data.repository.handler.CompactionStateInfo。
 */
data class CompactionStateInfo(
    val isActive: Boolean,
    val reason: String = ""
)
