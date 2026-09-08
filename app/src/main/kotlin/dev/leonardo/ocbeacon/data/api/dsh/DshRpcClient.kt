package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH RPC HTTP 客户端（backlog #274 组件 ③；设计文档 §1.6 传输契约 + §2.6 方法面）。
 *
 * 传输形态：
 * - POST {baseUrl}/api/{method}，Content-Type: application/json；
 * - **不设 Origin 头**（§1.6-4：非浏览器客户端无 Origin 要求，存在才校验一致性）；
 * - Host 由 OkHttp 按 URL 自动生成（勿覆写——栅栏只看 Host 头，§1.6 P-1）；
 * - 无 Basic auth：DSH 无鉴权，[ServerConnection.authHeader] 被有意忽略。
 * - method 同时出现在 URL 路径段与 body.method（P-4 信封铁律：不等 → bad-request）。
 *
 * 错误面（§5）：业务错误恒 HTTP 200 + result.error 闭集码 → [DshApiError.code]；
 * 非 200（415/400/404/403/426/500）只表搬运层 → [DshApiError.httpStatus]；
 * 传输层失败（IOException/超时）→ code=null 且 httpStatus=null（Network 分类）。
 *
 * ⑦ 接入层按域包装：call(conn, "session.list", payload) { value -> … }。
 *
 * #317/#318（2026-09-04）：0.1.2 线面适配——本类是翻译唯一收口：方法名/payload 经
 * [DshWireAdapter] 按注册表线面版本翻译（未探测保守 V011）；0.1.2 附 Cookie 头
 * （[DshConnectionRegistry]）；HTTP 401 → [DshAuthRequiredException]（注册表清
 * cookie，连接层进 TokenNeeded 而非普通断连）。调用方继续传 **0.1.1 规范方法名**。
 */
