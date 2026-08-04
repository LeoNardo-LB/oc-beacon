package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.common.ModelSelection
import dev.leonardo.ocbeacon.data.dto.response.ProviderInfo
import dev.leonardo.ocbeacon.data.dto.response.ProviderModel
import dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse
import dev.leonardo.ocbeacon.data.dto.response.ProviderCatalogResponse

/**
 * 将 provider 相关的 API 响应映射为简化的领域表示。
 *
 * 目前 provider DTO 由 ViewModel 直接消费。
 * 此 mapper 为领域层在无需 API 层序列化注解的情况下
 * 需要 provider 信息时提供转换。
 */
object ProviderMapper {

    /** 提取 provider ID → 显示名 的 map，供 UI 选择使用。 */
    fun toProviderNameMap(response: ProvidersResponse): Map<String, String> {
        return response.providers.associate { it.id to it.name }
    }

    /** 提取按 provider 分组的所有 model ID。 */
    fun toModelsByProvider(response: ProvidersResponse): Map<String, List<ProviderModel>> {
        return response.providers.associate { it.id to it.models.values.toList() }
    }

    /** 从目录中提取已连接的 provider ID。 */
    fun toConnectedProviderIds(response: ProviderCatalogResponse): Set<String> {
        return response.connected.toSet()
    }

    /** 将 provider+model 对转换为 ModelSelection 以供 API 请求。 */
    fun toModelSelection(providerId: String, modelId: String): ModelSelection {
        return ModelSelection(providerId = providerId, modelId = modelId)
    }
}
