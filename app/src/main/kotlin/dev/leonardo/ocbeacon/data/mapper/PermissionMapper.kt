package dev.leonardo.ocbeacon.data.mapper

import dev.leonardo.ocbeacon.data.dto.response.PermissionRequest
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 在 API DTO（PermissionRequest）与领域模型（SseEvent.PermissionAsked）之间映射。
 *
 * 关键差异：
 * - API：always 为 JsonElement?（可能是字符串数组或布尔值）；领域：always 为 Boolean
 * - API：metadata 为 Map<String, JsonElement>；领域：metadata 为 Map<String, String>
 */
object PermissionMapper {

    /** API DTO → 领域模型 */
    fun toDomain(dto: PermissionRequest): SseEvent.PermissionAsked {
        val alwaysBoolean = parseAlways(dto.always)
        val metadataStrings = dto.metadata?.mapValues { (_, v) ->
            v.jsonPrimitive.contentOrNull ?: v.toString()
        }
        return SseEvent.PermissionAsked(
            id = dto.id,
            sessionId = dto.sessionId,
            // 2026-08-16（F6）：V2 REST 条目无 permission/patterns 字段——
            // 官方 PermissionV2.Request 用 action/resources 表达同一语义，兜底映射。
            permission = dto.permission ?: dto.action ?: "",
            patterns = dto.patterns.ifEmpty { dto.resources },
            metadata = metadataStrings,
            always = alwaysBoolean,
            tool = dto.tool
        )
    }

    /** 领域模型 → API DTO */
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
     * 解析 `always` 字段，它可能是：
     * - V1：字符串的 JSON 数组（例如 ["*"]）→ 非空时为 true
     * - V2：JSON 布尔值（例如 true）→ 直接使用
     * - null / 缺失 → false
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
