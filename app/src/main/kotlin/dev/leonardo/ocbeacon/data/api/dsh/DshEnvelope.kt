package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * DSH RPC 错误码（backlog #274 组件 ①）。
 *
 * 39 值闭集全清单来自设计文档 §5 补遗行（RpcErrorDetailsMap keys，rpc.d.ts:26-175，
 * 2026-08-31 源码提取）。DSH 协议可能演进新增错误码，故不做成 enum：闭集外的
 * 未知码**保留原串**容错（[isKnown]=false），由上层按 [DshErrorCategory].Unknown 兜底。
 */
@JvmInline
value class DshRpcErrorCode(val wire: String) {

    /** 是否属于 39 值闭集。 */
    val isKnown: Boolean get() = wire in KNOWN_WIRE_CODES

    companion object {
        /** 闭集 wire 串全集（39 值，§5 补遗行）。 */
        private val KNOWN_WIRE_CODES: Set<String> = setOf(
            "bad-request", "cancelled", "session-not-found", "model-unavailable", "session-conflict",
            "invalid-time-zone",
            "workspace-attach-failed", "workspace-not-found", "workspace-invalid-path",
            "workspace-name-conflict", "workspace-move-invalid",
            "directory-unreadable", "directory-exists", "directory-create-failed", "picker-unavailable",
            "agent-preset-read-only", "agent-preset-locked", "agent-preset-conflict",
            "agent-preset-not-found", "agent-preset-invalid",
            "agent-busy", "attachment-error", "queue-item-not-found", "steer-unavailable",
            "command-error", "unknown-command",
            "settings-rejected", "settings-conflict",
            "credential-rejected", "model-discovery-failed", "title-invalid", "fork-unavailable",
            "subagent-parent-unavailable", "subagent-not-found",
            "catalog-diagnostic", "not-resumable", "unauthorized", "delivery-unavailable", "internal",
        )

        /** 闭集全量清单（表驱动测试用）。 */
        val ALL: List<DshRpcErrorCode> = KNOWN_WIRE_CODES.map { DshRpcErrorCode(it) }

        fun fromWire(raw: String): DshRpcErrorCode = DshRpcErrorCode(raw)

        // 常用码具名常量（全 39 个的语义分类见 DshApiError 表）
        val BadRequest = DshRpcErrorCode("bad-request")
        val Cancelled = DshRpcErrorCode("cancelled")
        val SessionNotFound = DshRpcErrorCode("session-not-found")
        val ModelUnavailable = DshRpcErrorCode("model-unavailable")
        val SessionConflict = DshRpcErrorCode("session-conflict")
        val InvalidTimeZone = DshRpcErrorCode("invalid-time-zone")
        val WorkspaceAttachFailed = DshRpcErrorCode("workspace-attach-failed")
        val WorkspaceNotFound = DshRpcErrorCode("workspace-not-found")
        val WorkspaceInvalidPath = DshRpcErrorCode("workspace-invalid-path")
        val WorkspaceNameConflict = DshRpcErrorCode("workspace-name-conflict")
        val WorkspaceMoveInvalid = DshRpcErrorCode("workspace-move-invalid")
        val DirectoryUnreadable = DshRpcErrorCode("directory-unreadable")
        val DirectoryExists = DshRpcErrorCode("directory-exists")
        val DirectoryCreateFailed = DshRpcErrorCode("directory-create-failed")
        val PickerUnavailable = DshRpcErrorCode("picker-unavailable")
        val AgentPresetReadOnly = DshRpcErrorCode("agent-preset-read-only")
        val AgentPresetLocked = DshRpcErrorCode("agent-preset-locked")
        val AgentPresetConflict = DshRpcErrorCode("agent-preset-conflict")
        val AgentPresetNotFound = DshRpcErrorCode("agent-preset-not-found")
        val AgentPresetInvalid = DshRpcErrorCode("agent-preset-invalid")
        val AgentBusy = DshRpcErrorCode("agent-busy")
        val AttachmentError = DshRpcErrorCode("attachment-error")
        val QueueItemNotFound = DshRpcErrorCode("queue-item-not-found")
        val SteerUnavailable = DshRpcErrorCode("steer-unavailable")
        val CommandError = DshRpcErrorCode("command-error")
        val UnknownCommand = DshRpcErrorCode("unknown-command")
        val SettingsRejected = DshRpcErrorCode("settings-rejected")
        val SettingsConflict = DshRpcErrorCode("settings-conflict")
        val CredentialRejected = DshRpcErrorCode("credential-rejected")
        val ModelDiscoveryFailed = DshRpcErrorCode("model-discovery-failed")
        val TitleInvalid = DshRpcErrorCode("title-invalid")
        val ForkUnavailable = DshRpcErrorCode("fork-unavailable")
        val SubagentParentUnavailable = DshRpcErrorCode("subagent-parent-unavailable")
        val SubagentNotFound = DshRpcErrorCode("subagent-not-found")
        val CatalogDiagnostic = DshRpcErrorCode("catalog-diagnostic")
        val NotResumable = DshRpcErrorCode("not-resumable")
        val Unauthorized = DshRpcErrorCode("unauthorized")
        val DeliveryUnavailable = DshRpcErrorCode("delivery-unavailable")
        val Internal = DshRpcErrorCode("internal")
    }
}

/**
 * DSH 统一结果面：RPC 响应与 respond 回程共用（§1：业务结果恒
 * `result:{"ok":true,"value":...}` 或 `result:{"ok":false,"error":{code,message,details?}}`）。
 */
sealed class DshRpcResult {

