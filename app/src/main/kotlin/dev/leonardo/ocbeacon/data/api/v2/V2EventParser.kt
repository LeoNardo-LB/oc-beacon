package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.sse.parsers.SseEventParser
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "SseClientV2"

/**
 * V2 专属事件解析器——处理 OpenCode V2 的细粒度流式事件。
 *
 * V2 服务器在活跃会话期间高频发送这些事件（V1 没有）：
 * - session.reasoning.started/delta/ended —— 推理内容流
 * - session.tool.input.started/delta/ended —— 工具输入流
 * - session.tool.called/success/progress —— 工具调用状态
 * - session.step.started/ended —— agent 步骤
 * - shell.created/exited/deleted —— shell 生命周期
 * - session.usage.updated —— token 用量
 *
 * 这些事件当前不映射到具体 UI 行为（V2 会话信息通过 REST/SSE 的
 * message 级事件驱动渲染），但必须被解析为占位事件：
 * 1. 让 SseClientV2 能计数并重置心跳（数据流即存活证据）
 * 2. 让下游观察到会话有活动（而不是静默丢弃）
 *
 * 返回 SseEvent.SessionNext(Unknown) 包装，保持事件可观察且不破坏
 * 现有 SseEvent 密封类的结构。
 */
class V2EventParser(private val json: Json) : SseEventParser {

    private val handledPrefixes = listOf(
        "session.reasoning.",
        "session.tool.",
        "session.step.",
        "session.usage.",
        "session.text.",
        "session.message.",
        "shell."
    )

    override fun canParse(eventType: String): Boolean =
        handledPrefixes.any { eventType.startsWith(it) }

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        if (BuildConfig.DEBUG) {
            AppLogger.d(TAG, "V2 event: $eventType ${props.toString().take(150)}")
        }
        // 提取会话 ID（不同事件可能在不同字段）
        val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull
            ?: props["sessionId"]?.jsonPrimitive?.contentOrNull

        return SseEvent.SessionNext(
            dev.leonardo.ocbeacon.domain.model.SessionNextEvent.Unknown(
                rawType = eventType,
                rawJson = props.toString()
            )
        )
    }
}
