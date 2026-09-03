package dev.leonardo.ocbeacon.data.api.dsh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DSH 传输线面版本（backlog #317/#318；journal 2026-09-04-dsh-012-adaptation §二）。
 *
 * 0.1.2（rc.1/alpha.5 实测）对 0.1.1 的线面破坏：
 * - 一元端点命名 session.list（点）→ session/list（斜杠）；
 * - payload 包一层 {args:{<宿主方法参数名>}}（args 键因端点而异，见 [DshWireAdapter]）；
 * - 新增强制鉴权（token 交换 → cookie；401 = 未认证）→ [DshAuthRequiredException]；
 * - 事件流重构（events.mux/events.host 删除 → remote.mux 单 WS，WS 侧另行适配）。
 *
 * 版本判定不依赖 host.describe（0.1.2 已无该端点）——由 [DshConnectionRegistry]
 * 双形态探测承担：slash+args 与 dot+裸 payload 各发一次，200/401/404 组合唯一判定
 * {版本 × 鉴权态}（journal §2.1 判别表）。
 */
enum class DshWireProtocol {
    /** ≤0.1.1：点式端点 + 裸 payload + 无鉴权。 */
    V011,

    /** ≥0.1.2：斜杠端点 + args 包装 + cookie 鉴权。 */
    V012,
}

/**
 * 双形态探测结果。
 *
 * 判别表（journal §2.1，实测）：
 * - slash 401 + dot 401 → 0.1.2 未认证（auth 栅栏先于端点匹配，两形态同 401）；
 * - slash 200* + dot 404 → 0.1.2 已认证（200 body 可为 ok 或 arguments-invalid，
 *   均证明端点存在）；
 * - dot 200 + slash 404 → 0.1.1（无鉴权）；
 * - dot 200 + slash 200 → 异常态（按 V011 降级 + 告警）。
 */
sealed interface DshProbeOutcome {
    /** 探测成功：线面版本 + 鉴权态。 */
    data class Online(
        val protocol: DshWireProtocol,
        /** V012 恒 true（cookie 在位且有效）；V011 恒 true（无鉴权面）。 */
        val authenticated: Boolean,
    ) : DshProbeOutcome

    /**
     * 0.1.2 + 凭据缺失/失效（含 cookie 过期）——需要用户输入 token 走
     * [DshConnectionRegistry.exchangeToken]。
     */
    data object TokenNeeded : DshProbeOutcome

    /** 传输层不可达（连接失败/超时）——连接循环按既有退避处理。 */
    data class Unreachable(val detail: String) : DshProbeOutcome
}

/**
 * 0.1.1 ↔ 0.1.2 线面适配器（纯函数，无状态；journal §2.3 端点注册表 + 参数键实测）。
 *
 * 职责边界：只做**机械翻译**（方法改名 + args 包装/字段改名）；带语义变化的载荷
 * （session.create 无 title、session.history→page 的 address/throughSeq、
 * session.prompt 增 requestId）由调用点按 [DshWireProtocol] 分支构造，不经本类。
 *
 * 实测锚点（2026-09-04 alpha.5 容器 + 生产 rc.1）：
 * - session/list args 键 = `_request`；其余 session 域方法与 goals/create = `request`；
 * - agentPresets/select 与 goals 域的会话引用字段 **sessionId → agentId**；
 * - subagents/list 直接平铺 parentSessionId（无 request 包装）；
 * - commands/execute、commands/list 在 0.1.1 即 args 形态 → 两版恒等变换；
 * - settings/describe、session/modelCatalog、agentPresets/list、llm/listProviders
 *   无参（args:{}）。
 */
object DshWireAdapter {

    /**
     * 方法名翻译（0.1.1 点式 → 0.1.2 斜杠式）。未列出的方法按点→斜杠机械替换
     * （0.1.2 命名规律：namespace/method；复数变化的命名空间显式列出）。
     */
    fun method(method: String, protocol: DshWireProtocol): String = when (protocol) {
        DshWireProtocol.V011 -> method
        DshWireProtocol.V012 -> RENAMES[method] ?: method.replace('.', '/')
    }

