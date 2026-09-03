package dev.leonardo.ocbeacon.data.api.dsh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.data.security.SecretCipher
import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DshConnRegistry"

/**
 * DSH 0.1.2 适配运行时注册表（backlog #317/#318；journal 2026-09-04 §2.1-2.2）。
 *
 * 按 authority（baseUrl）聚合三类每服务器状态：
 * 1. **线面版本**（[DshWireProtocol]）——双形态探测判定，[DshRpcClient]/WS 客户端
 *    据此选翻译形态；未探测前保守 V011（0.1.1 服务器零回归）。
 * 2. **鉴权 cookie**——内存缓存 + DataStore 持久化（SecretCipher 加密，对齐服务器
 *    密码惯例）；cookie 是 bearer 凭据，authority 改变即自然失效（键 = baseUrl）。
 * 3. **$events clientId**（0.1.2 waterfall 应答凭据）——WS ready 帧写入，
 *    replyToQuestion/replyToPermission 的 $events/result 回程读取。
 *
 * 探测/交换的 HTTP 走共享 [ApiClient.httpClient]（OkHttp engine 铁律；该 client
 * 未装 HttpRedirect → 303 原样返回，Set-Cookie 可直接捕获）。
 */
@Singleton
class DshConnectionRegistry @Inject constructor(
    private val apiClient: ApiClient,
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()

    /** authority → 线面版本（内存；进程生命周期内稳定，服务器升级走重连重探）。 */
    private val protocolByAuthority = mutableMapOf<String, DshWireProtocol>()

    /** authority → cookie 串（"name=value" 形态，请求头原样使用）。 */
    private val cookieByAuthority = mutableMapOf<String, String>()

    /** authority → $events ready clientId（0.1.2 应答凭据；连接代际更替时覆写）。 */
    private val clientIdByAuthority = mutableMapOf<String, String>()

    /** 持久化 cookie 已加载标记（首次访问时从 DataStore 读一次）。 */
    private var persistedLoaded = false

    // ---- 查询面（供 DshRpcClient / WS 客户端同步读取） ----------------------

    /** 当前已知线面版本；未探测返回 null（调用方保守按 V011 处理）。 */
    fun protocolOf(authority: String): DshWireProtocol? =
        synchronized(protocolByAuthority) { protocolByAuthority[normalize(authority)] }

    /** 鉴权 Cookie 头值（"dsh-auth-…=v1.…"）；无 cookie 返回 null。 */
    fun cookieHeader(authority: String): String? =
        synchronized(cookieByAuthority) { cookieByAuthority[normalize(authority)] }

    /** $events clientId（0.1.2 waterfall 应答用）；未连接返回 null。 */
    fun clientId(authority: String): String? =
        synchronized(clientIdByAuthority) { clientIdByAuthority[normalize(authority)] }

    /** WS ready 帧写入 clientId（每代 $events 连接覆写）。 */
    fun setClientId(authority: String, clientId: String) {
        synchronized(clientIdByAuthority) { clientIdByAuthority[normalize(authority)] = clientId }
    }

    /** RPC/WS 401 打点：清 cookie（过期/失效），下次探测走 TokenNeeded。 */
    fun markAuthFailure(authority: String) {
        val key = normalize(authority)
        synchronized(cookieByAuthority) { cookieByAuthority.remove(key) }
        persistSoon()
    }

    /**
     * 挂起等待 cookie 就位（TokenNeeded 态连接循环协作用；每 [intervalMs] 轮询）。
     * 取消语义随调用方协程（连接停止即取消等待）。返回非空 cookie。
     */
    suspend fun awaitCookie(authority: String, intervalMs: Long = 2_000L): String {
        val base = normalize(authority)
        while (true) {
            synchronized(cookieByAuthority) { cookieByAuthority[base] }?.let { return it }
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    // ---- 探测与 token 交换 -------------------------------------------------

    /**
     * 双形态探测（幂等；重连每轮调用——服务器升级/cookie 过期自然重判）。
     *
     * 判别表见 [DshProbeOutcome]；探测请求本身带已知 cookie（续期场景 200 直判）。
     * 传输异常 → [DshProbeOutcome.Unreachable]（调用方按既有断连退避处理）。
     */
    suspend fun ensureProbed(authority: String): DshProbeOutcome = mutex.withLock {
        val base = normalize(authority)
        loadPersistedOnce()
        val cookie = synchronized(cookieByAuthority) { cookieByAuthority[base] }
        val slash = rawProbe(base, SLASH_METHOD, SLASH_PROBE_PAYLOAD, cookie)
        val dot = rawProbe(base, DOT_METHOD, DOT_PROBE_PAYLOAD, cookie)
        val outcome = decide(base, slash, dot)
        when (outcome) {
            is DshProbeOutcome.Online -> {
                synchronized(protocolByAuthority) { protocolByAuthority[base] = outcome.protocol }
                if (!outcome.authenticated) clearCookieLocked(base)
            }
            is DshProbeOutcome.TokenNeeded -> {
                synchronized(protocolByAuthority) { protocolByAuthority[base] = DshWireProtocol.V012 }
                clearCookieLocked(base)
            }
            is DshProbeOutcome.Unreachable -> Unit
        }
        AppLogger.i(TAG, "probe " + base + ": slash=" + slash + " dot=" + dot + " → " + outcome)
        outcome
    }

    /**
     * launch token 交换（0.1.2）：GET {base}/?token=… → 303 + Set-Cookie → 持久化。
     *
     * @return 成功=true；401/5xx/无 Set-Cookie=false（token 无效或服务异常，
     *         调用方提示用户重输）。
     */
    suspend fun exchangeToken(authority: String, token: String): Boolean = mutex.withLock {
        val base = normalize(authority)
        loadPersistedOnce()
        return@withLock try {
            val response = apiClient.httpClient.get(base + "/") {
                url {
                    parameters.append("token", token)
                }
            }
            val setCookie = response.headers["Set-Cookie"]
            if (response.status.value == 303 && !setCookie.isNullOrBlank()) {
                val cookie = setCookie.substringBefore(';')
                synchronized(cookieByAuthority) { cookieByAuthority[base] = cookie }
                persistLocked()
                AppLogger.i(TAG, "token exchange ok for " + base + " (cookie persisted)")
                true
            } else {
                AppLogger.w(TAG, "token exchange rejected for " + base + ": HTTP " + response.status.value)
                false
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppLogger.w(TAG, "token exchange failed for " + base + ": " + t.message)
            false
        }
    }

    // ---- 内部 ---------------------------------------------------------------

    private fun clearCookieLocked(base: String) {
        synchronized(cookieByAuthority) { cookieByAuthority.remove(base) }
        persistSoon()
    }

    /** DataStore 异步落盘失败不应阻断主流程——IO scope 去抖持久化（不持 mutex）。 */
    private var persistJob: kotlinx.coroutines.Job? = null
    private val persistScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = persistScope.launch {
            runCatching { persistLocked() }
        }
    }

    private val cookieMapSerializer = MapSerializer(String.serializer(), String.serializer())

    private suspend fun persistLocked() {
        val snapshot = synchronized(cookieByAuthority) { cookieByAuthority.toMap() }
        val encrypted = snapshot.mapValues { (_, v) -> runCatching { secretCipher.encrypt(v) }.getOrElse { v } }
        dataStore.edit { prefs ->
            prefs[COOKIE_KEY] = json.encodeToString(cookieMapSerializer, encrypted)
        }
    }

    private suspend fun loadPersistedOnce() {
        if (persistedLoaded) return
        persistedLoaded = true
        runCatching {
            val raw = dataStore.data.first()[COOKIE_KEY] ?: return
            val loaded = json.decodeFromString(cookieMapSerializer, raw)
            synchronized(cookieByAuthority) {
                for (entry in loaded.entries) {
                    cookieByAuthority[entry.key] =
                        runCatching { secretCipher.decrypt(entry.value) }.getOrElse { entry.value }
                }
            }
        }.onFailure { AppLogger.w(TAG, "cookie persistence load failed: " + it.message) }
    }

    /** 单发原始探测（不经 DshWireAdapter——探测本身即形态样本）。返回 HTTP 状态码；传输异常 null。 */
    private suspend fun rawProbe(
        base: String,
        method: String,
        payload: JsonObject,
        cookie: String?,
    ): Int? = try {
        val response = apiClient.httpClient.post(base + "/api/" + method) {
            contentType(ContentType.Application.Json)
            cookie?.let { header("Cookie", it) }
            setBody(
                buildJsonObject {
                    put("type", JsonPrimitive("client-request"))
                    put("rpcId", JsonPrimitive("probe-" + java.util.UUID.randomUUID().toString().take(8)))
                    put("method", JsonPrimitive(method))
                    put("payload", payload)
                }.toString(),
            )
        }
        response.status.value
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (t: Throwable) {
        null
    }

    private fun decide(base: String, slash: Int?, dot: Int?): DshProbeOutcome = when {
        slash == null || dot == null -> DshProbeOutcome.Unreachable("transport failure (slash=$slash dot=$dot)")
        slash == 401 && dot == 401 -> DshProbeOutcome.TokenNeeded
        slash != null && slash in 200..299 && dot == 404 ->
            DshProbeOutcome.Online(DshWireProtocol.V012, authenticated = true)
        dot in 200..299 && slash == 404 ->
            DshProbeOutcome.Online(DshWireProtocol.V011, authenticated = true)
        dot in 200..299 && slash != null && slash in 200..299 -> {
            // 两形态同时在线：异常部署形态——保守 V011（0.1.1 全功能路径），告警可见
            AppLogger.w(TAG, "anomaly: both endpoint forms online at " + base + " — falling back to V011")
            DshProbeOutcome.Online(DshWireProtocol.V011, authenticated = true)
        }
        else -> DshProbeOutcome.Unreachable("unexpected probe statuses (slash=$slash dot=$dot)")
    }

    private fun normalize(authority: String): String = authority.trim().trimEnd('/')

    private companion object {
        val COOKIE_KEY = stringPreferencesKey("dsh_cookies")

        /** 0.1.2 形态探测样本：session/list + args 包装（journal §2.1）。 */
        const val SLASH_METHOD = "session/list"
        val SLASH_PROBE_PAYLOAD: JsonObject = buildJsonObject {
            put("args", buildJsonObject { put("_request", buildJsonObject {}) })
        }

        /** 0.1.1 形态探测样本：session.list + 裸 payload。 */
        const val DOT_METHOD = "session.list"
        val DOT_PROBE_PAYLOAD: JsonObject = buildJsonObject {}
    }
}
