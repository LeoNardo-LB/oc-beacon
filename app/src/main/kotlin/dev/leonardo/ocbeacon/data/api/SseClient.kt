package dev.leonardo.ocbeacon.data.api

import android.util.Log
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.data.api.sse.parsers.*
import dev.leonardo.ocbeacon.domain.model.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import io.ktor.utils.io.ClosedReadChannelException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseClient"
private const val HEARTBEAT_TIMEOUT_MS = 40_000L
/** 单行上限：防止恶意/异常 server 推送超长行（无 \n 终结）导致 OOM。 */
private const val MAX_SSE_LINE_SIZE = 256 * 1024
/** 单事件上限：多条 data: 行累计超过此大小时丢弃整个事件（1MB，与上游 oc-remote 一致）。 */
private const val MAX_SSE_EVENT_SIZE = 1_048_576

/**
 * 读取原始字节直到遇到 \n，不做 UTF-8 解码。
 * 返回 null 表示 channel 已关闭且无更多数据。
 * 兼容 CRLF：跳过 \r 字节。
 */
private suspend fun ByteReadChannel.readRawLineBytes(): List<Byte>? {
    val result = mutableListOf<Byte>()
    try {
        while (true) {
            val b = readByte()
            if (b == '\n'.code.toByte()) break
            if (b == '\r'.code.toByte()) continue  // 兼容 CRLF
            result.add(b)
            if (result.size > MAX_SSE_LINE_SIZE) {
                // 单行 OOM 防护：返回 null 让外层跳出读循环并重连。
                Log.e(TAG, "SSE line exceeds $MAX_SSE_LINE_SIZE bytes, aborting read")
                return null
            }
        }
    } catch (e: ClosedReadChannelException) {
        if (result.isEmpty()) return null
    }
    return result
}

/**
 * 将 byte 块列表拼接为完整字节数组，然后一次性 UTF-8 解码。
 */
private fun buildStringFromBytes(chunks: List<List<Byte>>): String {
    if (chunks.isEmpty()) return ""
    // SSE 规范：多条 data: 行必须以 \n（LF）连接。
    // 之前的实现未加分隔符直接拼接，导致多行 JSON
    // payload 中的换行丢失（例如 Markdown 表格行）。
    val separatorCount = chunks.size - 1
    val totalSize = chunks.sumOf { it.size } + separatorCount
    val array = ByteArray(totalSize)
    var pos = 0
    for ((idx, chunk) in chunks.withIndex()) {
        if (idx > 0) {
            array[pos++] = '\n'.code.toByte()  // SSE 规范：data: 行之间以 \n 分隔
        }
        for (b in chunk) {
            array[pos++] = b
        }
    }
    return array.toString(Charsets.UTF_8)
}

/**
 * 追加一条 data 行到事件 buffer，带事件级 OOM 防护。
 * 多条 data 行累计超过 [maxEventSize] 时清空 buffer 并跳过当前 payload
 * （超大事件通常是异常情况，跳过比断连更友好；下一帧仍可正常解析）。
 *
 * 可见性为 internal 以支持单元测试（[maxEventSize] 参数允许测试用小限制快速验证）。
 */
internal fun appendDataLine(
    buffer: MutableList<List<Byte>>,
    payload: List<Byte>,
    maxEventSize: Int = MAX_SSE_EVENT_SIZE
) {
    val projected = buffer.sumOf { it.size } + payload.size + buffer.size // buffer.size ≈ \n 分隔符数
    if (projected > maxEventSize) {
        Log.w(TAG, "SSE event exceeds $maxEventSize bytes (${buffer.size + 1} data lines), clearing buffer")
        buffer.clear()
        // 不 add 当前 payload — 丢弃这个超大事件
    } else {
        buffer.add(payload)
    }
}

/**
 * SSE（Server-Sent Events）客户端
 *
 * 无状态——所有连接信息来自 [ServerConnection] 参数。
 * 可安全地并发用于多个服务器。
 */
