package dev.leonardo.octether.domain.model

/**
 * Domain model for the full provider catalog response.
 * Used by ViewModels for provider/model selection.
 * Counterpart of data.dto.response.ProvidersResponse.
 */
data class ProvidersResponse(
    val providers: List<ProviderCatalog>,
    val default: Map<String, String> = emptyMap()
)

/**
 * Domain model for a provider in the catalog view.
 * Counterpart of data.dto.response.ProviderInfo.
 */
data class ProviderCatalog(
    val id: String,
    val name: String,
    val source: String = "",
    val models: Map<String, ModelCatalog> = emptyMap()
)

/**
 * Domain model for a model in the catalog view.
 * Carries display and configuration info needed by the UI.
 * Counterpart of data.dto.response.ProviderModel.
 */
data class ModelCatalog(
    val id: String,
    val name: String,
    val contextWindow: Int = 0,
    val costInput: Double = 0.0,
    val variantNames: List<String> = emptyList()
)
