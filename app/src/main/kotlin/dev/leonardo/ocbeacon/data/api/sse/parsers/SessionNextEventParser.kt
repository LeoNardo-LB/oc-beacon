package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.domain.model.SessionNextEvent
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "SseClient"

/**
 * 通过前缀匹配解析 session.next.* 事件。
 * 委托给 kotlinx.serialization 处理类型判别。
 */
class SessionNextEventParser(private val json: Json) : SseEventParser {

    private val prefix = "session.next."

    override fun canParse(eventType: String): Boolean = eventType.startsWith(prefix)

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        val nextEvent = parseSessionNextEvent(eventType, props)
        return SseEvent.SessionNext(nextEvent)
    }

    /**
     * 从类型字符串和属性解析 session.next.* 事件。
     * 在 SSE 事件类型以 "session.next." 开头时调用。
     * 使用 kotlinx.serialization 的 Json 解码为对应的 SessionNextEvent 变体。
     */
    fun parseSessionNextEvent(type: String, props: JsonObject): SessionNextEvent {
        return try {
            // 将 type 注入 props，使判别器能选择正确的变体
            val propsWithType = JsonObject(props + ("type" to JsonPrimitive(type)))
            val result = json.decodeFromString<SessionNextEvent>(propsWithType.toString())
            // 序列化器将未知类型路由到 Unknown，但不会从 "type" 填充 rawType
            if (result is SessionNextEvent.Unknown && result.rawType.isEmpty()) {
                result.copy(rawType = type, rawJson = props.toString())
            } else {
                result
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse session.next event: $type — ${e.message}")
            SessionNextEvent.Unknown(rawType = type, rawJson = props.toString())
        }
    }
}
