package dev.leonardo.ocbeacon.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class QuestionReplyBody(
    val answers: List<List<String>>
)
