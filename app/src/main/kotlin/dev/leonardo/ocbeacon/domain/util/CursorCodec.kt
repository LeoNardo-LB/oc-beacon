package dev.leonardo.ocbeacon.domain.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * OpenCode Server 消息分页游标编解码。
 *
 * 两种格式：
 * - **V1 游标**：base64url(JSON({"id": <msgId>, "time": <created>})) —— [encode]/[decode]。
 *   V1 服务器 before 参数与此格式；V2 服务器忽略此格式（不兼容）。
 * - **V2 游标**：base64url(JSON({"id": <msgId>, "order": "desc", "direction": <dir>})) ——
 *   [encodeV2]/[decodeV2]。V2 服务器 cursor 参数用此格式；direction 决定翻页方向：
 *   "next" → 更旧（older），"previous" → 更新（newer）。
 *
 * 仅依赖 kotlinx.serialization + java.util.Base64，无 Room/Android 依赖，
 * 因此置于 domain 层供 [dev.leonardo.ocbeacon.domain.usecase.MessagePaginationUseCase] 直接使用。
 */
object CursorCodec {

    // encodeDefaults = true：V2CursorPayload 的 order="desc" 是默认值，必须序列化
    //（服务器 cursor 契约要求 order 字段；默认 encodeDefaults=false 会省略默认值字段）。
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class CursorPayload(val id: String, val time: Long)

    /** V2 游标载荷：direction="next"=更旧，"previous"=更新。 */
    @Serializable
    private data class V2CursorPayload(
        val id: String,
        val order: String = "desc",
        val direction: String,
    )

    fun encode(id: String, time: Long): String {
        val payload = json.encodeToString(CursorPayload(id = id, time = time))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    fun decode(cursor: String): Pair<String, Long>? {
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(cursor)
            val payload = json.decodeFromString<CursorPayload>(String(bytes, Charsets.UTF_8))
            payload.id to payload.time
        }.getOrNull()
    }

    /**
     * 编码 V2 双向游标。
     *
     * @param id 起点消息 ID（目标本身不在结果中）。
     * @param direction "next" → 返回 id 之前（更旧）的消息；"previous" → 返回 id 之后（更新）的消息。
     * @return base64url(JSON{id, order:"desc", direction})，可直接作为 V2 cursor 请求参数。
     */
    fun encodeV2(id: String, direction: V2Direction): String {
        val payload = json.encodeToString(V2CursorPayload(id = id, order = "desc", direction = direction.value))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    /** 解码 V2 游标，返回 (id, direction)；非 V2 格式返回 null。 */
    fun decodeV2(cursor: String): Pair<String, V2Direction>? {
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(cursor)
            val payload = json.decodeFromString<V2CursorPayload>(String(bytes, Charsets.UTF_8))
            val dir = V2Direction.fromValue(payload.direction) ?: return null
            payload.id to dir
        }.getOrNull()
    }

    /** V2 翻页方向。 */
    enum class V2Direction(val value: String) {
        /** 更旧方向（返回 id 之前的消息）。 */
        OLDER("next"),
        /** 更新方向（返回 id 之后的消息）。 */
        NEWER("previous");

        companion object {
            fun fromValue(value: String): V2Direction? = when (value) {
                "next" -> OLDER
                "previous" -> NEWER
                else -> null
            }
        }
    }
}
