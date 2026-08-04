package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.JsonObject

/**
 * 按类型解析 SSE 事件的策略接口。
 * 每个实现处理一部分事件类型。
 */
interface SseEventParser {
    /** 当此解析器能处理给定事件类型时返回 true。 */
    fun canParse(eventType: String): Boolean

    /**
     * 将事件属性解析为 [SseEvent]。
     * 解析失败或事件应被跳过时返回 null。
     */
    fun parse(eventType: String, props: JsonObject): SseEvent?
}
