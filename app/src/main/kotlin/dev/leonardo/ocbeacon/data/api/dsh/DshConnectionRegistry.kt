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
 * 从用户粘贴内容提取 DSH 0.1.2 访问令牌（#317 token UX 三形态）：
 * 1. 完整 URL（http://host:port/?token=xxx）；
 * 2. 宿主启动行（"dsh web: http://…?token=xxx"——web.log 回收通道原样粘贴）；
 * 3. 裸 token（base64url，典型 43 字符——宽松下限 20 防误截断）。
 */
internal fun extractDshToken(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    Regex("[?&]token=([A-Za-z0-9_-]+)").find(trimmed)?.groupValues?.get(1)?.let { return it }
    return trimmed.takeIf { it.matches(Regex("[A-Za-z0-9_-]{20,200}")) }
}

/**
 * #512 dsh-password-login 铸票响应判读（纯函数）：`GET /plugins/dsh-password-login/session`
 * 三态——200+Set-Cookie+未 stale → cookie 串；stale=true（宿主格式漂移，插件自报）→
 * null（调用方应回落 legacy token 流）；401/403/404/5xx/无 Set-Cookie → null。
 */
internal fun parseSessionMint(code: Int, setCookie: String?, body: String?): String? {
    if (code != 200 || setCookie.isNullOrBlank()) return null
    if (body != null) {
        runCatching { Json.parseToJsonElement(body) }.getOrNull()
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("stale")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.content == "true" }
            ?.let { return null }
    }
    return setCookie.substringBefore(';').takeIf { it.contains('=') }
}

/**
 * #512 回落链编排（纯函数）：cookie 失效后的恢复动作顺序——免密铸票（未设密码态
 * 回环对端）→ 密码铸票（ServerConfig.password 双语义之一：插件配对密码）→ legacy
 * token 交换（#436 持久化 token 优先，password 字段兜底当 launch token 用——双语义
 * 之二）。同值去重防同 token 白打两枪。
 */
internal fun planRecovery(passwordHint: String?, persistedToken: String?): List<DshRecoveryStep> {
    val steps = mutableListOf<DshRecoveryStep>(DshRecoveryStep.FreeMint)
    passwordHint?.takeIf { it.isNotBlank() }?.let { steps.add(DshRecoveryStep.PasswordMint(it)) }
    val tried = mutableSetOf<String>()
    for (token in listOfNotNull(persistedToken, passwordHint)) {
        if (token.isBlank() || !tried.add(token)) continue
        steps.add(DshRecoveryStep.LegacyToken(token))
    }
    return steps
}

/** #512 回落链单步（[planRecovery] 产物；携带凭据的步骤内联值）。 */
sealed interface DshRecoveryStep {
    /** 未设密码态回环免密铸票（adb reverse 形态下 app 即回环对端）。 */
    data object FreeMint : DshRecoveryStep

    /** 已设密码态：Bearer = ServerConfig.password（插件配对密码语义）。 */
    data class PasswordMint(val password: String) : DshRecoveryStep

    /** legacy `GET /?token=` 交换（launch token 语义——无插件服务器的兼容层）。 */
    data class LegacyToken(val token: String) : DshRecoveryStep
}

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
 * HTTP 面（2026-09-04 MITM 实证修订）：探测走共享 [ApiClient.httpClient]；token
 * 交换走**专用裸 OkHttp**（followRedirects(false)）——Ktor OkHttp engine 的
 * config{followRedirects(false)} 对 303 不透传 Set-Cookie（跟随到裸 index →
 * 401，cookie 丢失），裸 Builder 级 API 才能直读 303 + Set-Cookie。
 */
