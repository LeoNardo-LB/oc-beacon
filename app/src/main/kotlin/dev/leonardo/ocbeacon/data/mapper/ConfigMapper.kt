package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.ServerConfigResponse
import dev.leonardo.ocbeacon.data.dto.request.ServerConfigPatch as DtoServerConfigPatch
import dev.leonardo.ocbeacon.domain.model.GlobalConfig
import dev.leonardo.ocbeacon.domain.model.GlobalConfigPatch

/**
 * 在 API Config DTO 和领域层配置表示之间映射。
 */
object ConfigMapper {

    /**
     * 从响应中提取禁用的 provider 列表。
     */
    fun toDisabledProviders(response: ServerConfigResponse): List<String> {
        return response.disabledProviders
    }

    /**
     * 从各字段更新构建一个 DTO patch。
     */
    fun toDtoPatch(
        disabledProviders: List<String>? = null,
        model: String? = null,
        smallModel: String? = null,
        defaultAgent: String? = null
    ): DtoServerConfigPatch {
        return DtoServerConfigPatch(
            disabledProviders = disabledProviders,
            model = model,
            smallModel = smallModel,
            defaultAgent = defaultAgent
        )
    }

    /** 将 DTO [ServerConfigResponse] 映射为领域 [GlobalConfig]。 */
    fun toDomain(response: ServerConfigResponse): GlobalConfig {
        return GlobalConfig(
            disabledProviders = response.disabledProviders,
            enabledProviders = response.enabledProviders,
            model = response.model,
            smallModel = response.smallModel,
            defaultAgent = response.defaultAgent
        )
    }

    /** 将领域 [GlobalConfigPatch] 映射为 DTO [DtoServerConfigPatch]。 */
    fun toDto(patch: GlobalConfigPatch): DtoServerConfigPatch {
        return DtoServerConfigPatch(
            disabledProviders = patch.disabledProviders,
            model = patch.model,
            smallModel = patch.smallModel,
            defaultAgent = patch.defaultAgent
        )
    }
}
