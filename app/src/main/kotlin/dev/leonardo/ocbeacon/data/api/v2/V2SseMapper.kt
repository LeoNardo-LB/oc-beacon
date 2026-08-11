package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * V2 SSE 事件 → 领域事件映射层（纯函数）。
 *
 * 2026-08-11 实测契约（docs/superpowers/specs/2026-08-11-v2-contract-alignment-design.md §3.2）：
 * v2 **不发** message.updated / message.part.updated / session.status；
 * 消息与 part 通过细粒度生命周期事件传递：
 *
 * ```
 * session.input.admitted → execution.started → step.started
 *   → (reasoning|text|tool).* 流式 part 事件
 * → step.ended → execution.succeeded
 * ```
 *
 * part 定位键：
 * - text/reasoning：`"${assistantMessageID}_ord_${ordinal}"`（v2 无 partID，ordinal 即定位键）
 * - tool：`call_id`（v2 tool part 的稳定 id）
 *
 * execution.started/succeeded 不在此映射（由 V2EventParser 处理为 FSM Busy/Idle）；
 * 消息 completed 由 REST 兜底（mergeMessages 时覆盖）。
 */
object V2SseMapper {

    private val taskToolNames = setOf("task", "subagent")

    /** partId 派生规则：text/reasoning part 的稳定 id。 */
    fun derivePartId(assistantMessageId: String, ordinal: Long): String =
        "${assistantMessageId}_ord_${ordinal}"

    /**
     * 尝试将 V2 事件映射为领域 SseEvent。不识别的事件返回 null（由下游 parser 处理）。
     */
    fun map(type: String, props: JsonObject): SseEvent? = when (type) {
        // ============ 消息生命周期 ============

        // 用户消息播种：{sessionID, inputID, input:{type:"user", data:{text}, delivery}}
        "session.input.admitted" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val inputId = props["inputID"]?.jsonPrimitive?.contentOrNull ?: return null
            val inputObj = props["input"]?.jsonObject
            val text = inputObj?.get("data")?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            SseEvent.MessageUpdated(
                Message.User(
                    id = inputId,
                    sessionId = sessionId,
                    time = TimeInfo(System.currentTimeMillis()),
                    summary = Message.User.UserSummary(body = text)
                )
            )
        }

