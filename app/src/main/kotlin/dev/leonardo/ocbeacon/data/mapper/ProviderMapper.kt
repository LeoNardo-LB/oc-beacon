package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.common.ModelSelection
import dev.leonardo.ocbeacon.data.dto.response.ProviderInfo
import dev.leonardo.ocbeacon.data.dto.response.ProviderModel
import dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse
import dev.leonardo.ocbeacon.data.dto.response.ProviderCatalogResponse

/**
 * Maps provider-related API responses to simplified domain representations.
 *
 * Currently the provider DTOs are consumed directly by ViewModels.
 * This mapper provides conversion for cases where domain layer needs
 * provider information without API-layer serialization annotations.
 */
object ProviderMapper {

    /** Extract provider ID → display name map for UI selection. */
    fun toProviderNameMap(response: ProvidersResponse): Map<String, String> {
        return response.providers.associate { it.id to it.name }
    }

    /** Extract all model IDs grouped by provider. */
    fun toModelsByProvider(response: ProvidersResponse): Map<String, List<ProviderModel>> {
        return response.providers.associate { it.id to it.models.values.toList() }
    }

    /** Extract connected provider IDs from catalog. */
    fun toConnectedProviderIds(response: ProviderCatalogResponse): Set<String> {
        return response.connected.toSet()
    }

    /** Convert provider+model pair to ModelSelection for API requests. */
    fun toModelSelection(providerId: String, modelId: String): ModelSelection {
        return ModelSelection(providerId = providerId, modelId = modelId)
    }
}
