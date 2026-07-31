package dev.leonardo.ocbeacon.domain.model

data class ProviderInfo(
    val id: String,
    val name: String,
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val models: List<ModelInfo> = emptyList()
)

data class ModelInfo(
    val id: String,
    val name: String,
    val visible: Boolean = true
)
