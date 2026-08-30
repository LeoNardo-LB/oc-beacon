package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "DshWsEventClient"

/** 客户端活性自证 ping 间隔（§1.6-3：服务端零心跳、无空闲踢线 → OkHttp pingInterval 必配）。 */
private const val PING_INTERVAL_SECONDS = 25L

/**
 * DSH 双 WS 下行客户端（backlog #274 组件 ④；设计文档 §1.6 传输契约 + §2.3 事件层）。
 *
 * 契约要点：
 * - 双流：/api/events.mux + /api/events.host（§1.6-1：只能走 WS，GET 拦 426）；
 * - **只收不发**（§1.6-2：客户端发任何数据帧 = close(1008, "downlink only")——本类
 *   从不调用 WebSocket.send，上行为 DshRpcClient 的 HTTP 面）；协议层 PING/PONG 不受限；
 * - pingInterval(25s)（§1.6-3）：服务端零心跳，doze 冻结 socket 靠重连兜底；
 * - 断线双流**独立重连**：指数退避 500ms×2ⁿ 封顶 10s 带抖动（[DshBackoff]，§1.6-5）；
 * - 连接状态聚合取最差（[aggregateDshWsState]）：任一流断 → Disconnected；
 * - 每帧文本 → [DshEnvelope] 解码 ServerRequest → [onFrame][start] 回调
 *   （method, payload）；畸形帧丢弃 + AppLogger.w（49 型开放联合容错）；
 * - 生命周期 start/stop：重复 start 幂等（先 stop 旧代）。
 *
 * 回调线程注意：[start] 的 onFrame 在 OkHttp 读线程执行，接入层（⑤ DshEventMapper →
 * processEvent）自行调度到目标调度器。
 */
enum class DshWsConnectionState {
    Connecting,
    Connected,
    Disconnected,
}

