package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * 提示部分的领域模型（文本、文件、图片等）。
 * 对应 data.dto.request.PromptPart。
 */
@Serializable
data class PromptPart(
    val type: String,
    val text: String? = null,
    val path: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null
)
