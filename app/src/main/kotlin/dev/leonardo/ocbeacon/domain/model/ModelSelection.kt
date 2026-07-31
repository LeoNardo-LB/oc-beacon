package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain model for selecting a provider/model pair.
 * Counterpart of data.dto.common.ModelSelection.
 */
@Serializable
data class ModelSelection(
    val providerId: String,
    val modelId: String
)
