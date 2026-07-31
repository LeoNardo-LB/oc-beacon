package dev.leonardo.octether.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class QuestionReplyBody(
    val answers: List<List<String>>
)
