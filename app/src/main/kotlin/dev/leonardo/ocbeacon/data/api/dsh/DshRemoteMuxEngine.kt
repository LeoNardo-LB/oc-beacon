package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DshRemoteMux"

/** 客户端活性 ping（对齐 [DshWsEventClient] PING_INTERVAL_SECONDS）。 */
private const val PING_INTERVAL_SECONDS = 25L

/**
 * 引擎侧鉴权缝隙（[DshConnectionRegistry] 实现；单测注入假实现——registry
 * 构造依赖 Android Keystore 不可单测）。
 */
interface DshMuxAuth {
    fun cookieHeader(authority: String): String?
    fun setClientId(authority: String, clientId: String)
    fun markAuthFailure(authority: String)
    suspend fun awaitCookie(authority: String, intervalMs: Long = 2_000L): String
}

/**
 * 0.1.2 单 WS（/api/remote.mux）事件引擎（backlog #318；journal §2.4-2.5 实测配方）。
 *
 * 与 0.1.1 双流引擎（[DshWsEventEngine]）的关键差异：
 * - **可上行**：open/cancel 帧（0.1.1 纯下行、发帧即踢）；本引擎是唯一合法上行方；
 * - **三条逻辑流**：`$events`（全局：ready/emit/waterfall/cancel）+ `session/follow`
 *   （每会话：snapshot 基线 + 增量 SessionEvent）+ `session/control`（jobs/队列/投影
 *   整快照基线）；
 * - **鉴权**：升级请求带 Cookie；401 unexpected-response → 清凭据 + 挂起等 token
 *   （[DshConnectionRegistry.awaitCookie]，TokenNeeded 模态由连接层呈现）。
 *
 * 帧翻译策略：**引擎内合成 0.1.1 帧词汇**（[DshMuxSynthesizer]）——orchestrator/
 * DshEventMapper/handler 全链零改动。会话发现：连接后 session.list 全量 follow
 * （DSH 个人规模，几十量级）+ api-session/added 增量补 follow。
 */
