package dev.leonardo.ocbeacon.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * OpenCode Server 消息分页游标编解码。
 * 游标 = base64url(JSON({"id": <msgId>, "time": <created>}))。
 */
object CursorCodec {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CursorPayload(val id: String, val time: Long)

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
}
