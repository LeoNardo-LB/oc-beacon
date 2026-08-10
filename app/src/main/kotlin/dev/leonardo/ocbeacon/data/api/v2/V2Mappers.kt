package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import dev.leonardo.ocbeacon.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * V2 API 响应包装层解包工具。
 *
 * V2 REST 响应格式：`{ "data": <实际数据> }`
 * 列表响应：`{ "data": [...], "cursor": { "previous": "...", "next": "..." } }`
 */
object V2ResponseWrapper {

    /**
     * 解包 V2 响应的 data 字段。
     * 如果输入没有 data 字段（非标准响应），直接返回根对象。
     */
    fun unwrap(root: JsonObject): JsonObject {
        return root["data"]?.jsonObject ?: root
    }

    /**
     * 解包 V2 列表响应。
     * 返回 data 数组和可选的 cursor.next。
     */
    fun unwrapList(root: JsonObject): Pair<List<JsonObject>, String?> {
        val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
        val items = data.mapNotNull { it.jsonObject }
        val nextCursor = root["cursor"]?.jsonObject?.get("next")?.jsonPrimitive?.contentOrNull
        return items to nextCursor
    }

    /**
     * 灵活解包列表响应——兼容 V2 服务器不同端点的不一致行为。
     *
     * 大多数 V2 端点返回 `{location:{}, data:[...], cursor:{}}` 包裹格式，
     * 但少数端点（如 /api/project）返回裸数组 `[...]`。
     * 本方法先尝试包裹格式，失败则回退到裸数组解析。
     */
    fun flexibleList(bodyText: String, json: Json): List<JsonObject> {
        val element = json.parseToJsonElement(bodyText)
        // 情况1：对象包裹 {data:[...]}
        if (element is JsonObject) {
            val dataField = element["data"]
            if (dataField is JsonArray) {
                return dataField.mapNotNull { it.jsonObject }
            }
            // 单个对象但无 data 字段——可能是直接返回的对象，包装为单元素列表
            return listOf(element)
        }
        // 情况2：裸数组 [...]
        if (element is JsonArray) {
            return element.mapNotNull { it.jsonObject }
        }
        return emptyList()
    }

    /**
     * 灵活解包单个对象响应——兼容 V2 包裹格式和直接返回。
     */
    fun flexibleObject(bodyText: String, json: Json): JsonObject {
        val element = json.parseToJsonElement(bodyText)
        if (element is JsonObject) {
            // 如果有 data 字段且 data 是对象，返回 data
            val dataField = element["data"]
            if (dataField is JsonObject) return dataField
            // 否则返回根对象
            return element
        }
        return JsonObject(emptyMap())
    }
}

/**
 * V2 Session JSON → 域模型 Session 转换器。
 *
 * V2 Session 字段映射：
 * - id → Session.id
 * - title → Session.title
 * - projectID → Session.projectId
 * - agent → Session.agent
 * - model → Session.model
 * - cost → Session.cost
 * - tokens → Session.tokens
 * - time.{created,updated,archived} → Session.time
 * - location.directory → Session.directory
 */
object V2SessionMapper {

