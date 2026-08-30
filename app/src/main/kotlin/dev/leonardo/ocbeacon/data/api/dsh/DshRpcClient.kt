package dev.leonardo.ocbeacon.data.api.dsh

import dev.leonardo.ocbeacon.data.api.ApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
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
 */
@Singleton
class DshRpcClient @Inject constructor(
    private val apiClient: ApiClient,
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
        val envelope = DshEnvelope.ClientRequest(DshEnvelope.newRpcId(), method, payload)
        val wire = exchange(conn, method, envelope)
        val ok = wire.getOrElse { return Result.failure(it) } as? DshRpcResult.Ok
            ?: return Result.failure(DshApiError(null, "malformed server-response envelope", null, HTTP_OK))
        val value = ok.value as? JsonObject
            ?: return Result.failure(
                DshApiError(null, "ok response with non-object value: " + ok.value.toString().take(120), null, HTTP_OK),
            )
        return runCatching { transform(value) }
    }

    /**
     * /api/respond 回程（§1.6-2：WS 纯下行，上行全部走 HTTP）。
     *
     * [rpcId] 必须复用 server-request（approval/question requested）帧的稳定 id；
     * [value] 为应答载荷（如 {"outcome":"allowed-once"}）。回执（RpcReceipt）的
     * ok 值本期不解析——错误分支（如 not-pending）照常映射 [DshApiError]。
     */
    suspend fun respond(conn: ServerConnection, rpcId: String, value: JsonObject): Result<Unit> {
        val envelope = DshEnvelope.ClientResponse(rpcId, DshRpcResult.Ok(value))
        return exchange(conn, "respond", envelope).map { Unit }
    }

    /** 非信封入口共用传输（#276：session.export zip 流直下）——同一 OkHttp engine 配置。 */
    internal val http: io.ktor.client.HttpClient get() = apiClient.httpClient

    // ---- 内部：传输 + 信封 + 业务错误分支统一收口 ------------------------

    private suspend fun exchange(conn: ServerConnection, method: String, envelope: DshEnvelope): Result<DshRpcResult> {
        return try {
            val response = apiClient.httpClient.post(url(conn, method)) {
                contentType(ContentType.Application.Json)
                setBody(DshEnvelope.encode(envelope))
            }
            val status = response.status.value
            val text = response.bodyAsText()
            if (status != HttpStatusCode.OK.value) {
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
    }
}