@Singleton
class DshConnectionRegistry @Inject constructor(
    private val apiClient: ApiClient,
    private val dataStore: DataStore<Preferences>,
    private val secretCipher: SecretCipher,
) : DshMuxAuth {

    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()

    /** authority → 线面版本（内存；进程生命周期内稳定，服务器升级走重连重探）。 */
    private val protocolByAuthority = mutableMapOf<String, DshWireProtocol>()

    /** authority → cookie 串（"name=value" 形态，请求头原样使用）。 */
    private val cookieByAuthority = mutableMapOf<String, String>()

    /** authority → $events ready clientId（0.1.2 应答凭据；连接代际更替时覆写）。 */
    private val clientIdByAuthority = mutableMapOf<String, String>()

    /**
     * authority → launch token（#436：成功交换时记录、SecretCipher 加密持久化）。
     * cookie 失效（服务器重启/签名密钥轮换）时 [recoverAuth] 用它自动重交换——
     * 「断连不能自动重连」的自愈凭据源；token 本体从不外发（仅 /?token= 交换用）。
     */
    private val tokenByAuthority = mutableMapOf<String, String>()

    /**
     * #512：authority → ServerConfig.password（双语义：插件服务器=配对密码；
     * 无插件=launch token）。连接循环每轮刷新（SseConnectionManager DSH 分支），
     * mux 引擎 401 自愈路径 recoverAuth 读此处——比 lambda 钩子少一处接线时序。
     */
    private val passwordHintByAuthority = mutableMapOf<String, String>()

    /** 持久化 cookie 已加载标记（首次访问时从 DataStore 读一次）。 */
    private var persistedLoaded = false

    /**
     * #441 深究批次（2026-09-30）：prompt 受理回执钩子——DshApiClient.promptAsync
     * 成功后经 [DshRpcClient.notifyPromptAdmitted] 触发；SseConnectionManager 接线
     * （authority → 帧源路由），给当前 mux 引擎的静默哨兵播种期望（onRequestSent）。
     * 覆盖「空闲期 WS 假活 → 用户发言」场景：activityFlow 事件驱动门在此形态
     * 永不亮（journal 2026-09-30-467 §5 三层不通电定罪），HTTP 受理回执是唯一
     * 可靠的期望源（服务器受理必发帧——与被否决的 follow-open 无回执形态本质不同）。
     */
    @Volatile
    var onPromptAdmitted: ((authority: String) -> Unit)? = null

    fun notifyPromptAdmitted(authority: String) {
        onPromptAdmitted?.invoke(authority)
    }

    // ---- 查询面（供 DshRpcClient / WS 客户端同步读取） ----------------------

    /** 当前已知线面版本；未探测返回 null（调用方保守按 V011 处理）。 */
    fun protocolOf(authority: String): DshWireProtocol? =
        synchronized(protocolByAuthority) { protocolByAuthority[normalize(authority)] }

    /** 鉴权 Cookie 头值（"dsh-auth-…=v1.…"）；无 cookie 返回 null。 */
    override fun cookieHeader(authority: String): String? =
        synchronized(cookieByAuthority) { cookieByAuthority[normalize(authority)] }

    /** $events clientId（0.1.2 waterfall 应答用）；未连接返回 null。 */
    fun clientId(authority: String): String? =
        synchronized(clientIdByAuthority) { clientIdByAuthority[normalize(authority)] }

    /** WS ready 帧写入 clientId（每代 $events 连接覆写）。 */
    override fun setClientId(authority: String, clientId: String) {
        synchronized(clientIdByAuthority) { clientIdByAuthority[normalize(authority)] = clientId }
    }

    /** RPC/WS 401 打点：清 cookie（过期/失效），下次探测走 TokenNeeded。 */
    override fun markAuthFailure(authority: String) {
        val key = normalize(authority)
        synchronized(cookieByAuthority) { cookieByAuthority.remove(key) }
        persistSoon()
    }

    /**
     * 挂起等待 cookie 就位（TokenNeeded 态连接循环协作用；每 [intervalMs] 轮询）。
     * 取消语义随调用方协程（连接停止即取消等待）。返回非空 cookie。
     */
    override suspend fun awaitCookie(authority: String, intervalMs: Long): String {
        val base = normalize(authority)
        while (true) {
            synchronized(cookieByAuthority) { cookieByAuthority[base] }?.let { return it }
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    /**
     * #512：DSH 服务器密码提示注入（连接循环每轮调用；null 清除——服务器配置
     * 删密码后回落链不再白打密码枪）。
     */
    fun setPasswordHint(authority: String, password: String?) {
        val key = normalize(authority)
        synchronized(passwordHintByAuthority) {
            if (password.isNullOrBlank()) passwordHintByAuthority.remove(key)
            else passwordHintByAuthority[key] = password
        }
    }

    /**
     * #512 凭据自愈回落链（取代 #436 单一 token 交换）：免密铸票 → 密码铸票 →
     * legacy token 交换（持久化 token 优先、password 字段兜底）。
     * 每级一次廉价 HTTP 往返；任一级成功即落盘新 cookie 并返回 true，
     * 全部落空返回 false（调用方走 AUTH_REQUIRED 人工路径）。
     * 线程语义不变：可从任意连接协程调用（mintSession/exchangeToken 各自持 mutex；
     * 防风暴由调用方的 401/TokenNeeded 分支天然限频——每代连接至多一次）。
     */
    override suspend fun recoverAuth(authority: String): Boolean {
        val base = normalize(authority)
        loadPersistedOnce()
        val hint = synchronized(passwordHintByAuthority) { passwordHintByAuthority[base] }
        val persistedToken = synchronized(tokenByAuthority) { tokenByAuthority[base] }
        for (step in planRecovery(hint, persistedToken)) {
            val ok = when (step) {
                is DshRecoveryStep.FreeMint -> mintSession(base, null)
                is DshRecoveryStep.PasswordMint -> mintSession(base, step.password)
                is DshRecoveryStep.LegacyToken -> exchangeToken(base, step.token)
            }
            if (ok) {
                AppLogger.i(TAG, "auth recovered for " + base + " via " + step::class.simpleName)
                return true
            }
        }
        AppLogger.w(TAG, "auth recovery failed (all fallback steps exhausted) for " + base)
        return false
    }

    /**
     * #512：dsh-password-login 插件 `/session` 铸票——未设密码态免密（回环对端），
     * 已设密码态 Bearer=密码。200 + Set-Cookie + 未 stale（宿主格式未漂移）即采纳；
     * 404（无插件）/401（密码错）/403（非回环免密）/stale 一律 false 落下一级。
     */
    private suspend fun mintSession(base: String, bearer: String?): Boolean = mutex.withLock {
        loadPersistedOnce()
        return@withLock try {
            val builder = okhttp3.Request.Builder()
                .url(base + "/plugins/dsh-password-login/session")
                .get()
            bearer?.let { builder.header("Authorization", "Bearer " + it) }
            val call = exchangeOkHttp.newCall(builder.build())
            val response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (cont.isActive) cont.resumeWith(Result.success(response))
                    }
                })
            }
            response.use {
                val cookie = parseSessionMint(it.code, it.header("Set-Cookie"), it.body?.string())
                if (cookie != null) {
                    synchronized(cookieByAuthority) { cookieByAuthority[base] = cookie }
                    persistLocked()
                    true
                } else {
                    AppLogger.i(TAG, "session mint not taken for " + base + ": HTTP " + it.code + (bearer?.let { " (bearer)" } ?: " (free)"))
                    false
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            AppLogger.w(TAG, "session mint failed for " + base + ": " + t.message)
            false
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
     * 交换专用裸 OkHttp（非 Ktor）：303 Set-Cookie 必须直读——Ktor OkHttp engine
     * 的 config{followRedirects(false)} 实测未透传（2026-09-04 MITM 实证：303 被
     * 跟随到 / 裸 index → 401，cookie 丢失）。裸 Builder 与 [DshWsEventClient] 同源
     * 依赖，Builder 级 API 无歧义。独立实例，不动共享 client。
     */
    private val exchangeOkHttp by lazy {
        okhttp3.OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(15))
            .build()
    }

    /**
     * launch token 交换（0.1.2）：GET {base}/?token=… → 303 + Set-Cookie → 持久化。
     *
     * @return 成功=true；401/5xx/无 Set-Cookie=false（token 无效或服务异常，
     *         调用方提示用户重输）。挂起语义：OkHttp enqueue + await。
     */
    suspend fun exchangeToken(authority: String, token: String): Boolean = mutex.withLock {
        val base = normalize(authority)
        loadPersistedOnce()
        return@withLock try {
            val url = base + "/?token=" + java.net.URLEncoder.encode(token, "UTF-8")
            val call = exchangeOkHttp.newCall(okhttp3.Request.Builder().url(url).get().build())
            val response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        if (cont.isActive) cont.resumeWith(Result.success(response))
                    }
                })
            }
            response.use {
                val setCookie = it.header("Set-Cookie")
                if (it.code == 303 && !setCookie.isNullOrBlank()) {
                    val cookie = setCookie.substringBefore(';')
                    synchronized(cookieByAuthority) { cookieByAuthority[base] = cookie }
                    // #436：token 随成功交换一并记录——cookie 失效自动重交换的凭据源
                    synchronized(tokenByAuthority) { tokenByAuthority[base] = token }
                    persistLocked()
                    AppLogger.i(TAG, "token exchange ok for " + base + " (cookie+token persisted)")
                    true
                } else {
                    AppLogger.w(TAG, "token exchange rejected for " + base + ": HTTP " + it.code)
                    false
                }
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
        // #436：token 与 cookie 同批持久化（同款加密）——重交换自愈凭据跨进程存活
        val tokens = synchronized(tokenByAuthority) { tokenByAuthority.toMap() }
        val encryptedTokens = tokens.mapValues { (_, v) -> runCatching { secretCipher.encrypt(v) }.getOrElse { v } }
        dataStore.edit { prefs ->
            prefs[COOKIE_KEY] = json.encodeToString(cookieMapSerializer, encrypted)
            prefs[TOKEN_KEY] = json.encodeToString(cookieMapSerializer, encryptedTokens)
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
            // #436：持久化 token 一并恢复
            dataStore.data.first()[TOKEN_KEY]?.let { tokenRaw ->
                val tokensLoaded = json.decodeFromString(cookieMapSerializer, tokenRaw)
                synchronized(tokenByAuthority) {
                    for (entry in tokensLoaded.entries) {
                        tokenByAuthority[entry.key] =
                            runCatching { secretCipher.decrypt(entry.value) }.getOrElse { entry.value }
                    }
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
        val TOKEN_KEY = stringPreferencesKey("dsh_tokens")

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