@Singleton
class DshRpcClient @Inject constructor(
    private val apiClient: ApiClient,
    private val registry: DshConnectionRegistry,
) {

    /**
     * 发起一次 RPC 调用并变换 ok 值。
     *
     * [transform] 只在 ok=true 且 value 为对象时执行（52 方法面 value 恒对象，P-4）；
     * transform 自身异常原样透传（不冒充传输错误），由接入层 runCatching 语义接管。
     */
    suspend fun <T> call(
        conn: ServerConnection,
        method: String,
        payload: JsonObject,
        transform: (JsonObject) -> T,
    ): Result<T> {
        val (wireMethod, envelope) = prepare(conn, method, payload)
        val wire = exchange(conn, wireMethod, envelope)
        val ok = wire.getOrElse { return Result.failure(it) } as? DshRpcResult.Ok
            ?: return Result.failure(DshApiError(null, "malformed server-response envelope", null, HTTP_OK))
        val value = ok.value as? JsonObject
            ?: return Result.failure(
                DshApiError(null, "ok response with non-object value: " + ok.value.toString().take(120), null, HTTP_OK),
            )
        return runCatching { transform(value) }
    }

    /**
     * void 结果 RPC（#324：agentPresets/copy|deletePreset、credentials/set|unset）。
     *
     * 服务器 typert z.void() 结果经 JSON.stringify 丢 undefined 键 → 线面
     * {"ok":true}（value 缺席）或 value:null——[call]/[callJson] 的非空前置均
     * 会误判失败。本方法把 ok=true 的任意 value（含 null/JsonNull）判为成功。
     */
    /**
     * #358：value 可缺席 RPC（commands/execute 的 CommandExecution|undefined）——
     * ok=true 时 value 原样返回（含 null=受理-异步）；[call]/[callJson] 的
     * value 非空前置不适用。网关/传输错误照常 Result.failure。
     */
    internal suspend fun callOptional(
        conn: ServerConnection,
        method: String,
        payload: JsonObject,
    ): Result<JsonElement?> {
        val (wireMethod, envelope) = prepare(conn, method, payload)
        val wire = exchange(conn, wireMethod, envelope)
        val ok = wire.getOrElse { return Result.failure(it) } as? DshRpcResult.Ok
            ?: return Result.failure(DshApiError(null, "malformed server-response envelope", null, HTTP_OK))
        return Result.success(ok.value)
    }

    internal suspend fun callVoid(
        conn: ServerConnection,
        method: String,
        payload: JsonObject,
    ): Result<Unit> {
        val (wireMethod, envelope) = prepare(conn, method, payload)
        val wire = exchange(conn, wireMethod, envelope)
        val ok = wire.getOrElse { return Result.failure(it) } as? DshRpcResult.Ok
            ?: return Result.failure(DshApiError(null, "malformed server-response envelope", null, HTTP_OK))
        return Result.success(Unit)
    }

    /**
     * value 非对象 RPC（commands/list 等数组值方法，2026-08-31 活体定音）——
     * [call] 的「value 必为对象」前置不适用。transform 语义同 [call]
     *（ok=true 时执行；异常经 runCatching 语义接管，不冒充传输错误）。
     */
    internal suspend fun <T> callJson(
        conn: ServerConnection,
        method: String,
        payload: JsonObject,
        transform: (JsonElement) -> T,
    ): Result<T> {
        val (wireMethod, envelope) = prepare(conn, method, payload)
        val wire = exchange(conn, wireMethod, envelope)
        val ok = wire.getOrElse { return Result.failure(it) } as? DshRpcResult.Ok
            ?: return Result.failure(DshApiError(null, "malformed server-response envelope", null, HTTP_OK))
        val value = ok.value
            ?: return Result.failure(DshApiError(null, "ok response with null value", null, HTTP_OK))
        return runCatching { transform(value) }
    }

    /**
     * /api/respond 回程（§1.6-2：WS 纯下行，上行全部走 HTTP）。
     *
     * [rpcId] 必须复用 server-request（approval/question requested）帧的稳定 id；
     * [value] 为应答载荷。#308（2026-09-03 源码定音）：载荷 schema——审批
     * {sessionId,approvalId,outcome} / 提问 {sessionId,answer:{answers[]}}；
     * **回执是 RpcReceipt {accepted,reason} 而非业务信封**（无 rpcId/result，
     * 走 [DshEnvelope.decode] 必返 null → 旧实现 respond 恒失败、HTTP 200 掩盖）。
     * accepted=false 按 reason 构造 [DshApiError]（bad-response/not-pending 为
     * 本端点专用词，不在 39 值闭集，[DshRpcErrorCode] 保留原串容错）。
     */
    suspend fun respond(conn: ServerConnection, rpcId: String, value: JsonObject): Result<Unit> =
        postRespond(conn, DshEnvelope.ClientResponse(rpcId, DshRpcResult.Ok(value)))

    /**
     * /api/respond 取消回程（#308）：提问取消的唯一被接受形态 = Err 信封
     * （result.ok=false + error.code="cancelled" → 服务端 claimQuestion("cancelled")
     * → accepted:true）。审批无取消——Err 回执恒 bad-response，拒绝审批应走
     * [respond] 的 outcome="rejected"。
     */
    suspend fun respondError(conn: ServerConnection, rpcId: String, code: DshRpcErrorCode, message: String): Result<Unit> =
        postRespond(conn, DshEnvelope.ClientResponse(rpcId, DshRpcResult.Err(code, message, null)))

    /**
     * 0.1.2 waterfall 应答（#318；journal §2.5）：POST /api/$events/result，
     * payload {args:{clientId, eventId, outcome}}——clientId 来自本服务器
     * $events ready 帧（[DshConnectionRegistry.clientId]，WS 引擎写入），
     * eventId 来自 waterfall 帧。outcome 三形：result{value?}/next/rejected{error}。
     *
     * [outcome] 原样嵌入（调用方按 question/permission 语义构造）。
     * 回程 {ok:true}；业务错误走既有 DshApiError 面。
     */
    suspend fun eventsResult(
        conn: ServerConnection,
        eventId: String,
        outcome: JsonObject,
    ): Result<Unit> {
        val clientId = registry.clientId(conn.baseUrl)
            ?: return Result.failure(
                DshApiError(null, "no active \$events client for " + conn.baseUrl, null, null),
            )
        val payload = buildJsonObject {
            put("args", buildJsonObject {
                put("clientId", JsonPrimitive(clientId))
                put("eventId", JsonPrimitive(eventId))
                put("outcome", outcome)
            })
        }
        val envelope = DshEnvelope.ClientRequest(DshEnvelope.newRpcId(), EVENTS_RESULT_METHOD, payload)
        return exchange(conn, EVENTS_RESULT_METHOD, envelope).map { Unit }
    }

    /** /api/respond 传输 + RpcReceipt 解析（#308：与 [exchange] 的信封解码分道）。 */
    private suspend fun postRespond(conn: ServerConnection, envelope: DshEnvelope): Result<Unit> {
        return try {
            val response = apiClient.httpClient.post(url(conn, "respond")) {
                contentType(ContentType.Application.Json)
                setBody(DshEnvelope.encode(envelope))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            if (status != HttpStatusCode.OK.value) {
                Result.failure(DshApiError(null, "HTTP " + status + ": " + text.take(200), null, status))
            } else {
                val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                val accepted = (root?.get("accepted") as? JsonPrimitive)?.booleanOrNull
                if (accepted == true) {
                    Result.success(Unit)
                } else {
                    val reason = (root?.get("reason") as? JsonPrimitive)?.contentOrNull
                        ?: "malformed rpc receipt: " + text.take(120)
                    Result.failure(DshApiError(DshRpcErrorCode(reason), "respond not accepted: " + reason, null, status))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(DshApiError(null, t.message ?: t::class.java.simpleName, null, null, cause = t))
        }
    }

    /** 非信封入口共用传输（#276：session.export zip 流直下）——同一 OkHttp engine 配置。 */
    internal val http: io.ktor.client.HttpClient get() = apiClient.httpClient

    // ---- 内部：传输 + 信封 + 业务错误分支统一收口 ------------------------

    /**
     * 线面翻译（#317/#318 唯一收口）：0.1.1 规范方法名 + 裸 payload →
     * 目标线面（wire 方法名 + args 包装 + 字段改名）+ 信封。
     * 信封 method 与 URL 方法段同步用 wire 名（P-4 铁律：不等 → bad-request）。
     */
    private fun prepare(conn: ServerConnection, method: String, payload: JsonObject): Pair<String, DshEnvelope.ClientRequest> {
        val protocol = registry.protocolOf(conn.baseUrl) ?: DshWireProtocol.V011
        val wireMethod = DshWireAdapter.method(method, protocol)
        val wirePayload = DshWireAdapter.payload(method, protocol, payload)
        return wireMethod to DshEnvelope.ClientRequest(DshEnvelope.newRpcId(), wireMethod, wirePayload)
    }

    /** 0.1.2 鉴权 Cookie 头（非空时由请求构造处挂载；也供 export 等非信封入口复用）。 */
    internal fun cookieFor(conn: ServerConnection): String? = registry.cookieHeader(conn.baseUrl)

    private suspend fun exchange(conn: ServerConnection, method: String, envelope: DshEnvelope): Result<DshRpcResult> {
        return try {
            val cookie = registry.cookieHeader(conn.baseUrl)
            val response = apiClient.httpClient.post(url(conn, method)) {
                contentType(ContentType.Application.Json)
                cookie?.let { header("Cookie", it) }
                setBody(DshEnvelope.encode(envelope))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            if (status == HTTP_UNAUTHORIZED) {
                // #317：0.1.2 cookie 缺失/过期/authority 不匹配——清凭据并进 TokenNeeded 模态
                registry.markAuthFailure(conn.baseUrl)
                Result.failure(DshAuthRequiredException(conn.baseUrl, "HTTP 401: " + text.take(120)))
            } else if (status != HttpStatusCode.OK.value) {
                // 搬运层错误：HTTP 状态表非信封内容（§5）
                Result.failure(
                    DshApiError(null, "HTTP " + status + ": " + text.take(200), null, status),
                )
            } else {
                when (val decoded = DshEnvelope.decode(text)) {
                    is DshEnvelope.ServerResponse -> when (val result = decoded.result) {
                        is DshRpcResult.Ok -> Result.success(result)
                        is DshRpcResult.Err -> Result.failure(
                            DshApiError(result.code, result.message, result.details, status),
                        )
                    }
                    else -> Result.failure(
                        DshApiError(null, "malformed server-response envelope: " + text.take(120), null, status),
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 传输层失败：无信封无状态码 → Network 分类
            Result.failure(
                DshApiError(null, t.message ?: t::class.java.simpleName, null, null, cause = t),
            )
        }
    }

    private fun url(conn: ServerConnection, method: String): String =
        conn.baseUrl.trimEnd('/') + "/api/" + method

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        /** 0.1.2 waterfall 应答端点（journal §2.5）。 */
        const val EVENTS_RESULT_METHOD = "\$events/result"
    }
}