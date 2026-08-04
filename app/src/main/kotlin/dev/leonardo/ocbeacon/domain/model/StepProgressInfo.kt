package dev.leonardo.ocbeacon.domain.model

/**
 * 步骤进度的领域模型。
 * 对应 data.repository.handler.StepProgressInfo。
 */
data class StepProgressInfo(
    val step: Int,
    val agent: String = "",
    val model: String = ""
)
