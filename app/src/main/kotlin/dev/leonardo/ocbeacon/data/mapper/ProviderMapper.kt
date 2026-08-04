package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.common.ModelSelection
import dev.leonardo.ocbeacon.data.dto.response.ProviderAuthMethod as DtoProviderAuthMethod
import dev.leonardo.ocbeacon.data.dto.response.ProviderCatalogResponse
import dev.leonardo.ocbeacon.data.dto.response.ProviderInfo
import dev.leonardo.ocbeacon.data.dto.response.ProviderModel
import dev.leonardo.ocbeacon.data.dto.response.ProviderOauthAuthorization as DtoProviderOauthAuthorization
import dev.leonardo.ocbeacon.data.dto.response.ProvidersResponse
import dev.leonardo.ocbeacon.domain.model.ModelCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderAuthMethod
import dev.leonardo.ocbeacon.domain.model.ProviderCatalog
import dev.leonardo.ocbeacon.domain.model.ProviderConnectionStatus
import dev.leonardo.ocbeacon.domain.model.ProviderOauthAuthorization

/**
 * 将 provider 相关的 API 响应映射为简化的领域表示。
 */
object ProviderMapper {

    /** 提取 provider ID → 显示名 的 map，供 UI 选择。 */
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

    /** 将 [ProviderCatalogResponse]（/provider）映射为领域 [ProviderConnectionStatus]。 */
    fun toConnectionStatus(response: ProviderCatalogResponse): ProviderConnectionStatus {
        return ProviderConnectionStatus(
            providers = response.all.map { it.toDomain() },
            connected = response.connected.toSet()
        )
    }

    /** 将 DTO [ProviderInfo]（/provider、/config/providers）映射为领域 [ProviderCatalog]。 */
    private fun ProviderInfo.toDomain(): ProviderCatalog {
        return ProviderCatalog(
            id = id,
            name = name,
            source = source,
            models = models.mapValues { (_, model) -> model.toDomain() }
        )
    }

    /** 将 DTO [ProviderModel] 映射为领域 [ModelCatalog]。 */
    private fun ProviderModel.toDomain(): ModelCatalog {
        return ModelCatalog(
            id = id,
            name = name,
            contextWindow = limit?.context ?: 0,
            costInput = cost?.input ?: 0.0,
            variantNames = variants?.keys?.toList()?.sorted() ?: emptyList()
        )
    }

    /** 将 DTO [DtoProviderAuthMethod] 映射为领域 [ProviderAuthMethod]。 */
    private fun DtoProviderAuthMethod.toDomain(): ProviderAuthMethod {
        return ProviderAuthMethod(type = type, label = label)
    }

    /** 将 DTO auth 方法 map 整体映射为领域 map。 */
    fun toDomainAuthMethods(
        methods: Map<String, List<DtoProviderAuthMethod>>
    ): Map<String, List<ProviderAuthMethod>> {
        return methods.mapValues { (_, list) -> list.map { it.toDomain() } }
    }

    /** 将 DTO [DtoProviderOauthAuthorization] 映射为领域 [ProviderOauthAuthorization]。 */
    fun toDomain(auth: DtoProviderOauthAuthorization): ProviderOauthAuthorization {
        return ProviderOauthAuthorization(
            url = auth.url,
            method = auth.method,
            instructions = auth.instructions
        )
    }
}
