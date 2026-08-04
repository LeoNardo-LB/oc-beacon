package dev.leonardo.ocbeacon.domain.model

/**
 * 完整 provider 目录响应的领域模型。
 * 供 ViewModel 用于 provider/model 选择。
 * 对应 data.dto.response.ProvidersResponse。
 */
data class ProvidersResponse(
    val providers: List<ProviderCatalog>,
    val default: Map<String, String> = emptyMap()
)

/**
 * 目录视图中 provider 的领域模型。
 * 对应 data.dto.response.ProviderInfo。
 */
data class ProviderCatalog(
    val id: String,
    val name: String,
    val source: String = "",
    val models: Map<String, ModelCatalog> = emptyMap()
)

/**
 * 目录视图中 model 的领域模型。
 * 承载 UI 所需的展示和配置信息。
 * 对应 data.dto.response.ProviderModel。
 */
data class ModelCatalog(
    val id: String,
    val name: String,
    val contextWindow: Int = 0,
    val costInput: Double = 0.0,
    val variantNames: List<String> = emptyList()
)
