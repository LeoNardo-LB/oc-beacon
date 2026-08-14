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

    /**
     * partId 派生规则：text/reasoning part 的稳定 id（含 type——#109 id 碰撞修复）。
     * 服务器 ordinal 按类型独立计数（同消息 reasoning[0] 与 text[0] 并存），
     * id 不含 type 会碰撞 → text.started 按 id 命中 Reasoning part 并替换（内容丢失）。
     * @param kind "text" 或 "reasoning"（与服务器 SSE 事件域对应）
     */
    fun derivePartId(assistantMessageId: String, kind: String, ordinal: Long): String =
        "${assistantMessageId}_${kind}_ord_${ordinal}"

    /**
     * 尝试将 V2 事件映射为领域 SseEvent。不识别的事件返回 null（由下游 parser 处理）。
     */
    fun map(type: String, props: JsonObject): SseEvent? = when (type) {
        // ============ 消息生命周期 ============

        // 用户消息播种。事件契约演进（2026-08-14 实测抓帧 + 官方 schema）：
        // 最新（next-17403+）：session.inbox.enqueued
        //   {sessionID, inboxID, item:{type:"user", payload:{text,agents}, delivery}}
        // 过渡（next-171xx）：session.input.admitted
        //   {sessionID, inputID, input:{type:"user", data:{text}, delivery}}
        // 早期（已废弃）：{sessionID, id, prompt:{text,files,agents}, delivery, timeCreated}
        // 事件名/字段读不到 → return null → 用户消息不播种（发送后不显示，重进才显示）
        "session.inbox.enqueued", "session.input.admitted" -> {
            val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: return null
            val inputId = props["inboxID"]?.jsonPrimitive?.contentOrNull
                ?: props["id"]?.jsonPrimitive?.contentOrNull
                ?: props["inputID"]?.jsonPrimitive?.contentOrNull
                ?: return null
            // 新版：item.payload.text；过渡：prompt.text；旧版：input.data.text
            val text = props["item"]?.jsonObject?.get("payload")?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
                ?: props["prompt"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: props["input"]?.jsonObject?.get("data")?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
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
                    modelId = modelIdFrom(props),
                    providerId = providerIdFrom(props)
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
            // 2026-08-14：解析 tokens（统计栏 token 占比圆环数据源——修复前
            // V2 下 tokens 恒 null，统计栏无 token 展示）
            val tokens = props["tokens"]?.jsonObject?.let { t ->
                Message.Assistant.Tokens(
                    input = t["input"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    output = t["output"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    reasoning = t["reasoning"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    cache = Message.Assistant.Tokens.Cache(
                        read = t["cache"]?.jsonObject?.get("read")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        write = t["cache"]?.jsonObject?.get("write")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    )
                )
            }
            SseEvent.MessageUpdated(
                Message.Assistant(
                    id = messageId,
                    sessionId = sessionId,
                    time = TimeInfo(System.currentTimeMillis()),
                    parentId = "",
                    cost = cost,
                    tokens = tokens
                )
            )
        }

        // ============ part 生命周期（started 创建 / ended 覆盖） ============

        "session.reasoning.started" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            SseEvent.MessagePartUpdated(
                Part.Reasoning(
                    id = derivePartId(messageId, "reasoning", ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = "",
                    // #109：started 事件无时间戳——用本地时刻；ended 合并时作为回退 start
                    time = Part.Reasoning.Time(start = System.currentTimeMillis())
                )
            )
        }

        "session.reasoning.ended" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            val text = props["text"]?.jsonPrimitive?.contentOrNull ?: ""
            SseEvent.MessagePartUpdated(
                Part.Reasoning(
                    id = derivePartId(messageId, "reasoning", ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = text,
                    // #109：start=0 表示未知——mergePart 回退到 started 记录的本地时刻
                    // （旧实现 start=ordinal → epoch 0 → "思考完毕 · 29778524m" 垃圾时长）
                    time = Part.Reasoning.Time(start = 0L, end = System.currentTimeMillis())
                )
            )
        }

        "session.text.started" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = derivePartId(messageId, "text", ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = "",
                    time = Part.Text.Time(start = System.currentTimeMillis())
                )
            )
        }

        "session.text.ended" -> {
            val (sessionId, messageId, ordinal) = partLocator(props) ?: return null
            val text = props["text"]?.jsonPrimitive?.contentOrNull ?: ""
            SseEvent.MessagePartUpdated(
                Part.Text(
                    id = derivePartId(messageId, "text", ordinal),
                    sessionId = sessionId,
                    messageId = messageId,
                    text = text,
                    time = Part.Text.Time(start = 0L, end = System.currentTimeMillis())
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
                partId = derivePartId(messageId, "reasoning", ordinal),
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
                partId = derivePartId(messageId, "text", ordinal),
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
     * 提取模型 ID。契约演进（2026-08-14 抓帧实证）：
     * - 新版（next-17403+）：model 是对象 {id, providerID, variant}——字段是 id
     * - 旧版：model 是 Ref 对象 {providerID, modelID} 或字符串
     */
    private fun modelIdFrom(props: JsonObject): String? {
        val model = props["model"] ?: return null
        return when {
            model is kotlinx.serialization.json.JsonPrimitive -> model.contentOrNull
            model is JsonObject -> {
                model["id"]?.jsonPrimitive?.contentOrNull
                    ?: model["modelID"]?.jsonPrimitive?.contentOrNull
                    ?: model["model"]?.jsonPrimitive?.contentOrNull
            }
            else -> null
        }
    }

    /** 提取模型提供商 ID（新版 model 对象 {id, providerID, variant}；旧版无 → null）。 */
    private fun providerIdFrom(props: JsonObject): String? {
        val model = props["model"] as? kotlinx.serialization.json.JsonObject ?: return null
        return model["providerID"]?.jsonPrimitive?.contentOrNull
    }
}