/** WebSocket 打开缝隙（测试注入假 opener 用；生产 = OkHttpClient.newWebSocket）。 */
fun interface DshWebSocketOpener {
    fun open(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket
}

/** 状态聚合：取最差（Disconnected > Connecting > Connected）；空集保守判 Disconnected。 */
internal fun aggregateDshWsState(states: List<DshWsConnectionState>): DshWsConnectionState = when {
    states.isEmpty() -> DshWsConnectionState.Disconnected
    states.contains(DshWsConnectionState.Disconnected) -> DshWsConnectionState.Disconnected
    states.contains(DshWsConnectionState.Connecting) -> DshWsConnectionState.Connecting
    else -> DshWsConnectionState.Connected
}

/**
 * 帧处理器：单帧文本 → 解码 → 回调。
 *
 * @return true=好帧已转发；false=畸形/违约帧已丢弃（AppLogger.w，不抛不崩）。
 */
internal fun handleDshWsFrame(text: String, onFrame: (method: String, payload: JsonObject) -> Unit): Boolean {
    val envelope = DshEnvelope.decode(text) as? DshEnvelope.ServerRequest
    if (envelope == null) {
        AppLogger.w(TAG, "丢弃无法解码的 WS 帧: " + text.take(120))
        return false
    }
    onFrame(envelope.method, envelope.payload)
    return true
}

/**
 * 重连退避（§1.6-5 官方参考实现参数）：base 500ms ×2ⁿ 封顶 10s，抖动
 * cap/2 + rand×cap/2（结果 ∈ [raw/2, raw)）。随机源构造注入，测试可钉死序列。
 */
class DshBackoff(
    private val random: () -> Double = { Random.nextDouble() },
    private val baseDelayMs: Long = 500L,
    private val maxDelayMs: Long = 10_000L,
) {
    /** 第 [attempt] 次连续失败后的重连延迟（attempt 从 0 起；成功会归零计数）。 */
    fun delayMs(attempt: Int): Long {
        require(attempt >= 0) { "attempt must be >= 0" }
        var raw = baseDelayMs
        repeat(attempt.coerceAtMost(BLANK_ATTEMPT_LIMIT)) {
            raw = minOf(raw * 2, maxDelayMs)
        }
        val half = raw / 2
        return half + (random() * half).toLong()
    }

    private companion object {
        // 防御性上限：封顶后 raw 恒为 maxDelayMs，更多次迭代无意义
        const val BLANK_ATTEMPT_LIMIT = 64
    }
}

/** 双流端点（§1.5 帧词汇表：mux = 会话事件流，host = 会话生命周期流）。 */
private enum class DshWsStreamKind(val path: String, val label: String) {
    MUX("/api/events.mux", "mux"),
    HOST("/api/events.host", "host"),
}

/**
 * 引擎实现（[DshWsEventClient] 的可测内核：scope/opener/backoff 全部构造注入，
 * 生产外壳继承注入 IO scope）。
 */
open class DshWsEventEngine(
    private val scope: CoroutineScope,
    private val backoff: DshBackoff = DshBackoff(),
    private val opener: DshWebSocketOpener = DshWebSocketOpener { client, request, listener ->
        client.newWebSocket(request, listener)
    },
    internal val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build(),
) {

    private val muxState = MutableStateFlow(DshWsConnectionState.Disconnected)
    private val hostState = MutableStateFlow(DshWsConnectionState.Disconnected)

    /** 聚合连接状态（取最差），初值 Disconnected。 */
    val connectionState: StateFlow<DshWsConnectionState> =
        combine(muxState, hostState) { mux, host -> aggregateDshWsState(listOf(mux, host)) }
            .stateIn(scope, SharingStarted.Eagerly, DshWsConnectionState.Disconnected)

    private var generation: Job? = null
    private var generationId = 0

    /**
     * 启动双流（幂等：先 stop 旧代）。[onFrame] 在 OkHttp 读线程回调。
     */
    fun start(baseUrl: String, onFrame: (method: String, payload: JsonObject) -> Unit) {
        synchronized(this) {
            stopLocked()
            generationId++
            val id = generationId
            generation = scope.launch {
                DshWsStreamKind.entries.map { stream ->
                    val state = if (stream == DshWsStreamKind.MUX) muxState else hostState
                    launch { streamLoop(id, stream, wsUrl(baseUrl, stream.path), state, onFrame) }
                }
            }
        }
    }

    /** 停止当前代（幂等）：取消双流与挂起的重连，状态回落 Disconnected。 */
    fun stop() {
        synchronized(this) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        generation?.cancel()
        generation = null
        muxState.value = DshWsConnectionState.Disconnected
        hostState.value = DshWsConnectionState.Disconnected
    }

    /** 单流生命周期循环：连 → 挂起等断 → 退避 → 重连（双流各自独立）。 */
    private suspend fun streamLoop(
        generation: Int,
        stream: DshWsStreamKind,
        url: String,
        state: MutableStateFlow<DshWsConnectionState>,
        onFrame: (method: String, payload: JsonObject) -> Unit,
    ) {
        val attempts = AtomicInteger(0)
        while (true) {
            state.value = DshWsConnectionState.Connecting
            val closed = CompletableDeferred<Throwable?>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    state.value = DshWsConnectionState.Connected
                    attempts.set(0)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleDshWsFrame(text, onFrame)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!closed.isCompleted) closed.complete(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!closed.isCompleted) closed.complete(null)
                }
            }
            val socket = opener.open(client, Request.Builder().url(url).build(), listener)
            try {
                closed.await()
            } finally {
                runCatching { socket.cancel() }
            }
            state.value = DshWsConnectionState.Disconnected
            val attempt = attempts.getAndIncrement()
            AppLogger.w(
                TAG,
                "DSH WS 流 " + stream.label + " 断开（代 " + generation + "，连续失败 " + (attempt + 1) + "），退避后重连",
            )
            delay(backoff.delayMs(attempt))
        }
    }

    /** http(s) baseUrl → ws(s) URL；已是 ws(s) 形态则原样透传。 */
    private fun wsUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val scheme = when {
            base.startsWith("https://", ignoreCase = true) -> "wss://"
            base.startsWith("http://", ignoreCase = true) -> "ws://"
            else -> ""
        }
        val authority = if (scheme.isEmpty()) base else base.substringAfter("://")
        return scheme + authority + path
    }
}

/**
 * DSH 双 WS 下行客户端（Hilt 单例外壳；生产 scope = IO + SupervisorJob）。
 *
 * 用法：连接生命周期协调层 start(serverConnection.baseUrl) { method, payload -> … }；
 * 断开/切换服务器时 stop()。帧 → SseEvent 映射（⑤）由接入层完成。
 */
@Singleton
class DshWsEventClient @Inject constructor() : DshWsEventEngine(
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)
