package dev.leonardo.ocbeacon.data.api.v2

import dev.leonardo.ocbeacon.data.api.sse.parsers.SseEventParser
import dev.leonardo.ocbeacon.data.api.appendDataLine
import dev.leonardo.ocbeacon.data.api.buildStringFromBytes
import dev.leonardo.ocbeacon.data.api.readRawLineBytes
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.SseAuthException
import dev.leonardo.ocbeacon.data.api.SseConnectionException
import dev.leonardo.ocbeacon.domain.model.Message
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.TimeInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ClosedReadChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import io.ktor.utils.io.ByteReadChannel

private const val TAG = "SseClientV2"
private const val HEARTBEAT_TIMEOUT_MS = 40_000L

/** V2 事件信封元字段（非 payload 数据）——顶层格式剥除用。 */
private val EVENT_META_KEYS = setOf("id", "created", "type", "durable", "location", "event")

/**
 * V2 SSE 客户端——解析 OpenCode V2 的 Server-Sent Events 格式。
 *
 * V2 SSE 使用标准 SSE 帧格式（区别于 V1）：
 * ```
 * event: message.updated
 * data: {"info":{"id":"msg_...","role":"assistant",...}}
 * id: evt_xxx
 *
 * ```
 *
 * V1 SSE 则将所有信息打包在 `data:` 行的 JSON 中：
 * `data: {"type":"message.updated","properties":{"info":{...}}}`
 *
 * 本类复用 V1 的事件解析器（SseEventParser）——事件类型和属性结构相同，
 * 仅 SSE 帧的线格式不同。V2 从 `event:` 行获取事件类型，从 `data:` 行获取属性。
 */