@Singleton
class SseClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    private val parsers: List<SseEventParser> = listOf(
        MiscEventParser(),
        SessionEventParser(json),
        MessageEventParser(json),
        PermissionEventParser(),
        QuestionEventParser(),
        PtyEventParser(),
        SessionNextEventParser(json)
    )

    /** session.next 解析器的公共访问器（供测试使用）。 */
    val sessionNextParser: SessionNextEventParser get() = parsers.filterIsInstance<SessionNextEventParser>().firstOrNull()
        ?: throw IllegalStateException("SessionNextEventParser not found in parser list")

    /**
     * 来自活跃全局事件连接的原始 SSE JSON 字符串。
     * V2 管线消费此流以避免重复的 HTTP 连接。
     * 在 V1 解析之前发射——消费者能看到每一个非心跳 data 帧。
     */
    val rawSseEvents: MutableSharedFlow<String> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 外部消费者（V2 管线）的只读访问。 */
    val rawSseEventFlow: SharedFlow<String> = rawSseEvents.asSharedFlow()

    /**
     * 连接到全局事件流。
     * 返回一个发射 SSE 事件的 Flow。
     * 该 Flow 不会在内部自动重连——调用方应自行处理
     * 重连（service 已实现指数退避）。
     */
    fun connectToGlobalEvents(conn: ServerConnection, directory: String? = null): Flow<SseEvent> = flow {
        val sseUrl = "${conn.baseUrl}/global/event"
        Log.i(TAG, "Connecting to SSE: $sseUrl (auth=${conn.authHeader != null})")

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
            Log.i(TAG, "SSE response: status=$statusCode, contentType=${response.headers["content-type"]}")

            if (statusCode == 401) {
                Log.e(TAG, "SSE auth failed (401). Check username/password.")
                throw SseAuthException("Authentication failed (401)")
            }

            if (statusCode !in 200..299) {
                Log.e(TAG, "SSE failed with HTTP $statusCode")
                throw SseConnectionException("HTTP $statusCode")
            }

            val channel = response.bodyAsChannel()
            var lastHeartbeat = System.currentTimeMillis()
            val buffer = mutableListOf<List<Byte>>()
            var eventCount = 0

            Log.i(TAG, "SSE stream opened, reading events...")

            while (!channel.isClosedForRead) {
                if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "Heartbeat timeout after $eventCount events, reconnecting...")
                    break
                }

                val lineBytes = channel.readRawLineBytes() ?: break

                if (lineBytes.isEmpty()) {
                    // 空白行 = SSE event 边界 → 解码整个 buffer
                    val data = buildStringFromBytes(buffer)
                    if (data.isNotEmpty()) {
                        try {
                            // 为 V2 管线发射原始 JSON（在 V1 解析之前）
                            rawSseEvents.tryEmit(data)
                            val event = parseEvent(data)
                            if (event != null) {
                                eventCount++
                                if (event is SseEvent.ServerHeartbeat) {
                                    lastHeartbeat = System.currentTimeMillis()
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Heartbeat received (total events: $eventCount)")
                                } else {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Event #$eventCount: ${event::class.simpleName}")
                                    emit(event)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse error: ${data.take(200)}", e)
                        }
                        buffer.clear()
                    }
                } else {
                    // data: 行 → 提取 payload 的原始字节
                    val prefix = "data:".encodeToByteArray()
                    var start = 0
                    if (lineBytes.size >= prefix.size &&
                        lineBytes.subList(0, prefix.size) == prefix.toList()) {
                        start = prefix.size
                        if (start < lineBytes.size && lineBytes[start] == ' '.code.toByte()) {
                            start++  // 跳过 "data: " 中的空格
                        }
                    }
                    if (start < lineBytes.size) {
                        appendDataLine(buffer, lineBytes.subList(start, lineBytes.size))
                    }
                }
            }

            Log.w(TAG, "SSE stream closed after $eventCount events")
        }
    }

    /**
     * 连接到实例级事件流（V2）。
     * GET /event
     * 返回一个发射 SSE 事件的 Flow。
     */
    fun connectToInstanceEvents(conn: ServerConnection, directory: String? = null): Flow<SseEvent> = flow {
        val sseUrl = "${conn.baseUrl}/event"
        Log.i(TAG, "Connecting to instance SSE: $sseUrl (auth=${conn.authHeader != null})")

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
            Log.i(TAG, "Instance SSE response: status=$statusCode")

            if (statusCode == 401) {
                throw SseAuthException("Authentication failed (401)")
            }

            if (statusCode !in 200..299) {
                throw SseConnectionException("HTTP $statusCode")
            }

            val channel = response.bodyAsChannel()
            var lastHeartbeat = System.currentTimeMillis()
            val buffer = mutableListOf<List<Byte>>()
            var eventCount = 0

            while (!channel.isClosedForRead) {
                if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "Instance SSE heartbeat timeout after $eventCount events")
                    break
                }

                val lineBytes = channel.readRawLineBytes() ?: break

                if (lineBytes.isEmpty()) {
                    // 空白行 = SSE event 边界 → 解码整个 buffer
                    val data = buildStringFromBytes(buffer)
                    if (data.isNotEmpty()) {
                        try {
                            val event = parseEvent(data)
                            if (event != null) {
                                eventCount++
                                if (event is SseEvent.ServerHeartbeat) {
                                    lastHeartbeat = System.currentTimeMillis()
                                } else {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Instance event #$eventCount: ${event::class.simpleName}")
                                    emit(event)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Instance parse error: ${data.take(200)}", e)
                        }
                        buffer.clear()
                    }
                } else {
                    // data: 行 → 提取 payload 的原始字节
                    val prefix = "data:".encodeToByteArray()
                    var start = 0
                    if (lineBytes.size >= prefix.size &&
                        lineBytes.subList(0, prefix.size) == prefix.toList()) {
                        start = prefix.size
                        if (start < lineBytes.size && lineBytes[start] == ' '.code.toByte()) {
                            start++  // 跳过 "data: " 中的空格
                        }
                    }
                    if (start < lineBytes.size) {
                        appendDataLine(buffer, lineBytes.subList(start, lineBytes.size))
                    }
                }
            }

            Log.w(TAG, "Instance SSE stream closed after $eventCount events")
        }
    }

    /**
     * 从原始 JSON 解析 SSE 事件。
     * 全局端点包装事件：{directory, payload: {type, properties}}
     * 实例级端点直接发送：{type, properties}
     */
    private fun parseEvent(data: String): SseEvent? {
        val root = json.parseToJsonElement(data).jsonObject

        val payload = root["payload"]?.jsonObject ?: root
        val type = payload["type"]?.jsonPrimitive?.content ?: return null
        val properties = payload["properties"]?.jsonObject ?: JsonObject(emptyMap())

        return parseEventByType(type, properties)
    }

    private fun parseEventByType(type: String, props: JsonObject): SseEvent? {
        for (parser in parsers) {
            if (parser.canParse(type)) {
                return parser.parse(type, props)
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Unhandled event: $type")
        return null
    }

    /**
     * 为向后兼容保留的公共 API（供测试使用）。
     * 委托给 [SessionNextEventParser]。
     */
    fun parseSessionNextEvent(type: String, props: JsonObject): SessionNextEvent {
        return sessionNextParser.parseSessionNextEvent(type, props)
    }
}

// ============ SSE 读取超时跟踪 ============

/**
 * SSE 读取超时行为的常量。
 */
object SseClientDefaults {
    const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    const val MAX_CONSECUTIVE_TIMEOUTS = 5
    const val COOLDOWN_DURATION_MS = 300_000L
}

/**
 * 跟踪连续 SSE 读取超时并管理冷却状态。
 *
 * 在连续 [maxConsecutiveTimeouts] 次超时后，跟踪器进入
 * 冷却期（[cooldownDurationMs]），在此期间重连会被延迟。
 */
class SseReadTimeoutTracker(
    val maxConsecutiveTimeouts: Int = SseClientDefaults.MAX_CONSECUTIVE_TIMEOUTS,
    val cooldownDurationMs: Long = SseClientDefaults.COOLDOWN_DURATION_MS
) {
    var consecutiveTimeouts: Int = 0
        private set
    private var cooldownUntilMs: Long = 0L

    /** 记录一次读取超时事件。 */
    fun recordTimeout() {
        consecutiveTimeouts++
    }

    /** 记录一次成功读取——重置连续计数器。 */
    fun recordSuccess() {
        consecutiveTimeouts = 0
    }

    /** 跟踪器是否已达到冷却阈值。 */
    fun shouldEnterCooldown(): Boolean = consecutiveTimeouts >= maxConsecutiveTimeouts

    /** 进入冷却模式。 */
    fun enterCooldown() {
        cooldownUntilMs = System.currentTimeMillis() + cooldownDurationMs
    }

    /** 是否当前处于冷却期内。 */
    fun isInCooldown(): Boolean = System.currentTimeMillis() < cooldownUntilMs

    /** 完全重置跟踪器（同时清除超时计数和冷却状态）。 */
    fun reset() {
        consecutiveTimeouts = 0
        cooldownUntilMs = 0L
    }
}

/** SSE 返回 401 时抛出 */
class SseAuthException(message: String) : Exception(message)

/** 非 2xx SSE 响应时抛出 */
class SseConnectionException(message: String) : Exception(message)