class DshRemoteMuxEngine(
    private val scope: CoroutineScope,
    private val baseUrl: String,
    private val registry: DshMuxAuth,
    private val listSessionIds: suspend () -> List<String>,
    private val backoff: DshBackoff = DshBackoff(),
    private val opener: DshWebSocketOpener = DshWebSocketOpener { client, request, listener ->
        client.newWebSocket(request, listener)
    },
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build(),
) {

    private var generation: kotlinx.coroutines.Job? = null
    private var generationId = 0
    private val state = kotlinx.coroutines.flow.MutableStateFlow(DshWsConnectionState.Disconnected)

    /** 聚合连接状态（单流；对齐 [DshWsEventEngine.connectionState] 语义）。 */
    val connectionState: kotlinx.coroutines.flow.StateFlow<DshWsConnectionState> = state

    /** waterfall eventId → 合成帧 method（cancel 帧回查用）。 */
    private val pendingWaterfalls = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start(onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit) {
        synchronized(this) {
            stopLocked()
            generationId++
            val id = generationId
            generation = scope.launch { muxLoop(id, onFrame) }
        }
    }

    fun stop() {
        synchronized(this) { stopLocked() }
    }

    private fun stopLocked() {
        generation?.cancel()
        generation = null
        state.value = DshWsConnectionState.Disconnected
        pendingWaterfalls.clear()
    }

    /** 连接生命周期：开 → 开流 → 挂起等断 → 退避 → 重连（401 特判等 token）。 */
    private suspend fun muxLoop(
        generation: Int,
        onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit,
    ) {
        val attempts = AtomicInteger(0)
        while (true) {
            state.value = DshWsConnectionState.Connecting
            val closed = CompletableDeferred<Throwable?>()
            var unauthorized = false
            val syn = DshMuxSynthesizer(onFrame, onReady = { clientId -> registry.setClientId(baseUrl, clientId) })
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    state.value = DshWsConnectionState.Connected
                    attempts.set(0)
                    // $events：全局事件 + waterfall（clientId 由 ready 帧经 onReady 入注册表）
                    webSocket.send(openFrame(EVENTS_STREAM, EVENTS_ENDPOINT))
                    // session/control：jobs/队列/投影整快照基线
                    webSocket.send(openFrame(CONTROL_STREAM, "session/control"))
                    // 会话 follow：list 全量
                    scope.launch {
                        val ids = runCatching { listSessionIds() }.getOrElse { emptyList() }
                        for (sid in ids) webSocket.send(openFrame(followStreamId(sid), "session/follow", followArgs(sid)))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMuxMessage(webSocket, text, syn)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (response?.code == 401) unauthorized = true
                    if (!closed.isCompleted) closed.complete(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closed.isCompleted) closed.complete(null)
                }
            }
            val url = baseUrl.trim().trimEnd('/').let {
                when {
                    it.startsWith("https://", ignoreCase = true) -> "wss://" + it.removePrefix("https://").removePrefix("https://")
                    it.startsWith("http://", ignoreCase = true) -> "ws://" + it.removePrefix("http://").removePrefix("http://")
                    else -> it
                }
            } + "/api/remote.mux"
            val request = okhttp3.Request.Builder()
                .url(url)
                .apply { registry.cookieHeader(baseUrl)?.let { header("Cookie", it) } }
                .build()
            val socket = opener.open(client, request, listener)
            try {
                closed.await()
            } finally {
                runCatching { socket.cancel() }
            }
            state.value = DshWsConnectionState.Disconnected
            if (unauthorized) {
                // #317：cookie 缺失/过期——清凭据、挂起等 token（连接层 TokenNeeded 呈现）
                registry.markAuthFailure(baseUrl)
                AppLogger.w(TAG, "remote.mux 401 — 等待 token 交换后重连: " + baseUrl)
                registry.awaitCookie(baseUrl)
                continue
            }
            val attempt = attempts.getAndIncrement()
            AppLogger.w(TAG, "remote.mux 断开（代 " + generation + "，连续失败 " + (attempt + 1) + "），退避后重连")
            delay(backoff.delayMs(attempt))
        }
    }

    /** 单条下行消息分发（item/error/end；streamId 路由）。 */
    private fun handleMuxMessage(webSocket: WebSocket, text: String, syn: DshMuxSynthesizer) {
        val frame = runCatching {
            dev.leonardo.ocbeacon.data.api.dsh.DshMuxCodec.decode(text)
        }.getOrNull() ?: run {
            AppLogger.w(TAG, "丢弃无法解码的 mux 帧: " + text.take(120))
            return
        }
        when (frame) {
            is DshMuxCodec.Item -> syn.onItem(frame.streamId, frame.value, pendingWaterfalls)
            is DshMuxCodec.StreamError ->
                AppLogger.w(TAG, "流错误 streamId=" + frame.streamId + ": " + frame.error.toString().take(160))
            is DshMuxCodec.End -> Unit // 逻辑流结束（follow 会话消亡等）——无需动作
        }
    }

    private fun openFrame(streamId: String, endpoint: String, payloadArgs: JsonObject? = null): String =
        buildJsonObject {
            put("type", "open")
            put("streamId", streamId)
            put("endpoint", endpoint)
            put("payload", buildJsonObject { put("args", payloadArgs ?: buildJsonObject {}) })
        }.toString()

    private fun followArgs(sessionId: String): JsonObject = buildJsonObject {
        put("request", buildJsonObject {
            put("address", buildJsonObject {
                put("kind", "session")
                put("sessionId", sessionId)
            })
        })
    }

    private fun followStreamId(sessionId: String): String = FOLLOW_PREFIX + sessionId

    private companion object {
        const val EVENTS_STREAM = "evt"
        const val CONTROL_STREAM = "ctl"
        const val FOLLOW_PREFIX = "f:"
        /** $events 逻辑流端点（转义 $：字面量）。 */
        const val EVENTS_ENDPOINT = "\$events"
    }
}

/**
 * mux 下行帧编解码（journal §2.4：item/error/end 三型）。
 * 独立小对象便于单测直接喂 JSON 字符串。
 */
object DshMuxCodec {

    sealed interface Frame
    data class Item(val streamId: String, val value: JsonObject) : Frame
    data class StreamError(val streamId: String, val error: JsonElement) : Frame
    data class End(val streamId: String) : Frame

    fun decode(text: String): Frame? {
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return null
        val sid = (obj["streamId"] as? JsonPrimitive)?.content ?: return null
        return when ((obj["type"] as? JsonPrimitive)?.content) {
            "item" -> (obj["value"] as? JsonObject)?.let { Item(sid, it) }
            "error" -> obj["error"]?.let { StreamError(sid, it) }
            "end" -> End(sid)
            else -> null
        }
    }
}

