package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.JsonObject

/**
 * DSH 错误语义分类七类（backlog #274 组件 ②；设计文档 §2.4 错误层）。
 *
 * 本项目 UI 文案一律走 strings.xml——此处**只做错误语义分类**，不内嵌任何
 * 用户可见文案；接入层（⑦）按 category 选字符串资源。[Network] 为传输层
 * 失败保留（IOException/超时，无信封无状态码）；[Auth] 在 DSH 下仅覆盖
 * Host 栅栏 unauthorized 与上游凭据被拒（DSH 本身无鉴权面，§1.6 P-1）。
 */
enum class DshErrorCategory {
    /** 资源不存在（session/workspace/preset/queue item/subagent not-found 族）。 */
    NotFound,

    /** 资源被占用或暂不可继续（agent-busy/locked/not-resumable/steer-unavailable）。 */
    Busy,

    /** 状态或命名冲突（*-conflict/directory-exists）。 */
    Conflict,

    /** Host 栅栏 unauthorized / 上游凭据 credential-rejected（DSH 无常规鉴权）。 */
    Auth,

    /** 服务端执行失败（internal/model-unavailable/目录与命令失败族）。 */
    Server,

    /** 传输层失败（无信封无状态码：IOException/超时）。 */
    Network,

    /** 客户端输入违约或无领域语义（bad-request 族/未知码）。 */
    Unknown,
}

/**
 * DSH RPC 错误（backlog #274 组件 ②；设计文档 §1/§2.4/§5）。
 *
 * 携带闭集错误码 [code]（[DshRpcErrorCode]，未知码保留原串）+ 服务端 message +
 * 可选 details（原样 [JsonObject] 透传）+ 搬运层 HTTP 状态 [httpStatus]。
 *
 * 与既有 [dev.leonardo.ocbeacon.domain.model.ApiError]（HTTP 形状分类学，
 * 401/403/404/429…）语义不匹配——DSH 错误恒 HTTP 200 + result.error 闭集码
 * （§1 实测），故独立建型；分类经 [category]（七类）暴露，勿据此硬编码 UI 文案。
 */
data class DshApiError(
    val code: DshRpcErrorCode?,
    override val message: String,
    val details: JsonObject?,
    val httpStatus: Int?,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    /** 语义分类：有码按 39 码闭集表；无码按 HTTP 搬运层语义；两者皆无 = 传输层 [DshErrorCategory.Network]。 */
    val category: DshErrorCategory
        get() = when {
            code != null -> categoryForCode(code)
            httpStatus != null -> categoryForStatus(httpStatus)
            else -> DshErrorCategory.Network
        }

    companion object {
        /** 39 码 → 七类表（§5 补遗行闭集；与 DshApiErrorTest 期望表互为独立来源）。 */
        private val CODE_CATEGORIES: Map<String, DshErrorCategory> = mapOf(
            // NotFound
            "session-not-found" to DshErrorCategory.NotFound,
            "workspace-not-found" to DshErrorCategory.NotFound,
            "agent-preset-not-found" to DshErrorCategory.NotFound,
            "queue-item-not-found" to DshErrorCategory.NotFound,
            "subagent-not-found" to DshErrorCategory.NotFound,
            // Busy
            "agent-busy" to DshErrorCategory.Busy,
            "agent-preset-locked" to DshErrorCategory.Busy,
            "not-resumable" to DshErrorCategory.Busy,
            "steer-unavailable" to DshErrorCategory.Busy,
            // Conflict
            "session-conflict" to DshErrorCategory.Conflict,
            "workspace-name-conflict" to DshErrorCategory.Conflict,
            "agent-preset-conflict" to DshErrorCategory.Conflict,
            "settings-conflict" to DshErrorCategory.Conflict,
            "directory-exists" to DshErrorCategory.Conflict,
            // Auth（DSH 无鉴权面：仅栅栏 + 上游凭据两处）
            "unauthorized" to DshErrorCategory.Auth,
            "credential-rejected" to DshErrorCategory.Auth,
            // Server
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
            // Unknown（其余闭集成员：客户端输入违约/无领域语义）
        )

        /** HTTP 搬运层语义（§5：415 非 JSON/400 非 JSON body/404 未知方法/403 Host/426 需 WS/500 崩溃）。 */
        internal fun categoryForStatus(status: Int): DshErrorCategory = when (status) {
            404 -> DshErrorCategory.NotFound
            403 -> DshErrorCategory.Auth
            in 500..599 -> DshErrorCategory.Server
            else -> DshErrorCategory.Unknown
        }

        internal fun categoryForCode(code: DshRpcErrorCode): DshErrorCategory =
            CODE_CATEGORIES[code.wire] ?: DshErrorCategory.Unknown
    }
}