    /** 成功分支。[value] 缺席/JSON null 时为 null。 */
    data class Ok(val value: JsonElement?) : DshRpcResult()

    /** 失败分支。[details] 缺席或非对象时为 null；[code] 保留未知码原串。 */
    data class Err(
        val code: DshRpcErrorCode,
        val message: String,
        val details: JsonObject?,
    ) : DshRpcResult()
}

/**
 * DSH 四象限信封（backlog #274 组件 ①；设计文档 §1 信封契约 + §1.5 WS 帧信封）。
 *
 * 非标准 JSON-RPC 2.0：`type` 判别式取值四象限——
 * - [ClientRequest]：`{"type":"client-request","rpcId","method","payload"}`，
 *   method 必须同时出现在 URL 路径段与 body（DshRpcClient 保证两者同源）；
 * - [ServerResponse]：`{"type":"server-response","rpcId":echo,"result":{ok,...}}`；
 * - [ServerRequest]（WS 下行帧）：`{"type":"server-request","rpcId","method":帧型,
 *   "payload":{"type":同method,...}}`（§1.5 实测：payload.type 与 method 一致性校验）；
 * - [ClientResponse]（/api/respond 回程）：`{"type":"client-response","rpcId":稳定id,"result"}`。
 *
 * payload 是开放联合（49 型 SessionEvent + 52 方法参数面），一律以 [JsonObject]
 * 透传，**不为未知 payload 建强类型**。解码对畸形/违约输入一律返回 null（容错不抛）。
 */
sealed class DshEnvelope {

    abstract val rpcId: String

    /** 客户端 → 服务端 RPC 请求（POST /api/{method}）。 */
    data class ClientRequest(
        override val rpcId: String,
        val method: String,
        val payload: JsonObject,
    ) : DshEnvelope()

    /** 服务端 → 客户端 RPC 响应（HTTP 200 body）。 */
    data class ServerResponse(
        override val rpcId: String,
        val result: DshRpcResult,
    ) : DshEnvelope()

    /** 服务端 → 客户端 WS 下行帧（/api/events.mux + /api/events.host）。 */
    data class ServerRequest(
        override val rpcId: String,
        val method: String,
        val payload: JsonObject,
    ) : DshEnvelope()

    /** 客户端 → 服务端 respond 回程（POST /api/respond，rpcId 复用 server-request 的稳定 id）。 */
    data class ClientResponse(
        override val rpcId: String,
        val result: DshRpcResult,
    ) : DshEnvelope()

    companion object {

        private val json = Json

        /** rpcId 铸造：UUID 串（§1 实测契约）。 */
        fun newRpcId(): String = UUID.randomUUID().toString()

        // ============ 编码 ============

        fun encode(envelope: DshEnvelope): String = when (envelope) {
            is ClientRequest -> buildJsonObject {
                put("type", "client-request")
                put("rpcId", envelope.rpcId)
                put("method", envelope.method)
                put("payload", envelope.payload)
            }
            is ServerRequest -> buildJsonObject {
                put("type", "server-request")
                put("rpcId", envelope.rpcId)
                put("method", envelope.method)
                put("payload", envelope.payload)
            }
            is ServerResponse -> buildJsonObject {
                put("type", "server-response")
                put("rpcId", envelope.rpcId)
                put("result", encodeResult(envelope.result))
            }
            is ClientResponse -> buildJsonObject {
                put("type", "client-response")
                put("rpcId", envelope.rpcId)
                put("result", encodeResult(envelope.result))
            }
        }.toString()

        private fun encodeResult(result: DshRpcResult): JsonObject = when (result) {
            is DshRpcResult.Ok -> buildJsonObject {
                put("ok", true)
                put("value", result.value ?: JsonNull)
            }
            is DshRpcResult.Err -> buildJsonObject {
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", result.code.wire)
                    put("message", result.message)
                    result.details?.let { put("details", it) }
                })
            }
        }

        // ============ 解码（容错：畸形/违约 → null） ============

        fun decode(text: String): DshEnvelope? {
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            val rpcId = root.str("rpcId") ?: return null
            return when (root.str("type")) {
                "client-request" -> {
                    val method = root.str("method") ?: return null
                    val payload = root.obj("payload") ?: return null
                    ClientRequest(rpcId, method, payload)
                }
                "server-request" -> {
                    val method = root.str("method") ?: return null
                    val payload = root.obj("payload") ?: return null
                    // §1.5 实测：帧 method 即帧型，payload.type 必须与之一致
                    val payloadType = (payload["type"] as? JsonPrimitive)?.contentOrNull
                    if (payloadType != method) return null
                    ServerRequest(rpcId, method, payload)
                }
                "server-response" -> ServerResponse(rpcId, decodeResult(root) ?: return null)
                "client-response" -> ClientResponse(rpcId, decodeResult(root) ?: return null)
                else -> null
            }
        }

        private fun decodeResult(root: JsonObject): DshRpcResult? {
            val result = root.obj("result") ?: return null
            val ok = (result["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
            return when (ok) {
                true -> {
                    val value = result["value"]?.let { if (it is JsonNull) null else it }
                    DshRpcResult.Ok(value)
                }
                false -> {
                    val error = result.obj("error") ?: return null
                    val code = error.str("code") ?: return null
                    val message = error.str("message") ?: ""
                    val details = error.obj("details")
                    DshRpcResult.Err(DshRpcErrorCode(code), message, details)
                }
                else -> null
            }
        }

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        private fun JsonObject.obj(key: String): JsonObject? =
            this[key] as? JsonObject
    }
}