        // assistant 消息创建：{sessionID, assistantMessageID, agent, model, snapshot?}
        "session.step.started" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.MessageUpdated(
                Message.Assistant(
                    id = messageId,
                    sessionId = sessionId,
                    time = TimeInfo(System.currentTimeMillis()),
                    parentId = props["parentID"]?.jsonPrimitive?.contentOrNull ?: "",
                    agent = props["agent"]?.jsonPrimitive?.contentOrNull,
                    modelId = modelIdFrom(props)
                )
            )
        }

        // step 结束（不完成 turn）：{sessionID, assistantMessageID, finish, cost, tokens, ...}
        "session.step.ended" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            // cost 可能是对象 {total: 1.25}（实测）或裸数字（0.9）——都兼容
            val cost = when (val c = props["cost"]) {
                is JsonObject -> c["total"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                is kotlinx.serialization.json.JsonPrimitive -> c.contentOrNull?.toDoubleOrNull()
                else -> null
            }
            SseEvent.MessageUpdated(
                Message.Assistant(
                    id = messageId,
                    sessionId = sessionId,
                    time = TimeInfo(System.currentTimeMillis()),
                    parentId = "",
                    cost = cost
                )
            )
        }

        // ============ part 生命周期（started 创建 / ended 覆盖） ============

        "session.reasoning.started" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            SseEvent.MessagePartUpdated(
                Part.Reasoning(
                    id = derivePartId(messageId, ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = ""
                )
            )
        }

        "session.reasoning.ended" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            val text = props["text"]?.jsonPrimitive?.contentOrNull ?: ""
            SseEvent.MessagePartUpdated(
                Part.Reasoning(
                    id = derivePartId(messageId, ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = text,
                    time = Part.Reasoning.Time(start = ordinal, end = System.currentTimeMillis())
                )
            )
        }

        "session.text.started" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = derivePartId(messageId, ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = ""
                )
            )
        }

        "session.text.ended" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            val text = props["text"]?.jsonPrimitive?.contentOrNull ?: ""
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = derivePartId(messageId, ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = text,
                    time = Part.Text.Time(start = ordinal, end = System.currentTimeMillis())
                )
            )
        }

        // ============ delta 流 ============

        "session.reasoning.delta" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.MessagePartDelta(
                sessionId = sessionId,
                messageId = messageId,
                partId = derivePartId(messageId, ordinal),
                field = "reasoning",
                delta = delta
            )
        }

        "session.text.delta" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.MessagePartDelta(
                sessionId = sessionId,
                messageId = messageId,
                partId = derivePartId(messageId, ordinal),
                field = "text",
                delta = delta
            )
        }

        // ============ 工具生命周期（id = call_id） ============

        "session.tool.input.started" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            val callId = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val toolName = props["name"]?.jsonPrimitive?.contentOrNull ?: ""
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = callId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    tool = toolName,
                    state = ToolState.Pending(input = emptyMap())
                )
            )
        }

        "session.tool.input.delta" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            val callId = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val delta = props["delta"]?.jsonPrimitive?.contentOrNull ?: return null
            SseEvent.MessagePartDelta(
                sessionId = sessionId,
                messageId = messageId,
                partId = callId,
                field = "output",
                delta = delta
            )
        }

        "session.tool.input.ended" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            val callId = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val text = props["text"]?.jsonPrimitive?.contentOrNull ?: ""
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = callId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    tool = "",
                    state = ToolState.Running(output = text, input = emptyMap())
                )
            )
        }

        "session.tool.called" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            val callId = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val input = props["input"]?.jsonObject?.mapValues { (_, v) -> v } ?: emptyMap()
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = callId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    tool = input["tool"]?.jsonPrimitive?.contentOrNull ?: "",
                    state = ToolState.Running(input = input, output = "")
                )
            )
        }

        "session.tool.success", "session.tool.failed" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
            val callId = props["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val contentText = props["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n") ?: ""
            val metadata = props["metadata"]?.jsonObject?.let { m ->
                // 双写 sessionId/sessionID（subagent 子会话跳转兼容）
                val mapped = m.mapValues { (_, v) -> v }.toMutableMap()
                val sid = m["sessionID"] ?: m["sessionId"]
                if (sid != null) {
                    mapped["sessionId"] = sid
                    mapped["sessionID"] = sid
                }
                mapped
            }
            val state = if (type == "session.tool.success") {
                ToolState.Completed(output = contentText, metadata = metadata?.ifEmpty { null })
            } else {
                val error = props["error"]?.let { elem ->
                    when {
                        elem is kotlinx.serialization.json.JsonPrimitive -> elem.contentOrNull
                        elem is JsonObject -> elem["message"]?.jsonPrimitive?.contentOrNull ?: elem.toString()
                        else -> elem.toString()
                    }
                } ?: ""
                ToolState.Error(error = error, metadata = metadata?.ifEmpty { null })
            }
            SseEvent.MessagePartUpdated(
                Part.Tool(
                    id = callId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = callId,
                    tool = "",
                    state = state
                )
            )
        }

        else -> null
    }

    /** 提取 (sessionId, assistantMessageID, ordinal) 定位三元组。 */
    private fun partLocator(props: JsonObject): Triple<String, String, Long>? {
        val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
        val messageId = props["assistantMessageID"]?.jsonPrimitive?.contentOrNull ?: return null
        val ordinal = props["ordinal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: props["ordinal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return null
        return Triple(sessionId, messageId, ordinal)
    }

    /**
     * 提取模型 ID（V2 model 是 Ref 对象 {providerID, modelID}，非字符串）。
     */
    private fun modelIdFrom(props: JsonObject): String? {
        val model = props["model"] ?: return null
        return when {
            model is kotlinx.serialization.json.JsonPrimitive -> model.contentOrNull
            model is JsonObject -> {
                model["modelID"]?.jsonPrimitive?.contentOrNull
                    ?: model["model"]?.jsonPrimitive?.contentOrNull
            }
            else -> null
        }
    }
}
