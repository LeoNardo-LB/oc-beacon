package dev.leonardo.ocbeacon.data.api.sse.parsers

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import kotlinx.serialization.json.*

private const val TAG = "MessageEventParser"

/**
 * 解析消息相关事件：
 * - message.updated, message.removed
 * - message.part.updated, message.part.delta, message.part.removed
 */
class MessageEventParser(private val json: Json) : SseEventParser {

    private val handledTypes = setOf(
        "message.updated", "message.removed",
        "message.part.updated", "message.part.delta", "message.part.removed"
    )

    override fun canParse(eventType: String): Boolean = eventType in handledTypes

    override fun parse(eventType: String, props: JsonObject): SseEvent? {
        return try {
            when (eventType) {
                "message.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: return null
                    val message = parseMessage(infoObj) ?: return null
                    SseEvent.MessageUpdated(info = message)
                }

                "message.removed" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    SseEvent.MessageRemoved(sessionId = sessionId, messageId = messageId)
                }

                "message.part.updated" -> {
                    val partObj = props["part"]?.jsonObject ?: return null
                    val part = parsePart(partObj) ?: return null
                    SseEvent.MessagePartUpdated(part = part)
                }

                "message.part.delta" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    val partId = props.str("partID")
                    val field = props.str("field", "text")
                    val delta = props.str("delta")
                    SseEvent.MessagePartDelta(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId,
                        field = field,
                        delta = delta
                    )
                }

                "message.part.removed" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    val partId = props.str("partID")
                    SseEvent.MessagePartRemoved(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse $eventType: ${e.message}", e)
            null
        }
    }

    private fun parseMessage(obj: JsonObject): Message? {
        val role = obj["role"]?.jsonPrimitive?.content ?: return null
        return when (role) {
            "user" -> json.decodeFromJsonElement<Message.User>(obj)
            "assistant" -> json.decodeFromJsonElement<Message.Assistant>(obj)
            else -> {
                AppLogger.w(TAG, "Unknown message role: $role")
                null
            }
        }
    }

    private fun parsePart(obj: JsonObject): Part? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return try {
            when (type) {
                "text" -> json.decodeFromJsonElement<Part.Text>(obj)
                "reasoning" -> json.decodeFromJsonElement<Part.Reasoning>(obj)
                "tool" -> {
                // V2 兼容：V2 结构用 name 字段（V1 用 tool）、id 即 callID、无 sessionID/messageID
                // 反序列化前补全字段，避免 MissingFieldException 且保留工具名
                val normalized = obj.toMutableMap()
                if (normalized["name"] != null && normalized["tool"] == null) {
                    normalized["tool"] = normalized["name"]!!
                }
                if (normalized["callID"] == null && normalized["id"] != null) {
                    normalized["callID"] = normalized["id"]!!
                }
                if (normalized["sessionID"] == null) {
                    normalized["sessionID"] = JsonPrimitive("")
                }
                if (normalized["messageID"] == null) {
                    normalized["messageID"] = JsonPrimitive("")
                }
                // V2 双层 metadata 展平：state.metadata 可能为 {metadata: {sessionID: ...}}
                // 反序列化前归一化，让 ToolState 拿到内层 metadata 并双写 sessionId/sessionID
                val stateObj = obj["state"]?.jsonObject
                if (stateObj != null) {
                    val normalizedState = stateObj.toMutableMap()
                    val meta = stateObj["metadata"]
                    if (meta is JsonObject) {
                        val inner = if (meta.size == 1 && meta["metadata"] is JsonObject) {
                            meta["metadata"]!!.jsonObject
                        } else meta
                        val mapped = inner.toMutableMap()
                        val sid = inner["sessionID"] ?: inner["sessionId"]
                        if (sid != null) {
                            mapped["sessionId"] = sid
                            mapped["sessionID"] = sid
                        }
                        normalizedState["metadata"] = JsonObject(mapped)
                    }
                    // V2 error 字段可能是对象 {type, message}，V1 ToolState.Error.error 期望字符串
                    val err = stateObj["error"]
                    if (err is JsonObject) {
                        normalizedState["error"] = JsonPrimitive(
                            err["message"]?.jsonPrimitive?.contentOrNull ?: err.toString()
                        )
                    }
                    normalized["state"] = JsonObject(normalizedState)
                }
                // V2 的 state.content 是 Tool.Content 数组，V1 Completed.output 期望纯文本字符串。
                // 反序列化后若 output 为空但有 content 数组，提取文本补全。
                val partObj = JsonObject(normalized)
                val decoded = json.decodeFromJsonElement<Part.Tool>(partObj)
                val contentText = stateObj?.get("content")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("\n")
                if (!contentText.isNullOrEmpty()) {
                    when (val st = decoded.state) {
                        is dev.leonardo.ocbeacon.domain.model.ToolState.Completed ->
                            decoded.copy(state = st.copy(output = contentText))
                        is dev.leonardo.ocbeacon.domain.model.ToolState.Running ->
                            decoded.copy(state = st.copy(output = contentText))
                        else -> decoded
                    }
                } else {
                    decoded
                }
            }
                "step-start" -> json.decodeFromJsonElement<Part.StepStart>(obj)
                "step-finish" -> json.decodeFromJsonElement<Part.StepFinish>(obj)
                "file" -> json.decodeFromJsonElement<Part.File>(obj)
                "snapshot" -> json.decodeFromJsonElement<Part.Snapshot>(obj)
                "patch" -> json.decodeFromJsonElement<Part.Patch>(obj)
                "subtask" -> json.decodeFromJsonElement<Part.Subtask>(obj)
                "compaction" -> json.decodeFromJsonElement<Part.Compaction>(obj)
                "retry" -> json.decodeFromJsonElement<Part.Retry>(obj)
                "abort" -> json.decodeFromJsonElement<Part.Abort>(obj)
                "agent" -> json.decodeFromJsonElement<Part.Agent>(obj)
                "session-turn" -> json.decodeFromJsonElement<Part.SessionTurn>(obj)
                else -> {
                    AppLogger.w(TAG, "Unknown part type: $type")
                    Part.Unknown(
                        id = obj.str("id"),
                        sessionId = obj.str("sessionID"),
                        messageId = obj.str("messageID")
                    )
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse part type=$type: ${e.message}", e)
            null
        }
    }
}