    fun toSession(obj: JsonObject): Session {
        val timeObj = obj["time"]?.jsonObject
        val locationObj = obj["location"]?.jsonObject
        val modelObj = obj["model"]?.jsonObject
        val tokensObj = obj["tokens"]?.jsonObject

        return Session(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            projectId = obj["projectID"]?.jsonPrimitive?.contentOrNull ?: "",
            directory = locationObj?.get("directory")?.jsonPrimitive?.contentOrNull ?: "",
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            parentId = obj["parentID"]?.jsonPrimitive?.contentOrNull,
            time = Session.Time(
                created = timeObj?.get("created")?.jsonPrimitive?.long ?: 0L,
                updated = timeObj?.get("updated")?.jsonPrimitive?.long ?: 0L,
                archived = timeObj?.get("archived")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ),
            cost = obj["cost"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            agent = obj["agent"]?.jsonPrimitive?.contentOrNull,
            model = modelObj?.let { mv ->
                Session.SessionModel(
                    id = mv["id"]?.jsonPrimitive?.content ?: "",
                    providerId = mv["providerID"]?.jsonPrimitive?.content ?: "",
                    variant = mv["variant"]?.jsonPrimitive?.contentOrNull
                )
            },
            tokens = tokensObj?.let { tk ->
                val cacheObj = tk["cache"]?.jsonObject
                Session.SessionTokens(
                    input = tk["input"]?.jsonPrimitive?.intOrNull ?: 0,
                    output = tk["output"]?.jsonPrimitive?.intOrNull ?: 0,
                    reasoning = tk["reasoning"]?.jsonPrimitive?.intOrNull ?: 0,
                    cache = Session.SessionTokens.Cache(
                        read = cacheObj?.get("read")?.jsonPrimitive?.intOrNull ?: 0,
                        write = cacheObj?.get("write")?.jsonPrimitive?.intOrNull ?: 0
                    )
                )
            }
        )
    }
}

/**
 * V2 Message JSON → 域模型 Message/MessageWithParts 转换器。
 *
 * V2 消息用 `type` 字段判别（替代 V1 的 `role`）：
 * - type="user" → Message.User（text 字段）
 * - type="assistant" → Message.Assistant（content 数组 → parts）
 * - type="system"/"synthetic"/"shell"/"compaction" → 仅提取文本信息
 *
 * V2 assistant 消息的 content 数组元素类型：
 * - {type:"text", text:"..."} → Part.Text
 * - {type:"reasoning", text:"..."} → Part.Reasoning
 * - {type:"tool", id, name, state:{status, input, ...}} → Part.Tool
 */
object V2MessageMapper {

