package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.data.api.NonJsonResponseException
import dev.leonardo.ocbeacon.domain.model.FileDiff
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.ShellJob
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

/** 双向游标列表解包结果（[V2ResponseWrapper.unwrapListFull]）。 */
data class UnwrappedList(
    val items: List<JsonObject>,
    /** cursor.next —— 更旧方向游标。 */
    val nextCursor: String?,
    /** cursor.previous —— 更新方向游标。 */
    val previousCursor: String?,
)

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
     * 解包 V2 列表响应（含双向游标）—— 用于消息双向分页。
     *
     * V2 cursor 对象：`{previous, next}`（base64 的 JSON）：
     * - [UnwrappedList.nextCursor]（cursor.next）→ 更旧方向（older）。
     * - [UnwrappedList.previousCursor]（cursor.previous）→ 更新方向（newer）。
     */
    fun unwrapListFull(root: JsonObject): UnwrappedList {
        val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
        val items = data.mapNotNull { it.jsonObject }
        val cursorObj = root["cursor"]?.jsonObject
        val nextCursor = cursorObj?.get("next")?.jsonPrimitive?.contentOrNull
        val previousCursor = cursorObj?.get("previous")?.jsonPrimitive?.contentOrNull
        return UnwrappedList(items, nextCursor, previousCursor)
    }

    /**
     * 灵活解包列表响应——兼容 V2 服务器不同端点的不一致行为。
     *
     * 大多数 V2 端点返回 `{location:{}, data:[...], cursor:{}}` 包裹格式，
     * 但少数端点（如 /api/project）返回裸数组 `[...]`。
     * 本方法先尝试包裹格式，失败则回退到裸数组解析。
     */
    fun flexibleList(bodyText: String, json: Json): List<JsonObject> {
        dev.leonardo.ocbeacon.data.api.rejectHtmlResponse(bodyText)
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
        dev.leonardo.ocbeacon.data.api.rejectHtmlResponse(bodyText)
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
                    time = TimeInfo(created = timeCreated),
                    // 2026-08-12 修复：REST 加载同时生成 summary——Room parts
                    // 丢失/覆盖时（历史 bug：空 id 主键冲突）仍有兜底文本可提取，
                    // 快速导航 preview 与消息恢复不依赖 parts 完整性
                    summary = Message.User.UserSummary(body = text),
                )
                // 2026-08-16 根治（P0 附件恢复丢失）：读回的 user 消息 files 字段
                // 映射为 Part.File——原实现只映射 text，重进会话后图片缩略图/
                // 文件卡片消失。服务器契约（curl 实证）：
                // files:[{data:"<base64>", mime:"image/png", name, source:{type:"inline"}}]
                // url 重组为 dataUrl（data 与 mime 分离）；若元素直接给 uri 则优先。
                val filesArray = obj["files"] as? JsonArray
                val fileParts = filesArray?.mapIndexedNotNull { idx, fileEl ->
                    val fileObj = fileEl as? JsonObject ?: return@mapIndexedNotNull null
                    val mime = fileObj["mime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                    val name = fileObj["name"]?.jsonPrimitive?.contentOrNull
                    val uri = fileObj["uri"]?.jsonPrimitive?.contentOrNull
                        ?: fileObj["data"]?.jsonPrimitive?.contentOrNull?.let { data ->
                            if (data.startsWith("data:")) data else "data:$mime;base64,$data"
                        }
                    if (uri == null) return@mapIndexedNotNull null
                    Part.File(
                        id = "${id}_file$idx",
                        sessionId = sessionId,
                        messageId = id,
                        mime = mime,
                        filename = name,
                        url = uri,
                        source = fileObj["source"],
                    )
                }.orEmpty()
                val textParts = if (text.isNotEmpty()) {
                    listOf(Part.Text(
                        // 2026-08-12 修复：唯一 id（此前 "" 导致 Room 主键冲突
                        // 互相覆盖——实测测试会话 35 条 user 消息只剩 1 条有 parts）
                        id = "${id}_text",
                        sessionId = sessionId,
                        messageId = id,
                        text = text
                    ))
                } else emptyList()
                MessageWithParts(info = message, parts = textParts + fileParts)
            }
            "assistant" -> {
                val agent = obj["agent"]?.jsonPrimitive?.contentOrNull ?: "build"
                val modelObj = obj["model"]?.jsonObject
                val contentArray = obj["content"]?.jsonArray ?: JsonArray(emptyList())
                // token 统计图标回归修复（2026-08-19 用户报告顶栏图标消失）：
                // V2 REST 消息带 tokens/cost（实测 {input,output,reasoning,
                // cache:{read,write}}），原映射漏填 → lastContextTokens 恒 0 →
                // ChatTopBar 上下文进度圈（显示条件 tokens>0）永不出现。
                // beta-17639 SSE 不发 message.updated（整 turn 仅 session.* 事件），
                // REST 是 tokens 唯一可靠来源——此处漏映射即全链断。
                val tokensObj = obj["tokens"]?.jsonObject
                val cacheObj = tokensObj?.get("cache")?.jsonObject
                val message = Message.Assistant(
                    id = id,
                    sessionId = sessionId,
                    parentId = "", // V2 不提供 parentID 在消息层级
                    modelId = modelObj?.get("id")?.jsonPrimitive?.contentOrNull,
                    providerId = modelObj?.get("providerID")?.jsonPrimitive?.contentOrNull,
                    agent = agent,
                    time = TimeInfo(created = timeCreated, completed = timeCompleted),
                    cost = obj["cost"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    tokens = tokensObj?.let { tk ->
                        Message.Assistant.Tokens(
                            input = tk["input"]?.jsonPrimitive?.intOrNull ?: 0,
                            output = tk["output"]?.jsonPrimitive?.intOrNull ?: 0,
                            reasoning = tk["reasoning"]?.jsonPrimitive?.intOrNull ?: 0,
                            cache = Message.Assistant.Tokens.Cache(
                                read = cacheObj?.get("read")?.jsonPrimitive?.intOrNull ?: 0,
                                write = cacheObj?.get("write")?.jsonPrimitive?.intOrNull ?: 0
                            )
                        )
                    }
                )

                // #109（D2-01）：text/reasoning part id 用与 SSE 相同的派生规则
                // （messageId_type_ord_N，N 为该类型在 content 中的出现序）。
                // 旧实现 id="" 与 SSE 派生 id 契约错位 → mergePartsList 双保留 →
                // 已完结消息文本双份渲染。服务器 ordinal 按类型独立计数，
                // REST 按同类型出现顺序编号即与 SSE 对齐。
                var textOrdinal = 0L
                var reasoningOrdinal = 0L
                val parts = contentArray.mapNotNull { element ->
                    val contentObj = element.jsonObject
                    val contentType = contentObj["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val ordinal = when (contentType) {
                        "text" -> textOrdinal++
                        "reasoning" -> reasoningOrdinal++
                        else -> null
                    }
                    mapContentToPart(contentObj, contentType, sessionId, id, ordinal)
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
                // 2026-08-12：映射 metadata.agent（"Explore"/"general" 等子代理类型）
                // → Message.User.agent，供 SyntheticNotificationCard 展示具体类型。
                // 服务器 payload：metadata = {source:"subagent", childID, agent:"Explore", state:"completed"}
                val agent = obj["metadata"]?.jsonObject
                    ?.get("agent")?.jsonPrimitive?.contentOrNull
                val message = Message.User(
                    id = id,
                    sessionId = sessionId,
                    role = "synthetic",
                    time = TimeInfo(created = timeCreated),
                    agent = agent
                )
                val parts = if (text.isNotEmpty()) {
                    listOf(Part.Text(id = "", sessionId = sessionId, messageId = id, text = text))
                } else emptyList()
                MessageWithParts(info = message, parts = parts)
            }
            else -> {
                // shell, compaction, agent-switched, model-switched, skill 等
                // 提取可用的文本信息
                // compaction 失败时（status="failed"，如 "Nothing to compact yet"），
                // 提取 error.message 显示给用户 —— 否则失败静默无反馈
                // （2026-08-12 用户反馈"压缩会话点击无反应"根因之一）
                val isCompactionFailed = type == "compaction" &&
                    obj["status"]?.jsonPrimitive?.contentOrNull == "failed"
                val text = if (isCompactionFailed) {
                    obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: "Compaction failed"
                } else {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                        ?: obj["summary"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                }
                val message = Message.User(
                    id = id,
                    sessionId = sessionId,
                    role = type,
                    time = TimeInfo(created = timeCreated)
                )
                // 2026-08-15 修复（压缩完成无分割线）：compaction 消息生成
                // Part.Compaction（含摘要全文）——UI 渲染层靠
                // parts.any { it is Part.Compaction } 识别压缩点渲染
                // 可展开的压缩卡片（分割线+轻量边框样式，与 synthetic 通知
                // 卡片一致的视觉语言）；此前生成 Part.Text → 永不匹配 →
                // 用户点击压缩后界面无任何反馈。
                val parts = when {
                    type == "compaction" && text.isNotEmpty() ->
                        listOf(Part.Compaction(
                            id = "${id}_compaction",
                            sessionId = sessionId,
                            messageId = id,
                            summary = text
                        ))
                    text.isNotEmpty() -> listOf(Part.Text(id = "", sessionId = sessionId, messageId = id, text = text))
                    else -> emptyList()
                }
                MessageWithParts(info = message, parts = parts)
            }
        }
    }

    /**
     * V2 content 元素的 time {created, completed} → (start, end) 对。
     * created 缺失时返回 null（无法定 start）；completed 缺失时 end 为 null（仍流式）。
     */
    private fun mapV2PartTime(timeObj: JsonObject?): Pair<Long, Long?>? {
        val start = timeObj?.get("created")?.jsonPrimitive?.long ?: return null
        val end = timeObj["completed"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return start to end
    }

    /**
     * 将 V2 content 元素映射为 Part 域模型。
     */
    private fun mapContentToPart(
        obj: JsonObject,
        type: String,
        sessionId: String,
        messageId: String,
        ordinal: Long? = null
    ): Part? {
        return when (type) {
            "text" -> Part.Text(
                // #109：与 SSE 派生 id 对齐（ordinal 缺失时回退 ""，由
                // mergePartsList 内容去重兜底）
                id = ordinal?.let { V2SseMapper.derivePartId(messageId, "text", it) } ?: "",
                sessionId = sessionId,
                messageId = messageId,
                text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
                // V2 content 元素 time: {created, completed}——映射到 V1 {start, end}。
                // 缺失会导致 ReasoningBlock/文本流式判定（part.time.end == null）永远
                // 为 true → 已结束消息仍显示 "Thinking…" 且计时器一直涨（2026-08-11 修复）。
                time = mapV2PartTime(obj["time"]?.jsonObject)
                    ?.let { (s, e) -> Part.Text.Time(start = s, end = e) }
            )
            "reasoning" -> Part.Reasoning(
                id = ordinal?.let { V2SseMapper.derivePartId(messageId, "reasoning", it) } ?: "",
                sessionId = sessionId,
                messageId = messageId,
                text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
                time = mapV2PartTime(obj["time"]?.jsonObject)
                    ?.let { (s, e) -> Part.Reasoning.Time(start = s, end = e) }
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
                    // V2 服务器 metadata 可能是双层嵌套：{metadata: {sessionID: ...}}
                    // 展平：若 obj2 只有一个 "metadata" 键且值为对象，取其内层
                    val inner = if (obj2.size == 1 && obj2["metadata"] is JsonObject) {
                        obj2["metadata"]!!.jsonObject
                    } else obj2
                    val mapped = inner.mapValues { (_, v) -> v }.toMutableMap()
                    // V2 用 metadata.sessionID（大写），V1 TaskToolCard 读取 sessionId（小写）
                    // 双写兼容，让子会话跳转在两种格式下都可用
                    val sessionIdUpper = inner["sessionID"] ?: inner["sessionId"]
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
                        error = stateObj?.get("error")?.let { elem ->
                            // V2 error 可能是字符串（V1 兼容）或对象（{type, message}）
                            when {
                                elem is JsonPrimitive -> elem.contentOrNull
                                elem is JsonObject -> elem["message"]?.jsonPrimitive?.contentOrNull
                                    ?: elem.toString()
                                else -> elem.toString()
                            }
                        } ?: ""
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
            "shell" -> {
                // V2 后台 shell part：{id, shellID, command, status, exit?, output?, time?, metadata?}
                Part.Shell(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    sessionId = sessionId,
                    messageId = messageId,
                    shellId = obj["shellID"]?.jsonPrimitive?.contentOrNull
                        ?: obj["shell_id"]?.jsonPrimitive?.contentOrNull
                        ?: "",
                    command = obj["command"]?.jsonPrimitive?.contentOrNull ?: "",
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
                    exit = obj["exit"]?.jsonPrimitive?.intOrNull,
                    output = obj["output"]?.jsonPrimitive?.contentOrNull,
                    time = obj["time"]?.jsonObject?.let { t ->
                        Part.Shell.Time(
                            start = t["start"]?.jsonPrimitive?.long ?: 0L,
                            end = t["end"]?.jsonPrimitive?.long
                        )
                    },
                    metadata = obj["metadata"]?.jsonObject?.let { m ->
                        m.mapValues { (_, v) -> v }
                    }
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

/**
 * V2 Shell.Info JSON → ShellJob 域模型映射。
 *
 * V2 Shell.Info：`{id, status, command, cwd, shell, file, pid, exit, metadata, time}`
 * - metadata.sessionID 标识归属会话
 * - time = {start, end?}
 */
object V2ShellMapper {

    fun toShellJob(obj: JsonObject): ShellJob {
        val metadataObj = obj["metadata"]?.jsonObject
        val sessionId = metadataObj?.get("sessionID")?.jsonPrimitive?.contentOrNull
            ?: metadataObj?.get("sessionId")?.jsonPrimitive?.contentOrNull
        val timeObj = obj["time"]?.jsonObject
        return ShellJob(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
            command = obj["command"]?.jsonPrimitive?.contentOrNull ?: "",
            cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull ?: "",
            shell = obj["shell"]?.jsonPrimitive?.contentOrNull ?: "",
            file = obj["file"]?.jsonPrimitive?.contentOrNull ?: "",
            pid = obj["pid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            exit = obj["exit"]?.jsonPrimitive?.intOrNull,
            sessionId = sessionId,
            startedAt = timeObj?.get("start")?.jsonPrimitive?.long,
            completedAt = timeObj?.get("end")?.jsonPrimitive?.long,
            metadata = metadataObj?.let { m -> m.mapValues { (_, v) -> v } }
        )
    }

    /**
     * 解包 shell 列表响应（`{location, data: [...]}` 或裸数组）。
     */
    fun shellList(bodyText: String, json: Json): List<ShellJob> =
        V2ResponseWrapper.flexibleList(bodyText, json).map { toShellJob(it) }
}