@Singleton
class SseClientV2 @Inject constructor(
    private val json: Json,
    private val httpClient: io.ktor.client.HttpClient
) {
    private val parsers: List<SseEventParser> = listOf(
        dev.leonardo.ocbeacon.data.api.sse.parsers.MiscEventParser(),
        dev.leonardo.ocbeacon.data.api.sse.parsers.SessionEventParser(json),
        dev.leonardo.ocbeacon.data.api.sse.parsers.MessageEventParser(json),
        dev.leonardo.ocbeacon.data.api.sse.parsers.PermissionEventParser(),
        dev.leonardo.ocbeacon.data.api.sse.parsers.QuestionEventParser(),
        dev.leonardo.ocbeacon.data.api.sse.parsers.PtyEventParser(),
        dev.leonardo.ocbeacon.data.api.sse.parsers.SessionNextEventParser(json),
        V2EventParser(json)
    )

    // synthetic 实时通知（2026-08-12）：服务器 synthetic 注入经
    // session.input.admitted（带完整 input）→ session.input.promoted（带 inputID）
    // 两阶段广播。admitted 时缓存 input，promoted 时消费——TUI 前端即靠此机制
    // 实时显示后台任务完成通知（与 task 工具注入一致）。SSE 事件单线程顺序
    // 消费（同一 flow），HashMap 无需并发保护。
    private val pendingInputs = HashMap<String, JsonObject>()

    /**
     * 连接到 V2 事件流。
     * GET /api/event
     *
     * V2 事件流格式：标准 SSE（event: + data: + id: 帧）
     */
    fun connectToEvents(conn: ServerConnection, directory: String? = null): Flow<SseEvent> = flow {
        val sseUrl = "${conn.baseUrl}/api/event"
        AppLogger.i(TAG, "Connecting to V2 SSE: $sseUrl (auth=${conn.authHeader != null})")

        val statement = httpClient.prepareGet(sseUrl) {
            conn.authHeader?.let { header("Authorization", it) }
            header("Accept", "text/event-stream")
            directory?.let { header("x-opencode-directory", URLEncoder.encode(it, "UTF-8")) }

            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }

        statement.execute { response ->
            val statusCode = response.status.value
            AppLogger.i(TAG, "V2 SSE response: status=$statusCode")

            if (statusCode == 401) {
                throw SseAuthException("V2 Authentication failed (401)")
            }
            if (statusCode !in 200..299) {
                throw SseConnectionException("V2 HTTP $statusCode")
            }

            val channel = response.bodyAsChannel()
            var lastActivity = System.currentTimeMillis()
            var eventCount = 0

            AppLogger.i(TAG, "V2 SSE stream opened, reading events...")

            while (!channel.isClosedForRead) {
                // #108：阻塞读超时防护——半开 TCP（kill -9/NAT 静默断）下
                // readSseFrame 内部阻塞读永久挂起（socketTimeout=Long.MAX_VALUE），
                // 心跳检查永不执行 → 连接永久挂死，重连/冷却失效。
                // 40s 无数据强制断开走重连。
                val frame = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) {
                    readSseFrame(channel)
                }
                if (frame == null) {
                    if (!channel.isClosedForRead) {
                        AppLogger.w(TAG, "V2 SSE read timed out after ${HEARTBEAT_TIMEOUT_MS}ms (no data / half-open), reconnecting...")
                    }
                    break
                }
                if (frame.isEmpty()) {
                    // 空帧（注释行如 ": heartbeat" 等）也是连接存活的证据
                    lastActivity = System.currentTimeMillis()
                    continue
                }

                try {
                    val event = parseV2Event(frame)
                    // 任何事件到达都重置心跳计时器——数据流本身即连接存活的证据。
                    // V2 服务器在活跃流式传输期间不发送 server.heartbeat，
                    // 若只在收到 heartbeat 时重置，活跃会话每 40s 就会假超时断连。
                    lastActivity = System.currentTimeMillis()
                    eventCount++
                    if (event != null) {
                        if (event !is SseEvent.ServerHeartbeat) {
                            if (BuildConfig.DEBUG) {
                                // debug 级事件类型日志（不干扰正常日志）：逐条记录
                                // 非高频事件；delta 高频事件（文本/reasoning/tool 增量）
                                // 按 100 条节流汇总，避免流式期间刷屏。
                                val typeName = event::class.simpleName ?: "?"
                                if (event is SseEvent.MessagePartDelta) {
                                    if (eventCount % 100 == 1) {
                                        AppLogger.d(TAG, "[recv] ${typeName} (delta stream, eventCount=${eventCount})")
                                    }
                                } else {
                                    AppLogger.d(TAG, "[recv] ${typeName} eventCount=${eventCount}")
                                }
                            }
                            emit(event)
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "V2 parse error: ${frame.take(200)}", e)
                }
            }

            AppLogger.w(TAG, "V2 SSE stream closed after $eventCount events")
        }
    }

    /**
     * 读取一个完整的 SSE 帧（直到空行边界）。
     * 返回帧的所有行内容，或 null 表示流结束。
     * 空字符串表示跳过（注释行等）。
     */
    private suspend fun readSseFrame(channel: ByteReadChannel): String? {
        val dataBuffer = mutableListOf<List<Byte>>()
        var eventType: String? = null

        while (!channel.isClosedForRead) {
            val lineBytes = channel.readRawLineBytes() ?: return null

            if (lineBytes.isEmpty()) {
                // 空白行 = SSE 帧边界
                if (dataBuffer.isEmpty() && eventType == null) continue
                break
            }

            val line = lineBytes.toByteArray().toString(Charsets.UTF_8)

            when {
                line.startsWith("event:") -> {
                    eventType = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    val data = line.removePrefix("data:").let {
                        if (it.startsWith(" ")) it.substring(1) else it
                    }
                    appendDataLine(dataBuffer, data.toByteArray().toList())
                }
                line.startsWith("id:") -> {
                    // 事件 ID——当前不使用，忽略
                }
                line.startsWith(":") -> {
                    // SSE 注释行——忽略
                }
            }
        }

        val dataStr = buildStringFromBytes(dataBuffer)
        if (dataStr.isEmpty() && eventType == null) return ""

        // V2 事件格式：event 类型 + data JSON
        // 如果有 event 字段，用 event 作为类型；否则从 data JSON 中解析 type
        return if (eventType != null) {
            // V2 标准格式：event: type\ndata: {properties}
            // 构造 V1 兼容格式给解析器处理
            "$eventType\u0000$dataStr"
        } else {
            // 兼容模式：可能有些事件没有 event: 行
            // 尝试从 data JSON 中提取 type（V1 兼容）
            dataStr
        }
    }

    /**
     * 解析 V2 SSE 帧。
     *
     * V2 真实线格式（经 curl 实测确认）——单行 JSON 打包在 data: 行中：
     * ```
     * data: {"id":"evt_...","type":"session.reasoning.delta","data":{"sessionID":"...","delta":"..."}}
     * ```
     * 事件类型在 `type` 字段，payload 在 `data` 字段（对象）。
     *
     * 兼容格式（防御）：
     * - V1 风格：`{type, properties:{...}}`（payload 在 properties）
     * - 标准 SSE 帧：`event: xxx\ndata: {...}`（由 readSseFrame 用 \u0000 分隔）
     */
    private fun parseV2Event(frame: String): SseEvent? {
        // 标准 SSE 帧兼容路径：event:\ndata: 格式（\u0000 分隔）
        val parts = frame.split("\u0000", limit = 2)
        if (parts.size == 2) {
            return parseEventFrame(parts[0], parts[1])
        }

        // V2/V1 单行 JSON 路径
        val data = frame
        if (data.isEmpty()) return null

        val root = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            return null
        }

        val type = root["type"]?.jsonPrimitive?.content
        if (type != null) {
            // V2 payload 提取（2026-08-11 实测两种格式）：
            // 1. 旧格式：payload 在 data 字段（对象）——{type, data:{...}}
            // 2. 新格式：字段直接在顶层——{id, created, type, durable, location, sessionID, ...}
            //    （无 data 包装；剥除非数据字段后即 payload）
            // 类型防御：data/properties 为数组（如 session.instructions.updated 的 data
            // 是数组）时 jsonObject 扩展抛异常 → 显式判型回退顶层字段路径。
            val payload = root["data"]?.takeIf { it is JsonObject }?.jsonObject
                ?: root["properties"]?.takeIf { it is JsonObject }?.jsonObject  // V1 风格兼容
                ?: root.filterKeys { it !in EVENT_META_KEYS }
                    .takeIf { it.isNotEmpty() }
                    ?.let { JsonObject(it) }
                ?: JsonObject(emptyMap())
            return handleEvent(type, payload)
        }

        // 没有 type 字段——server.connected/heartbeat 等特殊事件
        if (data.contains("server.connected")) return SseEvent.ServerConnected
        if (data.contains("server.heartbeat")) return SseEvent.ServerHeartbeat
        return null
    }

    /** 处理 event: + data: 标准 SSE 帧 */
    private fun parseEventFrame(eventType: String, dataJson: String): SseEvent? {
        if (eventType == "server.connected") return SseEvent.ServerConnected
        if (eventType == "server.heartbeat") return SseEvent.ServerHeartbeat

        val properties = if (dataJson.isNotEmpty()) {
            try {
                json.parseToJsonElement(dataJson).jsonObject
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }
        } else {
            JsonObject(emptyMap())
        }
        return handleEvent(eventType, properties)
    }

    /**
     * 统一事件分发：优先解析器，特殊处理 V2 delta 流事件。
     */
    private fun handleEvent(type: String, props: JsonObject): SseEvent? {
        // synthetic 实时通知（2026-08-12 修复，与 TUI 机制对齐）：
        // 事件契约演进（2026-08-14 实测抓帧）：
        // 最新（next-17403+）：session.inbox.enqueued {sessionID, inboxID,
        //   item:{type, payload:{text,...}, delivery}} → 缓存 item；
        //   session.inbox.delivered {sessionID, inboxID} → 消费缓存。
        // 旧版：session.input.admitted {inputID, input:{type, data:{...}}}
        //   → 缓存 input；session.input.promoted {inputID} → 消费缓存。
        // 消费时 type != "user"（如 "synthetic"）→ 构造 MessageUpdated(User(role=type,
        //   summary.body=text)) → 下游 handleMessageUpdated 播种 Part.Text →
        //   实时渲染通知卡片（修复前只能等 REST 刷新，L3 ~15-20s）。
        if (type == "session.inbox.enqueued" || type == "session.input.admitted") {
            val inputID = props["inboxID"]?.jsonPrimitive?.contentOrNull
                ?: props["id"]?.jsonPrimitive?.contentOrNull
                ?: props["inputID"]?.jsonPrimitive?.contentOrNull
            // 新版 item（含 type/payload）；过渡 prompt；旧版 input（含 type/data）
            val input = props["item"]?.jsonObject
                ?: props["prompt"]?.jsonObject
                ?: props["input"]?.jsonObject
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "admitted: inputID=$inputID type=${input?.get("type")?.jsonPrimitive?.contentOrNull}")
            }
            if (inputID != null && input != null) {
                pendingInputs[inputID] = input
            }
        } else if (type == "session.inbox.delivered" || type == "session.input.promoted") {
            val inputID = props["inboxID"]?.jsonPrimitive?.contentOrNull
                ?: props["id"]?.jsonPrimitive?.contentOrNull
                ?: props["inputID"]?.jsonPrimitive?.contentOrNull
            val input = inputID?.let { pendingInputs.remove(it) }
            if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "promoted: inputID=$inputID cached=${input != null} pendingSize=${pendingInputs.size}")
            }
            if (input != null) {
                val inputType = input["type"]?.jsonPrimitive?.contentOrNull
                if (inputType != null && inputType != "user") {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull ?: ""
                    // 新版 item.payload；旧版 input.data
                    val dataObj = input["payload"]?.jsonObject ?: input["data"]?.jsonObject
                    val text = dataObj?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                    val description = dataObj?.get("description")?.jsonPrimitive?.contentOrNull
                    return SseEvent.MessageUpdated(
                        Message.User(
                            id = inputID,
                            sessionId = sessionId,
                            role = inputType, // "synthetic"（兼容其他非 user 类型）
                            time = TimeInfo(created = System.currentTimeMillis()),
                            // 2026-08-12：映射 metadata.agent（子代理类型）→ agent 字段
                            agent = dataObj?.get("metadata")?.jsonObject
                                ?.get("agent")?.jsonPrimitive?.contentOrNull,
                            summary = Message.User.UserSummary(
                                body = text,
                                title = description
                            )
                        )
                    )
                }
                // user 类型：admitted 时已播种消息，promoted 无需处理——
                // 消费掉避免 unhandled 噪音（2026-08-12）
                return null
            }
        }

        // #130：form 服务事件（form.created/replied/cancelled）→ 领域事件。
        // V2 question 工具已迁移到 form 服务（2026-08-14 官方确认），
        // 在 V2SseMapper 之前处理——form.created(kind=question) 映射为
        // QuestionAsked（复用现有提问卡片管道），replied/cancelled 映射为
        // QuestionReplied/QuestionRejected（复用卡片移除路径）。
        val formMapped = V2FormMapper.map(type, props)
        if (formMapped != null) return formMapped

        // V2SseMapper 优先：v2 细粒度生命周期事件 → 领域事件
        // （input.admitted / step / reasoning / text / tool 全映射）
        val mapped = V2SseMapper.map(type, props)
        if (mapped != null) return mapped

        // 兼容旧 delta 路径（mapV2DeltaEvent 保留，partId 已按 ordinal 派生）
        val deltaMapped = mapV2DeltaEvent(type, props)
        if (deltaMapped != null) return deltaMapped

        if (type == "server.connected") return SseEvent.ServerConnected
        if (type == "server.heartbeat") return SseEvent.ServerHeartbeat

        return parseEventByType(type, props)
    }

    /**
     * 将 V2 的 delta 流事件映射为 V1 MessagePartDelta。
     *
     * V2 事件 payload 结构：
     * - session.reasoning.delta: {sessionID, assistantMessageID, ordinal, delta}
     * - session.text.delta:     {sessionID, assistantMessageID, ordinal, delta}
     * - session.tool.input.delta: {sessionID, toolCallID, ordinal, delta}
     *
     * V1 MessagePartDelta 结构：{sessionId, messageId, partId, field, delta}
     * 映射：assistantMessageID → messageId，sessionID → sessionId
     */
    private fun mapV2DeltaEvent(type: String, props: JsonObject): SseEvent? {
        val delta = props["delta"]?.jsonPrimitive?.content ?: return null
        val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: return null
        val messageId = props["assistantMessageID"]?.jsonPrimitive?.content ?: return null

        val field = when (type) {
            "session.reasoning.delta" -> "reasoning"
            "session.text.delta" -> "text"
            else -> return null
        }
        // partId 派生（2026-08-11 修复）：V2 delta 无 partID，ordinal 即定位键——
        // 原硬编码 "" 导致每个 delta 新建 Part.Text(id="")，多 part 错乱。
        val ordinal = props["ordinal"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        return SseEvent.MessagePartDelta(
            sessionId = sessionId,
            messageId = messageId,
            partId = V2SseMapper.derivePartId(messageId, ordinal),
            field = field,
            delta = delta
        )
    }

    private fun parseEventByType(type: String, props: JsonObject): SseEvent? {
        for (parser in parsers) {
            if (parser.canParse(type)) {
                return parser.parse(type, props)
            }
        }
        if (BuildConfig.DEBUG) AppLogger.d(TAG, "V2 unhandled event: $type")
        return null
    }
}
