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
 * - **四条逻辑流**：`$events`（全局：ready/emit/waterfall/cancel）+ `session/follow`
 *   （每会话：snapshot 基线 + 增量 SessionEvent）+ `session/control`（jobs/队列/投影
 *   整快照基线）+ `workspace/follow`（#311/#330：workspace 注册表基线 + 全增量——
 *   archived/upsert/remove/order）；
 * - **鉴权**：升级请求带 Cookie；401 unexpected-response → 清凭据 + 挂起等 token
 *   （[DshConnectionRegistry.awaitCookie]，TokenNeeded 模态由连接层呈现）。
 *
 * 帧翻译策略：**引擎内合成 0.1.1 帧词汇**（[DshMuxSynthesizer]）——orchestrator/
 * DshEventMapper/handler 全链零改动。会话发现：连接后 session.list **限界集**
 * follow（running 或 24h 内活跃——#319 生产实证 440 会话全量 follow 拖垮服务端）
 * + added / status(running=true) / activity 三事件动态补开（老会话再激活不丢流）。
 */
class DshRemoteMuxEngine(
    private val scope: CoroutineScope,
    private val baseUrl: String,
    private val registry: DshMuxAuth,
    /** 连接期批量 follow 目标（sessionId + wire 地址——子会话为 durable subagent 形态）。 */
    private val listFollowTargets: suspend () -> List<DshFollowTarget>,
    /** 动态补开时按 sessionId 解析目标（added/status/activity 只带 id，地址由
     *  调用方从 session.list 缓存/刷新解析）；null = 无法寻址，放弃补开。 */
    private val resolveFollowTarget: suspend (String) -> DshFollowTarget? = { null },
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

    /** waterfall eventId → 合成上下文（method + 会话 id；cancel 帧回查用）。 */
    private val pendingWaterfalls = java.util.concurrent.ConcurrentHashMap<String, PendingWaterfall>()

    /** 当前代活跃 socket（动态 follow 补开用；断开置 null；按代更替覆写）。 */
    @Volatile private var activeSocket: okhttp3.WebSocket? = null

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
        focusFollowOpener = null
        pendingFocusFollows.clear()
    }

    // ---- #333：聚焦 follow（窗口外会话进 ChatRoute 的开流兜底）----------------
    //
    // #319 限界集（running/近 24h）外的会话连接期不 follow；added/status/activity
    // 三事件动态补开也覆盖不到「冷只读重进」（无任何服务器事件）。用户进入
    // ChatRoute 时经 [requestFollow] 显式开流：follow snapshot（cursor + 尾页
    // records）即转录基线——REST session/page 的 throughSeq 前置（session.list
    // projections.asOfSeq 对无投影缓存的冷会话**合法缺席**：服务器 summarizeCold/
    // projectionsFor 仅在投影缓存命中时给列；probe 仅 ≤1KB 文件）不再是唯一
    // 转录入口，且开流观察会写回投影缓存（coldSnapshot write-back），后续
    // session.list/REST 分页随之恢复。

    /** 当前代的聚焦开流入口（捕获该代 followed 集与 activeSocket；代更替置 null）。 */
    @Volatile private var focusFollowOpener: ((String) -> Unit)? = null

    /** 连接未就绪时挂起的聚焦请求（onOpen 后统一补开，请求不丢）。 */
    private val pendingFocusFollows: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 请求对 [sessionId] 开 follow 流（幂等——已 follow 直接 no-op）。socket
     * 未就绪（连接代间隙/未连接）时请求挂起，待 onOpen 补开。
     */
    fun requestFollow(sessionId: String) {
        val opener = focusFollowOpener
        if (opener != null && activeSocket != null) {
            opener(sessionId)
        } else {
            pendingFocusFollows.add(sessionId)
        }
    }

    /** 连接生命周期：开 → 开流 → 挂起等断 → 退避 → 重连（401 特判等 token）。 */
    private suspend fun muxLoop(
        generation: Int,
        onFrame: (method: String, payload: JsonObject, rpcId: String) -> Unit,
    ) {
        val attempts = AtomicInteger(0)
        val followed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        // 动态 follow 补开：followed 去重 + 发送失败回滚名额（重连后全量兜底不漏）。
        // #310①：目标携带 wire 地址（子会话 = durable subagent 形态）——旧实现
        // 此处恒 {kind:session}，子会话补开必被服务器拒（A7 logcat 实证）。
        fun openFollow(target: DshFollowTarget) {
            val ws = activeSocket ?: return
            if (!followed.add(target.sessionId)) return
            val sent = runCatching {
                ws.send(openFrame(followStreamId(target.sessionId), "session/follow", target.followArgs()))
            }.getOrDefault(false)
            if (!sent) {
                followed.remove(target.sessionId)
                if (dev.leonardo.ocbeacon.BuildConfig.DEBUG) {
                    AppLogger.d(TAG, "动态 follow 发送失败（连接已断？）: " + target.sessionId)
                }
            }
        }
        // #333：本代聚焦开流入口（与 onSessionActive 同款异步解析，resolve 失败静默放弃）
        focusFollowOpener = { sid ->
            scope.launch {
                resolveFollowTarget(sid)?.let { openFollow(it) }
            }
        }
        while (true) {
            state.value = DshWsConnectionState.Connecting
            val closed = CompletableDeferred<Throwable?>()
            var unauthorized = false
            followed.clear()
            val syn = DshMuxSynthesizer(
                onFrame,
                onReady = { clientId -> registry.setClientId(baseUrl, clientId) },
                // #319（双轴审查补全）：added / status(running=true) / activity
                // 三事件动态补开——新建会话与 >24h 老会话再激活都不丢流
                // （限界窗口外的会话事件唯一入口）。#310①：回调只带 id，地址
                // 经 resolveFollowTarget 异步解析（session.list 缓存/刷新）。
                onSessionActive = { sid ->
                    scope.launch {
                        resolveFollowTarget(sid)?.let { openFollow(it) }
                    }
                },
            )
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    state.value = DshWsConnectionState.Connected
                    attempts.set(0)
                    activeSocket = webSocket
                    // $events：全局事件 + waterfall（clientId 由 ready 帧经 onReady 入注册表）
                    webSocket.send(openFrame(EVENTS_STREAM, EVENTS_ENDPOINT))
                    // session/control：jobs/队列/投影整快照基线
                    webSocket.send(openFrame(CONTROL_STREAM, "session/control"))
                    // workspace/follow（#311 Task1）：注册表基线 + archived 集合增量。
                    // 旧版 0.1.2 无此端点 → 流错误帧（仅记日志，不影响其他逻辑流）。
                    webSocket.send(openFrame(WORKSPACE_STREAM, WORKSPACE_ENDPOINT))
                    // 会话 follow：限界集全量（running 或 24h 内活跃；后续新增/激活走 onSessionActive）
                    scope.launch {
                        val targets = runCatching { listFollowTargets() }.onFailure {
                            AppLogger.w(TAG, "session.list 拉取失败——本轮零 follow，重连重试: " + it.message)
                        }.getOrElse { emptyList() }
                        for (target in targets) openFollow(target)
                        // #333：连接前挂起的聚焦 follow 请求补开（请求不丢语义）
                        if (pendingFocusFollows.isNotEmpty()) {
                            val drained = pendingFocusFollows.toList()
                            pendingFocusFollows.clear()
                            drained.forEach { sid -> focusFollowOpener?.invoke(sid) }
                        }
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
                activeSocket = null
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

    private fun followStreamId(sessionId: String): String = FOLLOW_PREFIX + sessionId

    private companion object {
        const val EVENTS_STREAM = "evt"
        const val CONTROL_STREAM = "ctl"
        const val WORKSPACE_STREAM = "wsp"
        const val FOLLOW_PREFIX = "f:"
        /** $events 逻辑流端点（转义 $：字面量）。 */
        const val EVENTS_ENDPOINT = "\$events"
        /** workspace 状态流端点（#311：baseline + 增量）。 */
        const val WORKSPACE_ENDPOINT = "workspace/follow"
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
 * 待决 waterfall 上下文：cancel 帧到达时合成 resolved 帧需要 method + sessionId
 * （mapper 的 question/resolved / approval/resolved 均要求 sessionId 路由——
 * #319 E2E 实证：无 sessionId 的 resolved 帧被判 MALFORMED 静默丢弃，Web 端
 * 作答 settle 后 app 卡不消除的根因）。
 */
data class PendingWaterfall(
    val method: String,
    val sessionId: String,
)

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
    /** 会话需关注信号（added / status running=true / activity）——引擎据此动态补开 follow。 */
    private val onSessionActive: (sessionId: String) -> Unit = {},
) {

    private var chunkRowsSkipped = 0L

    fun onItem(
        streamId: String,
        value: JsonObject,
        pendingWaterfalls: java.util.concurrent.ConcurrentHashMap<String, PendingWaterfall>,
    ) {
        when {
            streamId == "evt" -> onEventsValue(value, pendingWaterfalls)
            streamId == "ctl" -> onControlValue(value)
            streamId == "wsp" -> onWorkspaceValue(value)
            streamId.startsWith("f:") -> onFollowValue(streamId.removePrefix("f:"), value)
            else -> AppLogger.d(TAG, "未知逻辑流 " + streamId + " 值: " + value.toString().take(100))
        }
    }

    // ---- $events：ready / emit / waterfall / cancel ------------------------

    private fun onEventsValue(value: JsonObject, pending: java.util.concurrent.ConcurrentHashMap<String, PendingWaterfall>) {
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
                onSessionActive(sid)
                frame(
                    "host/session-added",
                    buildJsonObject {
                        put("sessionId", sid)
                        (summary["cwd"] as? JsonPrimitive)?.let { put("cwd", it) }
                        // #331：added 摘要（SessionSummary）带 updatedAt（服务器
                        // summaryFor 实证）——透传给 mapper 采真值，否则
                        // SessionCreated.time.updated=epoch0 新行沉列表底部。
                        (summary["updatedAt"] as? JsonPrimitive)?.let { put("updatedAt", it) }
                        // #333：origin 透传（subagent 判别键——fork 子会话
                        // parentSessionId 在但 origin 缺席，是普通会话）。
                        (summary["origin"] as? JsonPrimitive)?.let { put("origin", it) }
                        // #310① A8 缺陷B修复：added 摘要是 SessionSummary——wire 键是
                        // parentSessionId（服务器 listFields 摊 header.parentSession）；
                        // parentSession 是 SessionWireHeader（follow snapshot 头）键。
                        // 旧实现误读该键 → 帧恒丢父址 → SessionCreated 整替换抹掉
                        // 子会话 parentId → 续聊发送分流失效误走 session/prompt 被拒。
                        (summary["parentSessionId"] as? JsonPrimitive)?.let { put("parentSessionId", it) }
                    },
                )
            }
            "api-session/removed" -> {
                val sid = (args.firstOrNull() as? JsonPrimitive)?.content ?: return
                frame("host/session-removed", buildJsonObject { put("sessionId", sid) })
            }
            "api-session/activity" -> {
                // args:[sessionId, updatedAt]——收到即最近活跃；0.1.1 帧族无对应物
                //（activity 数据面由 session.list 刷新承担），仅作动态补开信号。
                val sid = (args.getOrNull(0) as? JsonPrimitive)?.content ?: return
                onSessionActive(sid)
            }
            "api-session/status" -> {
                val sid = (args.getOrNull(0) as? JsonPrimitive)?.content ?: return
                val running = args.getOrNull(1) as? JsonPrimitive ?: return
                // running=true = 会话（可能超出限界窗口）再激活——动态补开 follow
                if (running.content == "true") onSessionActive(sid)
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
        pending: java.util.concurrent.ConcurrentHashMap<String, PendingWaterfall>,
    ) {
        val eventId = (value["eventId"] as? JsonPrimitive)?.content ?: return
        val event = (value["event"] as? JsonPrimitive)?.content ?: return
        val agentId = (value["agentId"] as? JsonPrimitive)?.content ?: ""
        when {
            event == "user-questions/request" -> {
                val request = value["request"] as? JsonObject ?: return
                val questions = (request["questions"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { normalizeQuestionItem(it) }
                pending[eventId] = PendingWaterfall("question/requested", agentId)
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
                pending[eventId] = PendingWaterfall("approval/requested", agentId)
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
        pending: java.util.concurrent.ConcurrentHashMap<String, PendingWaterfall>,
    ) {
        val eventId = (value["eventId"] as? JsonPrimitive)?.content ?: return
        when (val wf = pending.remove(eventId)?.takeIf { it.method == "question/requested" || it.method == "approval/requested" }) {
            null -> AppLogger.d(TAG, "cancel 无对应 pending: " + eventId)
            // resolved 帧必须带 sessionId（mapper MALFORMED 拒收无 sid 帧——Web 端
            // 作答 settle 后 app 卡不消除根因；journal §2.5 finishRemoteEvent 广播）
            else -> if (wf.method == "question/requested") {
                frame(
                    "question/resolved",
                    buildJsonObject { put("sessionId", wf.sessionId); put("id", eventId); put("cancelled", true) },
                    rpcId = eventId,
                )
            } else {
                frame(
                    "approval/resolved",
                    buildJsonObject { put("sessionId", wf.sessionId); put("approvalId", eventId) },
                    rpcId = eventId,
                )
            }
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

    // ---- session/control：baseline + 增量帧（#327 修复） ------------------

    /**
     * 控制流值分型（服务器 dsh-api-session-controller types/control.js
     * SessionControlController）：baseline 一次（{type:'baseline',value:{queues,
     * jobs,projections}}）+ 增量帧 {type:'queue'|'jobs'|'projection',…}。
     *
     * #327 根因：此前只解析 baseline 形状（value.value.{…}），增量帧无外层 value
     * 键 → `?: return` 整包静默丢弃 → 入队/任务/投影变更后 DshQueueStore 等永不
     * 更新 → FAB 队列角标恒 0（2026-09-04 实测症状）。修复=按 type 分型，增量帧
     * 镜像为与 baseline 相同的合成帧（session/queue|jobs|projection），下游
     * mapper/store 单一消费路径不变。
     */
    private fun onControlValue(value: JsonObject) {
        when (value.strOf("type")) {
            "queue" -> {
                val sid = value.strOf("sessionId") ?: return
                val items = value["items"] ?: return
                frame("session/queue", buildJsonObject {
                    put("sessionId", sid)
                    put("items", items)
                })
            }
            "jobs" -> {
                val sid = value.strOf("sessionId") ?: return
                val jobs = value["jobs"] ?: return
                frame("session/jobs", buildJsonObject {
                    put("sessionId", sid)
                    put("jobs", jobs)
                })
            }
            "projection" -> {
                val sid = value.strOf("sessionId") ?: return
                val key = value.strOf("key") ?: return
                val projValue = value["value"] ?: return
                frame("session/projection", buildJsonObject {
                    put("sessionId", sid)
                    put("key", key)
                    put("value", projValue)
                })
            }
            else -> {
                // baseline（含无 type 字段的历史容错——按 value 包装识别）
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
        }
    }

    // ---- workspace/follow：baseline + archived 增量（#311 Task1） ------------

    /**
     * workspace 状态流值分型（服务器 workspace-controller types.d.ts:108-131
     * WorkspaceFollowFrame）：baseline 一次（{type:'baseline', value:{items,
     * archivedSessionIds}}——value 包装与 session/control 同形态）+ 增量帧
     * {type:'upsert'|'remove'|'order'|'archived',…}（平铺）。
     *
     * #311 Task1 消费面：baseline + {type:'archived'}（归档集合替换式——帧即新
     * 集合）镜像为合成帧 workspace/baseline|archived，下游 mapper/store 单一消费
     * 路径。
     *
     * #311 Task3 增量消费：{type:'upsert', workspace}（WorkspaceView 整行——title
     * 重命名/新会话入组等注册表行变更）→ 合成帧 workspace/upsert（对话框
     * title/sessionIds 实时消费面）。
     *
     * #330 增量消费：{type:'remove', workspaceId}（注册表行删——重连 baseline 前即
     * 收敛，#331 对话框陈旧条目随减）→ 合成帧 workspace/remove；{type:'order',
     * workspaceIds}（完整新序——服务器 changed() 序变时 publish 全量数组）→
     * 合成帧 workspace/order。
     */
    private fun onWorkspaceValue(value: JsonObject) {
        when (value.strOf("type")) {
            "baseline" -> {
                val baseline = value["value"] as? JsonObject ?: return
                frame("workspace/baseline", buildJsonObject {
                    put("items", baseline["items"] ?: JsonArray(emptyList()))
                    put("archivedSessionIds", baseline["archivedSessionIds"] ?: JsonArray(emptyList()))
                })
            }
            "archived" -> {
                val ids = value["archivedSessionIds"] ?: return
                frame("workspace/archived", buildJsonObject { put("archivedSessionIds", ids) })
            }
            "upsert" -> {
                val ws = value["workspace"] ?: return
                frame("workspace/upsert", buildJsonObject { put("workspace", ws) })
            }
            "remove" -> {
                val id = value.strOf("workspaceId") ?: return
                frame("workspace/remove", buildJsonObject { put("workspaceId", id) })
            }
            "order" -> {
                val ids = value["workspaceIds"] as? JsonArray ?: return
                frame("workspace/order", buildJsonObject { put("workspaceIds", ids) })
            }
            else -> AppLogger.d(TAG, "workspace 未知增量型: " + value.toString().take(120))
        }
    }

    private fun JsonObject.strOf(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun frame(method: String, payload: JsonObject, rpcId: String = "") {
        onFrame(method, payload, rpcId)
    }
}