/**
 * 0.1.2 mux 值 → 0.1.1 帧词汇合成器（纯回调输出；journal §2.4-2.5 对照表）。
 *
 * 输出帧全部走 [onFrame]（method, payload, rpcId）——与 [DshWsEventEngine] 的
 * 信封帧回调同形，orchestrator/mapper 无感。waterfall 的 eventId 占 rpcId 槽
 * （0.1.1 里该槽是 /api/respond 路由键；0.1.2 应答改走 $events/result，
 * [DshApiClient] 回程分支按 eventId + registry.clientId 组装——rpcId 槽语义延续）。
 */
class DshMuxSynthesizer(
    private val onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit,
    private val onReady: (clientId: String) -> Unit = {},
) {

    private var chunkRowsSkipped = 0L

    fun onItem(
        streamId: String,
        value: JsonObject,
        pendingWaterfalls: java.util.concurrent.ConcurrentHashMap<String, String>,
    ) {
        when {
            streamId == "evt" -> onEventsValue(value, pendingWaterfalls)
            streamId == "ctl" -> onControlValue(value)
            streamId.startsWith("f:") -> onFollowValue(streamId.removePrefix("f:"), value)
            else -> AppLogger.d(TAG, "未知逻辑流 " + streamId + " 值: " + value.toString().take(100))
        }
    }

    // ---- $events：ready / emit / waterfall / cancel ------------------------

    private fun onEventsValue(value: JsonObject, pending: java.util.concurrent.ConcurrentHashMap<String, String>) {
        when ((value["type"] as? JsonPrimitive)?.content) {
            "ready" -> {
                // clientId 入注册表（$events/result 应答凭据）——引擎注入回调
                (value["clientId"] as? JsonPrimitive)?.content?.let(onReady)
            }
            "emit" -> onEmit(
                (value["event"] as? JsonPrimitive)?.content ?: return,
                (value["args"] as? JsonArray ?: JsonArray(emptyList())),
            )
            "waterfall" -> onWaterfall(value, pending)
            "cancel" -> onCancel(value, pending)
            else -> AppLogger.d(TAG, "未知 \$events 值型: " + value.toString().take(120))
        }
    }

    private fun onEmit(event: String, args: JsonArray) {
        when (event) {
            "commands/change" -> frame("commands/change", buildJsonObject {})
            "api-session/added" -> {
                val summary = args.firstOrNull() as? JsonObject ?: return
                val sid = (summary["sessionId"] as? JsonPrimitive)?.content ?: return
                frame(
                    "host/session-added",
                    buildJsonObject {
                        put("sessionId", sid)
                        (summary["cwd"] as? JsonPrimitive)?.let { put("cwd", it) }
                        (summary["parentSession"] as? JsonPrimitive)?.let { put("parentSessionId", it) }
                    },
                )
            }
            "api-session/removed" -> {
                val sid = (args.firstOrNull() as? JsonPrimitive)?.content ?: return
                frame("host/session-removed", buildJsonObject { put("sessionId", sid) })
            }
            "api-session/status" -> {
                val sid = (args.getOrNull(0) as? JsonPrimitive)?.content ?: return
                val running = args.getOrNull(1) as? JsonPrimitive ?: return
                frame(
                    "host/session-status",
                    buildJsonObject {
                        put("sessionId", sid)
                        put("running", running)
                    },
                )
            }
            "api-session/error" -> {
                val sid = (args.getOrNull(0) as? JsonPrimitive)?.content ?: return
                val message = (args.getOrNull(1) as? JsonPrimitive)?.content ?: return
                frame("host/agent-error", buildJsonObject { put("sessionId", sid); put("message", message) })
            }
            else -> AppLogger.d(TAG, "emit 无消费端: " + event)
        }
    }

    private fun onWaterfall(
        value: JsonObject,
        pending: java.util.concurrent.ConcurrentHashMap<String, String>,
    ) {
        val eventId = (value["eventId"] as? JsonPrimitive)?.content ?: return
        val event = (value["event"] as? JsonPrimitive)?.content ?: return
        val agentId = (value["agentId"] as? JsonPrimitive)?.content ?: ""
        when {
            event == "user-questions/request" -> {
                val request = value["request"] as? JsonObject ?: return
                val questions = (request["questions"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { normalizeQuestionItem(it) }
                pending[eventId] = "question/requested"
                frame(
                    "question/requested",
                    buildJsonObject {
                        put("sessionId", agentId)
                        put("questions", JsonArray(questions))
                    },
                    rpcId = eventId,
                )
            }
            event.contains("approval", ignoreCase = true) || event.contains("permission", ignoreCase = true) -> {
                // 形态未实测（E2E 批次抓样本）——按 approval 合成，幂等可迭代
                pending[eventId] = "approval/requested"
                frame(
                    "approval/requested",
                    buildJsonObject {
                        put("sessionId", agentId)
                        put("approvalId", eventId)
                    },
                    rpcId = eventId,
                )
            }
            else -> AppLogger.d(TAG, "waterfall 未识别事件: " + event + " (eventId=" + eventId + ")")
        }
    }

    private fun onCancel(
        value: JsonObject,
        pending: java.util.concurrent.ConcurrentHashMap<String, String>,
    ) {
        val eventId = (value["eventId"] as? JsonPrimitive)?.content ?: return
        when (pending.remove(eventId)) {
            "question/requested" -> frame(
                "question/resolved",
                buildJsonObject { put("id", eventId); put("cancelled", true) },
                rpcId = eventId,
            )
            "approval/requested" -> frame(
                "approval/resolved",
                buildJsonObject { put("approvalId", eventId) },
                rpcId = eventId,
            )
            else -> AppLogger.d(TAG, "cancel 无对应 pending: " + eventId)
        }
    }

    /** 0.1.2 题目项 → mapper 期望键（multiSelect→multi_select；其余透传）。 */
    private fun normalizeQuestionItem(el: JsonElement): JsonObject? {
        val q = el as? JsonObject ?: return null
        return buildJsonObject {
            for ((k, v) in q) put(if (k == "multiSelect") "multi_select" else k, v)
        }
    }

    // ---- session/follow：snapshot + 增量 ------------------------------------

    private fun onFollowValue(sessionId: String, value: JsonObject) {
        when ((value["type"] as? JsonPrimitive)?.content) {
            "snapshot" -> {
                val cursor = (value["cursor"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                frame("session/subscribed", buildJsonObject {
                    put("sessionId", sessionId)
                    put("lastSeq", cursor)
                })
                (value["records"] as? JsonArray ?: JsonArray(emptyList())).forEach { emitRecord(sessionId, it) }
                (value["projections"] as? JsonObject)?.get("values")?.let { emitProjections(sessionId, it) }
            }
            "event" -> (value["event"] as? JsonObject)?.let {
                frame("session/event", buildJsonObject {
                    put("sessionId", sessionId)
                    put("event", it)
                })
            }
            else -> AppLogger.d(TAG, "follow 未知值型: " + value.toString().take(120))
        }
    }

    private fun emitRecord(sessionId: String, record: JsonElement) {
        val rec = record as? JsonObject ?: return
        when ((rec["type"] as? JsonPrimitive)?.content) {
            "event" -> (rec["event"] as? JsonObject)?.let {
                frame("session/event", buildJsonObject {
                    put("sessionId", sessionId)
                    put("event", it)
                })
            }
            "chunks" -> {
                chunkRowsSkipped++
                if (chunkRowsSkipped == 1L || chunkRowsSkipped % 100 == 0L) {
                    AppLogger.i(TAG, "chunk 压缩行跳过（累计 " + chunkRowsSkipped + "）——展示走 REST 补全")
                }
            }
            else -> AppLogger.d(TAG, "record 未知型: " + rec.toString().take(100))
        }
    }

    private fun emitProjections(sessionId: String, values: JsonElement) {
        val obj = values as? JsonObject ?: return
        for ((key, v) in obj) {
            if (v is JsonNull) continue
            frame("session/projection", buildJsonObject {
                put("sessionId", sessionId)
                put("key", key)
                put("value", v)
            })
        }
    }

    // ---- session/control：baseline（jobs/队列/投影整快照） ------------------

    private fun onControlValue(value: JsonObject) {
        val baseline = (value["value"] as? JsonObject) ?: return
        (baseline["jobs"] as? JsonObject)?.forEach { (sid, jobs) ->
            frame("session/jobs", buildJsonObject {
                put("sessionId", sid)
                put("jobs", jobs)
            })
        }
        (baseline["queues"] as? JsonObject)?.forEach { (sid, items) ->
            frame("session/queue", buildJsonObject {
                put("sessionId", sid)
                put("items", items)
            })
        }
        (baseline["projections"] as? JsonObject)?.forEach { (sid, proj) ->
            (proj as? JsonObject)?.get("values")?.let { emitProjections(sid, it) }
        }
    }

    private fun frame(method: String, payload: JsonObject, rpcId: String = "") {
        onFrame(method, payload, rpcId)
    }
}
