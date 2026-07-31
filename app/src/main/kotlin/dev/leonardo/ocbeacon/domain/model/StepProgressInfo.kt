package dev.leonardo.ocbeacon.domain.model

/**
 * Domain model for step progress.
 * Counterpart of data.repository.handler.StepProgressInfo.
 */
data class StepProgressInfo(
    val step: Int,
    val agent: String = "",
    val model: String = ""
)
