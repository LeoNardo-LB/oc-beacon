package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.ServerConfigResponse
import dev.leonardo.ocbeacon.data.dto.request.ServerConfigPatch

/**
 * 在 API Config DTO 和领域层配置表示之间映射。
 *
 * ServerConfigResponse 和 ServerConfigPatch 目前在 API 层直接使用。
 * 此 mapper 用于领域层需要服务器配置简化视图的场景。
 */
object ConfigMapper {

    /**
     * 从响应中提取禁用的 provider 列表。
     * 领域层使用简单的字符串列表；尚无专用领域类型。
     */
    fun toDisabledProviders(response: ServerConfigResponse): List<String> {
        return response.disabledProviders
    }

    /**
     * 从各字段更新构建一个 patch。
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
