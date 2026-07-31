package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * Domain model for a prompt part (text, file, image, etc.).
 * Counterpart of data.dto.request.PromptPart.
 */
@Serializable
data class PromptPart(
    val type: String,
    val text: String? = null,
    val path: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null
)
