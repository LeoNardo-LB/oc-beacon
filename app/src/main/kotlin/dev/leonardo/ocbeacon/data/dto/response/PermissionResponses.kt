package dev.leonardo.ocbeacon.data.dto.response

import dev.leonardo.ocbeacon.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PermissionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, JsonElement>? = null,
    val always: JsonElement? = null,
    val tool: ToolRef? = null
)

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo>,
    val tool: ToolRef? = null
)

@Serializable
data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true,
    /** V2 form field key（q0/q1...）；V1 为 null。 */
    val key: String? = null
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String,
    /** V2 form option value（提交用）；V1 为 null。 */
    val value: String? = null
)
