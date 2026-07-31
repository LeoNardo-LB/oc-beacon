package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Draft(
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    val confirmedFilePaths: List<String> = emptyList(),
    val selectedAgent: String? = null,
    val selectedVariant: String? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() &&
                imageUris.isEmpty() &&
                confirmedFilePaths.isEmpty() &&
                selectedAgent.isNullOrBlank() &&
                selectedVariant.isNullOrBlank()
}
