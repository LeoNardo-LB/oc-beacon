package dev.leonardo.ocbeacon.data.api.dsh

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

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
 * 线面版本只读视图（#318 方法面）。
 *
 * [DshApiClient] 的语义分支（0.1.2 载荷构造）需要知道目标线面版本，但不直接
 * 依赖 [DshConnectionRegistry]——注册表构造需要 Android Keystore/DataStore，
 * 单元测试不可建。生产侧由 [DshProtocolSourceAdapter] 转发注册表；测试用假实现
 * （或 [DshUnprobedProtocolSource]）。
 */
interface DshProtocolSource {
    /** 当前已知线面版本；未探测返回 null（调用方保守按 V011 处理）。 */
    fun protocolOf(baseUrl: String): DshWireProtocol?

    /**
     * #391 切片6：一次握手（双形态探测，含鉴权态判别）。默认实现返回 Unreachable——
     * 只读替身 / 测试替身无需探测能力即可满足契约（调用方按退避处理）。
     */
    suspend fun ensureProbed(authority: String): DshProbeOutcome =
        DshProbeOutcome.Unreachable("probe not supported by this source")
}

/** 生产适配器：转发 [DshConnectionRegistry]（注册表是 @Singleton 已注入管线）。 */
@Singleton
class DshProtocolSourceAdapter @Inject constructor(
    private val registry: DshConnectionRegistry,
) : DshProtocolSource {
    override fun protocolOf(baseUrl: String): DshWireProtocol? = registry.protocolOf(baseUrl)

    override suspend fun ensureProbed(authority: String): DshProbeOutcome =
        registry.ensureProbed(authority)
}

/** 接口注入面绑定（实现类 @Inject 构造；模块随接口同文件——#318 收口）。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DshProtocolSourceModule {
    @Binds
    abstract fun bindDshProtocolSource(impl: DshProtocolSourceAdapter): DshProtocolSource
}

/** 恒「未探测」（null → 调用方保守 V011）协议源——测试便利构造的缺省兜底。 */
object DshUnprobedProtocolSource : DshProtocolSource {
    override fun protocolOf(baseUrl: String): DshWireProtocol? = null
}

/**
 * 0.1.1 ↔ 0.1.2 线面适配器（纯函数，无状态；journal §2.3 端点注册表 + 参数键实测）。
 *
 * 职责边界：只做**机械翻译**（方法改名 + args 包装/字段改名）；带语义变化的载荷
 * （session.create 无 title、session.history→page 的 address/throughSeq、
 * session.prompt 增 requestId、goals 域 args 语义结构）由调用点按 [DshWireProtocol]
 * 分支构造，不经本类。
 *
 * payload 风格表（0.1.2，按 wire 方法名分派；实测锚点 2026-09-04 alpha.5 容器 +
 * 生产 rc.1）：
 * - **SELF**：恒等（调用方已构造完整线面 payload，含 args 包装）——commands 两方法
 *   （0.1.1 即 args 形态）+ goals 六方法（0.1.2 形态由调用点语义构造）；
 * - **EMPTY_ARGS**：无参端点，payload 丢弃 → {args:{}}——settings/describe、
 *   session/modelCatalog、agentPresets/list、llm/listProviders、#324 新增
 *   llm/listConfigurableProviders、pluginInventory/list；
 * - **FLAT**：{args:{…}} 直包裸 payload（sessionId→agentId 改名 + 丢弃 JsonNull 值）
 *   ——subagents/list（键是 parentSessionId，无需改名）、#310⑤/#321 两引用端点
 *   （键是 agentId+query，无需改名）、agentPresets/select、directoryPicker/list、
 *   settings/mutate、#324 新增 llm/discoverModels、credentials/describe|set|unset、
 *   agentPresets/read|copy|deletePreset（typert 参数名即 wire 键）；
 * - **WRAPPED**：{args:{key:payload}}（key 默认 request；session/list = _request）
 *   ——其余全部（session 域）。
 */
object DshWireAdapter {

    /**
     * 方法名翻译（0.1.1 点式 → 0.1.2 斜杠式）。未列出的方法按点→斜杠机械替换
     * （0.1.2 命名规律：namespace/method；复数变化的命名空间显式列出）。
     * 无点号的名字恒等（goals/create 等斜杠名直传不二次变换）。
     */
    fun method(method: String, protocol: DshWireProtocol): String = when (protocol) {
        DshWireProtocol.V011 -> method
        DshWireProtocol.V012 -> RENAMES[method] ?: method.replace('.', '/')
    }