    /**
     * payload 翻译：0.1.1 裸 payload → 0.1.2 {args:{…}} 包装 + 字段改名。
     *
     * 恒等情形：commands 两方法（0.1.1 已是 args 形态）。改名在包装**之前**对裸 payload
     * 执行（sessionId→agentId 等仅适用于平铺键端点——见 [FLAT_KEYS]）。
     */
    fun payload(method: String, protocol: DshWireProtocol, payload: JsonObject): JsonObject = when (protocol) {
        DshWireProtocol.V011 -> payload
        DshWireProtocol.V012 -> {
            val wireMethod = method(method, protocol)
            if (wireMethod in SELF_WRAPPED) {
                payload
            } else {
                val bare = renameFlatFields(wireMethod, payload)
                buildJsonObject {
                    put("args", bare)
                }
            }
        }
    }

    /**
     * 0.1.2 下该方法的 RPC 载荷是否为非对象 value（数组直返，callJson 语义）：
     * commands/list、llm/listProviders。
     */
    fun isArrayValue(method: String, protocol: DshWireProtocol): Boolean = when (protocol) {
        DshWireProtocol.V011 -> method == "commands/list"
        DshWireProtocol.V012 -> method(method, protocol) in ARRAY_VALUE_ENDPOINTS
    }

    // ---- 内部：端点注册表（journal §2.3） --------------------------------

    /** 显式改名（机械点→斜杠之外的变化：复数命名空间/跨命名空间迁移/语义替换）。 */
    private val RENAMES = mapOf(
        // session 域：history 语义替换为 page（载荷适配在调用点）
        "session.history" to "session/page",
        // 复数命名空间
        "subagent.list" to "subagents/list",
        "agentPreset.list" to "agentPresets/list",
        "agentPreset.select" to "agentPresets/select",
        "goal.create" to "goals/create",
        "goal.edit" to "goals/edit",
        "goal.pause" to "goals/pause",
        "goal.resume" to "goals/resume",
        "goal.complete" to "goals/complete",
        "goal.clear" to "goals/clear",
        // 跨命名空间迁移
        "host.listDirectory" to "directoryPicker/list",
        "llm.providers" to "llm/listProviders",
        // llm.models 无对应 → session/modelCatalog（调用点走专用分支，不经 RPC 泛面）
        "llm.models" to "session/modelCatalog",
    )

    /** 0.1.1 即 args 形态的方法（恒等变换，勿二次包装）。 */
    private val SELF_WRAPPED = setOf(
        "commands/execute",
        "commands/list",
    )

    /** 数组直返端点（callJson 语义）。 */
    private val ARRAY_VALUE_ENDPOINTS = setOf(
        "commands/list",
        "llm/listProviders",
    )

    /**
     * 平铺键端点：包装前对裸 payload 做字段改名（实测 agentPresets/select 与
     * goals 域的会话引用 = agentId，0.1.1 为 sessionId）。
     * request 包装型端点（session 域）的字段在包装内部，由调用点语义分支负责。
     */
    private val FLAT_KEYS = mapOf(
        "agentPresets/select" to mapOf("sessionId" to "agentId"),
        "goals/create" to mapOf("sessionId" to "agentId"),
        "goals/edit" to mapOf("sessionId" to "agentId"),
        "goals/pause" to mapOf("sessionId" to "agentId"),
        "goals/resume" to mapOf("sessionId" to "agentId"),
        "goals/complete" to mapOf("sessionId" to "agentId"),
        "goals/clear" to mapOf("sessionId" to "agentId"),
        "subagents/list" to mapOf("sessionId" to "agentId"),
    )

    private fun renameFlatFields(wireMethod: String, payload: JsonObject): JsonObject {
        val renames = FLAT_KEYS[wireMethod] ?: return payload
        if (renames.keys.none { it in payload }) return payload
        return buildJsonObject {
            for ((key, value) in payload) {
                put(renames[key] ?: key, value)
            }
        }
    }
}
