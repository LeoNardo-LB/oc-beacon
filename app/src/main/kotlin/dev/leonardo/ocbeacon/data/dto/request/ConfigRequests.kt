package dev.leonardo.ocbeacon.data.dto.request

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerConfigPatch(
    @SerialName("disabled_providers") val disabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null
)
