package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DshApiError 七类语义分类表驱动测试（backlog #274 组件 ②；设计文档 §2.4/§5）。
 *
 * 既有 [dev.leonardo.ocbeacon.data.api.ApiErrorTranslator] 是 HTTP 形状分类学
 * （401/403/404/429…），与 DSH「错误恒 HTTP 200 + 39 码闭集」语义不匹配——本层
 * 只做错误语义分类（DshErrorCategory 七类），UI 文案由接入层后续走 strings.xml。
 */
class DshApiErrorTest {

    /** 期望表：39 码 → 七类（独立于实现的字面清单，钉住语义映射）。 */
    private val expected: Map<String, DshErrorCategory> = mapOf(
        // NotFound：资源不存在族
        "session-not-found" to DshErrorCategory.NotFound,
        "workspace-not-found" to DshErrorCategory.NotFound,
        "agent-preset-not-found" to DshErrorCategory.NotFound,
        "queue-item-not-found" to DshErrorCategory.NotFound,
        "subagent-not-found" to DshErrorCategory.NotFound,
        // Busy：资源被占用/暂不可继续族
        "agent-busy" to DshErrorCategory.Busy,
        "agent-preset-locked" to DshErrorCategory.Busy,
        "not-resumable" to DshErrorCategory.Busy,
        "steer-unavailable" to DshErrorCategory.Busy,
        // Conflict：状态/命名冲突族
        "session-conflict" to DshErrorCategory.Conflict,
        "workspace-name-conflict" to DshErrorCategory.Conflict,
        "agent-preset-conflict" to DshErrorCategory.Conflict,
        "settings-conflict" to DshErrorCategory.Conflict,
        "directory-exists" to DshErrorCategory.Conflict,
        // Auth：DSH 无鉴权面，仅 Host 栅栏 unauthorized + 上游凭据被拒两处
        "unauthorized" to DshErrorCategory.Auth,
        "credential-rejected" to DshErrorCategory.Auth,
        // Server：服务端执行失败族
        "model-unavailable" to DshErrorCategory.Server,
        "workspace-attach-failed" to DshErrorCategory.Server,
        "directory-unreadable" to DshErrorCategory.Server,
        "directory-create-failed" to DshErrorCategory.Server,
        "picker-unavailable" to DshErrorCategory.Server,
        "command-error" to DshErrorCategory.Server,
        "model-discovery-failed" to DshErrorCategory.Server,
        "catalog-diagnostic" to DshErrorCategory.Server,
        "delivery-unavailable" to DshErrorCategory.Server,
        "internal" to DshErrorCategory.Server,
        // Unknown：客户端输入违约/无领域语义族
        "bad-request" to DshErrorCategory.Unknown,
        "cancelled" to DshErrorCategory.Unknown,
        "invalid-time-zone" to DshErrorCategory.Unknown,
        "workspace-invalid-path" to DshErrorCategory.Unknown,
        "workspace-move-invalid" to DshErrorCategory.Unknown,
        "agent-preset-read-only" to DshErrorCategory.Unknown,
        "agent-preset-invalid" to DshErrorCategory.Unknown,
        "attachment-error" to DshErrorCategory.Unknown,
        "unknown-command" to DshErrorCategory.Unknown,
        "settings-rejected" to DshErrorCategory.Unknown,
        "title-invalid" to DshErrorCategory.Unknown,
        "fork-unavailable" to DshErrorCategory.Unknown,
        "subagent-parent-unavailable" to DshErrorCategory.Unknown,
    )

    @Test
    fun `all 39 closed set codes classify per table`() {
        assertEquals(39, expected.size)
        assertEquals(expected.keys, DshRpcErrorCode.ALL.map { it.wire }.toSet())
        DshRpcErrorCode.ALL.forEach { code ->
            val error = DshApiError(code = code, message = "m", details = null, httpStatus = 200)
            assertEquals("category mismatch for " + code.wire, expected[code.wire], error.category)
        }
    }

    @Test
    fun `unknown code falls back to Unknown category`() {
        val error = DshApiError(
            code = DshRpcErrorCode.fromWire("not-pending"),
            message = "no pending request",
            details = null,
            httpStatus = 200,
        )
        assertEquals(DshErrorCategory.Unknown, error.category)
        assertEquals("not-pending", error.code?.wire)
    }

    @Test
    fun `code takes precedence over httpStatus`() {
        // 业务错误恒 HTTP 200：有 code 时按闭集表分类
        val error = DshApiError(DshRpcErrorCode.Internal, "boom", null, httpStatus = 200)
        assertEquals(DshErrorCategory.Server, error.category)
    }

    @Test
    fun `http status only errors map by transport semantics`() {
        // §5：HTTP 状态只表搬运层——404 未知方法 / 403 Host 栅栏 / 500 崩溃 /
        // 415 非 JSON / 400 非 JSON body / 426 需 WS
        assertEquals(DshErrorCategory.NotFound, DshApiError(null, "m", null, 404).category)
        assertEquals(DshErrorCategory.Auth, DshApiError(null, "m", null, 403).category)
        assertEquals(DshErrorCategory.Server, DshApiError(null, "m", null, 500).category)
        assertEquals(DshErrorCategory.Server, DshApiError(null, "m", null, 502).category)
        assertEquals(DshErrorCategory.Unknown, DshApiError(null, "m", null, 415).category)
        assertEquals(DshErrorCategory.Unknown, DshApiError(null, "m", null, 400).category)
        assertEquals(DshErrorCategory.Unknown, DshApiError(null, "m", null, 426).category)
    }

    @Test
    fun `transport failure without code or status is Network`() {
        // 传输层失败（IOException/超时）：无信封、无状态码 → Network
        val error = DshApiError(code = null, message = "conn refused", details = null, httpStatus = null)
        assertEquals(DshErrorCategory.Network, error.category)
    }

    @Test
    fun `fields are carried through`() {
        val details = buildJsonObject { put("sessionId", "fixture-0001") }
        val error = DshApiError(DshRpcErrorCode.SessionNotFound, "(not attached)", details, 200)
        assertEquals("session-not-found", error.code?.wire)
        assertEquals("(not attached)", error.message)
        assertEquals(details, error.details)
        assertEquals(200, error.httpStatus)
        assertNull(DshApiError(null, "m", null, null).code)
    }
}
