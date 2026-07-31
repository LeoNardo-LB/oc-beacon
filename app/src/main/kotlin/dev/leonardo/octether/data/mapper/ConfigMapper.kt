package dev.leonardo.octether.data.mapper

import dev.leonardo.octether.data.dto.response.ServerConfigResponse
import dev.leonardo.octether.data.dto.request.ServerConfigPatch

/**
 * Maps between API Config DTOs and Domain-layer config representations.
 *
 * ServerConfigResponse and ServerConfigPatch are currently used directly
 * in the API layer. This mapper exists for cases where the domain needs
 * a simplified view of server configuration.
 */
object ConfigMapper {

    /**
     * Extract disabled provider list from response.
     * Domain uses simple string list; no dedicated domain type yet.
     */
    fun toDisabledProviders(response: ServerConfigResponse): List<String> {
        return response.disabledProviders
    }

    /**
     * Build a patch from individual field updates.
     */
    fun toPatch(
        disabledProviders: List<String>? = null,
        model: String? = null,
        smallModel: String? = null,
        defaultAgent: String? = null
    ): ServerConfigPatch {
        return ServerConfigPatch(
            disabledProviders = disabledProviders,
            model = model,
            smallModel = smallModel,
            defaultAgent = defaultAgent
        )
    }
}