    /**
     * 将 V2 消息 JSON 转换为 MessageWithParts。
     * V2 消息内联了 parts（content 数组），无需额外 parts 请求。
     */
    fun toMessageWithParts(obj: JsonObject, sessionId: String): MessageWithParts? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val timeCreated = obj["time"]?.jsonObject?.get("created")?.jsonPrimitive?.long ?: 0L
        val timeCompleted = obj["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: obj["completed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

        return when (type) {
            "user" -> {
                val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = Message.User(
                    id = id,
                    sessionId = sessionId,
                    time = TimeInfo(created = timeCreated)
                )
                val parts = if (text.isNotEmpty()) {
                    listOf(Part.Text(
                        id = "",
                        sessionId = sessionId,
                        messageId = id,
                        text = text
                    ))
                } else emptyList()
                MessageWithParts(info = message, parts = parts)
            }
            "assistant" -> {
                val agent = obj["agent"]?.jsonPrimitive?.contentOrNull ?: "build"
                val modelObj = obj["model"]?.jsonObject
                val contentArray = obj["content"]?.jsonArray ?: JsonArray(emptyList())

                val message = Message.Assistant(
                    id = id,
                    sessionId = sessionId,
                    parentId = "", // V2 不提供 parentID 在消息层级
                    modelId = modelObj?.get("id")?.jsonPrimitive?.contentOrNull,
                    providerId = modelObj?.get("providerID")?.jsonPrimitive?.contentOrNull,
                    agent = agent,
                    time = TimeInfo(created = timeCreated, completed = timeCompleted)
                )

                val parts = contentArray.mapNotNull { element ->
                    val contentObj = element.jsonObject
                    val contentType = contentObj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    mapContentToPart(contentObj, contentType, sessionId, id)
                }

                MessageWithParts(info = message, parts = parts)
            }
            "system" -> {
                val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = Message.User(
                    id = id,
                    sessionId = sessionId,
                    role = "system",
                    time = TimeInfo(created = timeCreated)
                )
                val parts = if (text.isNotEmpty()) {
                    listOf(Part.Text(id = "", sessionId = sessionId, messageId = id, text = text))
                } else emptyList()
                MessageWithParts(info = message, parts = parts)
            }
            "synthetic" -> {
                val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val message = Message.User(
                    id = id,
                    sessionId = sessionId,
                    role = "synthetic",
                    time = TimeInfo(created = timeCreated)
                )
                val parts = if (text.isNotEmpty()) {
                    listOf(Part.Text(id = "", sessionId = sessionId, messageId = id, text = text))
                } else emptyList()
                MessageWithParts(info = message, parts = parts)
            }
            else -> {
                // shell, compaction, agent-switched, model-switched, skill 等
                // 提取可用的文本信息
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["summary"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val message = Message.User(
                    id = id,
                    sessionId = sessionId,
                    role = type,
                    time = TimeInfo(created = timeCreated)
                )
                val parts = if (text.isNotEmpty()) {
                    listOf(Part.Text(id = "", sessionId = sessionId, messageId = id, text = text))
                } else emptyList()
                MessageWithParts(info = message, parts = parts)
            }
        }
    }

    /**
     * 将 V2 content 元素映射为 Part 域模型。
     */
    private fun mapContentToPart(
        obj: JsonObject,
        type: String,
        sessionId: String,
        messageId: String
    ): Part? {
        return when (type) {
            "text" -> Part.Text(
                id = "",
                sessionId = sessionId,
                messageId = messageId,
                text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
            )
            "reasoning" -> Part.Reasoning(
                id = "",
                sessionId = sessionId,
                messageId = messageId,
                text = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
            )
            "tool" -> {
                val toolId = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                val toolName = obj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val stateObj = obj["state"]?.jsonObject
                val status = stateObj?.get("status")?.jsonPrimitive?.contentOrNull ?: "completed"

                // 完整映射 V2 tool state → V1 ToolState
                // 关键：metadata 可能包含 subagent 子会话 ID（metadata.sessionID），
                // input 包含工具参数（如 subagent 的 description/prompt），
                // content 是工具输出——TaskToolCard 依赖这些实现子会话跳转。
                val inputMap = stateObj?.get("input")?.jsonObject?.let { obj2 ->
                    obj2.mapValues { (_, v) -> v }
                } ?: emptyMap()
                val metadataMap = stateObj?.get("metadata")?.jsonObject?.let { obj2 ->
                    val mapped = obj2.mapValues { (_, v) -> v }.toMutableMap()
                    // V2 用 metadata.sessionID（大写），V1 TaskToolCard 读取 sessionId（小写）
                    // 双写兼容，让子会话跳转在两种格式下都可用
                    val sessionIdUpper = obj2["sessionID"] ?: obj2["sessionId"]
                    if (sessionIdUpper != null) {
                        mapped["sessionId"] = sessionIdUpper
                        mapped["sessionID"] = sessionIdUpper
                    }
                    mapped
                } ?: emptyMap()
                val outputText = stateObj?.get("content")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString("\n") ?: ""

                val toolState = when (status) {
                    "streaming", "running" -> ToolState.Running(
                        input = inputMap,
                        output = outputText,
                        metadata = metadataMap.ifEmpty { null }
                    )
                    "completed" -> ToolState.Completed(
                        input = inputMap,
                        output = outputText,
                        metadata = metadataMap.ifEmpty { null }
                    )
                    "error" -> ToolState.Error(
                        input = inputMap,
                        error = stateObj?.get("error")?.jsonPrimitive?.contentOrNull ?: ""
                    )
                    else -> ToolState.Pending(input = inputMap)
                }

                Part.Tool(
                    id = toolId,
                    sessionId = sessionId,
                    messageId = messageId,
                    callId = toolId,
                    tool = toolName,
                    state = toolState
                )
            }
            else -> Part.Unknown(
                id = "",
                sessionId = sessionId,
                messageId = messageId
            )
        }
    }
}
