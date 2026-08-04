package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 选择 provider/model 配对的领域模型。
 * 对应 data.dto.common.ModelSelection。
 */
@Serializable
data class ModelSelection(
    val providerId: String,
    val modelId: String
)
