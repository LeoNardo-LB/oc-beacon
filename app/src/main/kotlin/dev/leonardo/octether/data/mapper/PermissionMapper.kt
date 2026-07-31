package dev.leonardo.octether.data.mapper

import dev.leonardo.octether.data.dto.response.PermissionRequest
import dev.leonardo.octether.domain.model.SseEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps between API DTO (PermissionRequest) and Domain (SseEvent.PermissionAsked).
 *
 * Key differences:
 * - API: always is JsonElement? (may be array of strings or boolean);  Domain: always is Boolean
 * - API: metadata is Map<String, JsonElement>;  Domain: metadata is Map<String, String>
 */
object PermissionMapper {

    /** API DTO → Domain */
    fun toDomain(dto: PermissionRequest): SseEvent.PermissionAsked {
        val alwaysBoolean = parseAlways(dto.always)
        val metadataStrings = dto.metadata?.mapValues { (_, v) ->
            v.jsonPrimitive.contentOrNull ?: v.toString()
        }
        return SseEvent.PermissionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            permission = dto.permission,
            patterns = dto.patterns,
            metadata = metadataStrings,
            always = alwaysBoolean,
            tool = dto.tool
        )
    }

    /** Domain → API DTO */
    fun toDto(domain: SseEvent.PermissionAsked): PermissionRequest {
        val metadataElements = domain.metadata?.mapValues { (_, v) ->
            JsonPrimitive(v) as JsonElement
        }
        val alwaysElement: JsonElement? = if (domain.always) JsonArray(listOf(JsonPrimitive("*"))) else null
        return PermissionRequest(
            id = domain.id,
            sessionId = domain.sessionId,
            permission = domain.permission,
            patterns = domain.patterns,
            metadata = metadataElements,
            always = alwaysElement,
            tool = domain.tool
        )
    }

    /**
     * Parse the `always` field which may be:
     * - V1: a JSON array of strings (e.g. ["*"]) → true if non-empty
     * - V2: a JSON boolean (e.g. true) → use directly
     * - null / missing → false
     */
    internal fun parseAlways(always: JsonElement?): Boolean {
        if (always == null) return false
        return when {
            always is kotlinx.serialization.json.JsonArray -> always.isNotEmpty()
            always.jsonPrimitive.content == "true" -> true
            else -> false
        }
    }
}
