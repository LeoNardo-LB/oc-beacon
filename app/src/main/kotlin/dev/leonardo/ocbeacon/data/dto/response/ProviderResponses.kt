package dev.leonardo.ocbeacon.data.dto.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ProvidersResponse(
    val providers: List<ProviderInfo>,
    val default: Map<String, String> = emptyMap()
)

@Serializable
data class ProviderCatalogResponse(
    val all: List<ProviderInfo>,
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val source: String = "",
    val env: List<String> = emptyList(),
    val key: String? = null,
    val options: Map<String, JsonElement> = emptyMap(),
    val models: Map<String, ProviderModel> = emptyMap()
)

@Serializable
data class ProviderModel(
    val id: String,
    @SerialName("providerID") val providerId: String = "",
    val name: String,
    val family: String? = null,
    val status: String = "active",
    val capabilities: ModelCapabilities? = null,
    val cost: ModelCost? = null,
    val limit: ModelLimit? = null,
    val variants: Map<String, JsonElement>? = null
)

@Serializable
data class ModelCapabilities(
    val temperature: Boolean = false,
    val reasoning: Boolean = false,
    val attachment: Boolean = false,
    val toolcall: Boolean = false
)

@Serializable
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cache: CacheCost? = null
) {
    @Serializable
    data class CacheCost(
        val read: Double = 0.0,
        val write: Double = 0.0
    )
}

@Serializable
data class ModelLimit(
    val context: Int = 0,
    val input: Int? = null,
    val output: Int = 0
)

@Serializable
data class ProviderAuthMethod(
    val type: String,
    val label: String
)

@Serializable
data class ProviderOauthAuthorization(
    val url: String = "",
    val method: String = "none",
    val instructions: String = ""
)
