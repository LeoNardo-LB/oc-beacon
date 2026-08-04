package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ToolState 的自定义序列化器，根据 "status" 字段分发。
 * API 使用 "status"（而非 "type"）作为判别字段。
 */
object ToolStateSerializer : JsonContentPolymorphicSerializer<ToolState>(ToolState::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ToolState> {
        return when (element.jsonObject["status"]?.jsonPrimitive?.content) {
            "pending" -> ToolState.Pending.serializer()
            "running" -> ToolState.Running.serializer()
            "completed" -> ToolState.Completed.serializer()
            "error" -> ToolState.Error.serializer()
            else -> ToolState.Pending.serializer() // 回退
        }
    }
}

/**
 * 工具状态 —— 工具调用的生命周期。
 * 由 API JSON 中的 "status" 字段判别。
 */
@Serializable(with = ToolStateSerializer::class)
sealed class ToolState {
    @Serializable
    data class Pending(
        val input: Map<String, JsonElement> = emptyMap(),
        val raw: String? = null
    ) : ToolState()

    @Serializable
    data class Running(
        val input: Map<String, JsonElement> = emptyMap(),
        val output: String = "",
        val title: String? = null,
        val metadata: Map<String, JsonElement>? = null,
        val time: Time? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long)
    }

    @Serializable
    data class Completed(
        val input: Map<String, JsonElement> = emptyMap(),
        val output: String = "",
        val title: String? = null,
        val metadata: Map<String, JsonElement>? = null,
        val time: Time? = null,
        val attachments: List<Attachment>? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long, val end: Long, val compacted: Long? = null)

        @Serializable
        data class Attachment(
            val type: String,
            val data: String? = null
        )
    }

    @Serializable
    data class Error(
        val input: Map<String, JsonElement> = emptyMap(),
        val error: String = "",
        val metadata: Map<String, JsonElement>? = null,
        val time: Time? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long, val end: Long)
    }
}