    /**
     * payload 翻译：0.1.1 裸 payload → 0.1.2 目标形态（风格表见类文档）。
     *
     * 恒等情形：SELF 集合（commands 两方法 + goals 六方法——调用点对两版本各自
     * 构造完整 payload，本方法只对方法名做翻译）。字段改名仅适用于 FLAT 集合
     * （平铺键端点）；WRAPPED 端点的包装内部字段由调用点语义分支负责。
     */
    fun payload(method: String, protocol: DshWireProtocol, payload: JsonObject): JsonObject = when (protocol) {
        DshWireProtocol.V011 -> payload
        DshWireProtocol.V012 -> {
            val wireMethod = method(method, protocol)
            when {
                wireMethod in SELF_METHODS -> payload
                wireMethod in EMPTY_ARGS_METHODS -> buildJsonObject {
                    put("args", buildJsonObject {})
                }
                wireMethod in FLAT_METHODS -> buildJsonObject {
                    put("args", flatFields(wireMethod, payload))
                }
                else -> buildJsonObject {
                    put("args", buildJsonObject {
                        put(WRAPPED_ARG_KEYS[wireMethod] ?: "request", payload)
                    })
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
        // 复数命名空间（#310①：subagents 域 prompt/interruptByParent 续聊双方法）
        "subagent.list" to "subagents/list",
        "subagent.prompt" to "subagents/prompt",
        "subagent.interruptByParent" to "subagents/interruptByParent",
        "agentPreset.list" to "agentPresets/list",
        "agentPreset.select" to "agentPresets/select",
        // #324②：agentPresets 域管理三方法（复数命名空间同族）
        "agentPreset.read" to "agentPresets/read",
        "agentPreset.copy" to "agentPresets/copy",
        "agentPreset.deletePreset" to "agentPresets/deletePreset",
        // #324①：llm 目录探查（listConfigurableProviders 复合名机械替换不中）
        "llm.listConfigurableProviders" to "llm/listConfigurableProviders",
        "goal.create" to "goals/create",
        "goal.edit" to "goals/edit",
        "goal.pause" to "goals/pause",
        "goal.resume" to "goals/resume",
        "goal.complete" to "goals/complete",
        "goal.clear" to "goals/clear",
        // 跨命名空间迁移
        "host.listDirectory" to "directoryPicker/list",
        // W4/D8(2026-09-06):原生建目录(机械替换会得 host/createDirectory——错)。
        "host.createDirectory" to "directoryPicker/createDirectory",
        "llm.providers" to "llm/listProviders",
        // llm.models 无对应 → session/modelCatalog（调用点走专用分支，不经 RPC 泛面）
        "llm.models" to "session/modelCatalog",
    )

    /**
     * SELF：恒等变换（调用方已构造完整线面 payload）。commands 两方法 0.1.1 即
     * args 形态；goals 六方法的 0.1.2 形态（含 args 包装 + agentId/request 语义
     * 结构）由调用点构造——V011 分支同理由调用点构造裸 payload。
     */
    private val SELF_METHODS = setOf(
        "commands/execute",
        "commands/list",
        "goals/create",
        "goals/edit",
        "goals/pause",
        "goals/resume",
        "goals/complete",
        "goals/clear",
    )

    /** 无参端点：payload 丢弃 → {args:{}}。 */
    private val EMPTY_ARGS_METHODS = setOf(
        "settings/describe",
        "session/modelCatalog",
        "agentPresets/list",
        "llm/listProviders",
        // #324①③：llm 目录探查 + 插件清单（typert 零参端点）
        "llm/listConfigurableProviders",
        "pluginInventory/list",
    )

    /**
     * 平铺端点：裸 payload 直包 {args:{…}}（改名 + 丢 JsonNull）。
     * subagents/list 的键是 parentSessionId（无需改名，实测无 request 包装）。
     */
    private val FLAT_METHODS = setOf(
        "subagents/list",
        // #310①：三平铺参 {childSessionId,parentSessionId,mode}（typert 参数名即 wire 键）
        "subagents/interruptByParent",
        // #310⑤/#321：两平铺参 {agentId,query}（typert 参数名即 wire 键）
        "fileReferences/list",
        "sessionReferenceResolver/candidates",
        "agentPresets/select",
        "directoryPicker/list",
        // W4/D8:两平铺参 {path,name}(typert 参数名即 wire 键)。
        "directoryPicker/createDirectory",
        "settings/mutate",
        // #324①：llm/discoverModels {settingsNs,request} + credentials 三方法
        // {keys}/{key,value}/{key}（typert 参数名即 wire 键）
        "llm/discoverModels",
        "credentials/describe",
        "credentials/set",
        "credentials/unset",
        // #324②：agentPresets 管理三方法（from/id/name? · agentPreset · id）
        "agentPresets/copy",
        "agentPresets/read",
        "agentPresets/deletePreset",
    )

    /** request 包装型端点的 args 键（默认 request；session/list 实测 = _request）。 */
    private val WRAPPED_ARG_KEYS = mapOf(
        "session/list" to "_request",
    )

    /** FLAT 端点的平铺字段改名（实测 agentPresets/select 会话引用 = agentId）。 */
    private val FLAT_RENAMES = mapOf(
        "agentPresets/select" to mapOf("sessionId" to "agentId"),
    )

    /** 数组直返端点（callJson 语义）。 */
    private val ARRAY_VALUE_ENDPOINTS = setOf(
        "commands/list",
        "llm/listProviders",
    )

    /** FLAT 字段整理：改名 sessionId→agentId（按端点表）+ 丢弃 JsonNull 值。 */
    private fun flatFields(wireMethod: String, payload: JsonObject): JsonObject {
        val renames = FLAT_RENAMES[wireMethod] ?: emptyMap()
        return buildJsonObject {
            for ((key, value) in payload) {
                if (value is JsonNull) continue
                put(renames[key] ?: key, value)
            }
        }
    }
}
