package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * #324③ settings/describe → 轻量动态表单（schema 驱动）。
 *
 * 如实裁决（形态）：describe 回程的 schema 是 rehydrate 形态（refs/union/const），
 * **非 JSON-Schema**——完整 schema 表单引擎超移动端价值；本映射器只取「顶层标量
 * 字段 × JSON 类型 → 控件类型」+ union-of-const 枚举选项 + secret 剥离披露，
 * 与 web 各插件卡的字段面（agent-loop/shell/web-search/subagent-model 等全为
 * 顶层标量）一致。非标量顶层值（对象/数组）跳过——按值深渲染不属轻量表单。
 */
enum class DshSettingsFieldKind { TEXT, NUMBER, BOOLEAN, ENUM, SECRET }

/** 单个表单字段（key = settings 顶层键 = mutate path）。 */
data class DshSettingsField(
    val key: String,
    val kind: DshSettingsFieldKind,
    /** 当前值（value 树内原 JsonElement；secret 字段被服务端剥离 → null）。 */
    val value: JsonElement? = null,
    /** ENUM 选项（schema union-of-const / 朴素 enum 兜底）。 */
    val options: List<String> = emptyList(),
    /** SECRET：凭据是否已配置（describe secrets[].set）。 */
    val secretSet: Boolean? = null,
    /**
     * SECRET：凭据 ref（同 ns 顶层 *Env 字符串字段值——web mod41 同构：key 写
     * 走 credentials 域「addressed by the reference the section names」）。
     * null = 不可解析（无 Env 兄弟字段）→ UI 禁写并提示。
     */
    val secretRef: String? = null,
)

/** 一个 namespace 的表单投影。 */
data class DshSettingsNamespaceForm(
    val ns: String,
    val revision: Long,
    /** "live" | "restart"（重启生效标注）。 */
    val applies: String,
    val writable: Boolean,
    val fields: List<DshSettingsField>,
)

/**
 * 纯映射器：describe 的 namespace 条目（{ns,schema,value,applies,secrets,revision}）
 * → [DshSettingsNamespaceForm]；ns 缺席 → null。
 */
object DshSettingsFormMapper {

    fun map(namespace: JsonObject, nsWritable: Boolean): DshSettingsNamespaceForm? {
        val ns = (namespace["ns"] as? JsonPrimitive)?.content ?: return null
        val value = namespace["value"] as? JsonObject ?: JsonObject(emptyMap())
        val revision = ((namespace["revision"] as? JsonPrimitive)?.content)?.toLongOrNull() ?: 0L
        val applies = (namespace["applies"] as? JsonPrimitive)?.content ?: "live"
        val schema = namespace["schema"]

        // secrets 披露：path 长度 1 的顶层 secret 字段（嵌套 secret 不属轻量表单）
        val secretSetByPath = LinkedHashMap<String, Boolean>()
        (namespace["secrets"] as? JsonArray)?.forEach { el ->
            val secret = el as? JsonObject ?: return@forEach
            val path = (secret["path"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            if (path.size == 1) {
                secretSetByPath[path[0]] = (secret["set"] as? JsonPrimitive)?.content == "true"
            }
        }

        // secret ref 解析源：顶层 *Env 字符串字段（apiKeyEnv 先例）
        val envRef = value.entries.firstOrNull { (key, v) ->
            key.endsWith("Env") && v is JsonPrimitive && v !is JsonNull
        }?.value as? JsonPrimitive

        val fields = mutableListOf<DshSettingsField>()
        for ((key, raw) in value) {
            if (key in secretSetByPath) {
                fields += DshSettingsField(
                    key = key,
                    kind = DshSettingsFieldKind.SECRET,
                    value = null,
                    secretSet = secretSetByPath[key],
                    secretRef = envRef?.content,
                )
                continue
            }
            when (raw) {
                is JsonPrimitive -> when {
                    raw is JsonNull -> fields += DshSettingsField(key, DshSettingsFieldKind.TEXT, raw)
                    raw.content == "true" || raw.content == "false" ->
                        fields += DshSettingsField(key, DshSettingsFieldKind.BOOLEAN, raw)
                    raw.content.toDoubleOrNull() != null ->
                        fields += DshSettingsField(key, DshSettingsFieldKind.NUMBER, raw)
                    else -> {
                        val options = enumOptionsOf(schema, key, raw.content)
                        fields += DshSettingsField(
                            key = key,
                            kind = if (options.size >= 2) DshSettingsFieldKind.ENUM else DshSettingsFieldKind.TEXT,
                            value = raw,
                            options = options,
                        )
                    }
                }
                // JsonObject/JsonArray：非标量顶层值跳过（轻量表单边界）
                else -> continue
            }
        }
        // secret 字段被服务端剥离（value 缺席）——按 secrets 披露补投（value 树
        // 里仍留有该键的防御：上面循环已覆盖，这里只补缺席者）
        for ((key, set) in secretSetByPath) {
            if (value.containsKey(key)) continue
            fields += DshSettingsField(
                key = key,
                kind = DshSettingsFieldKind.SECRET,
                value = null,
                secretSet = set,
                secretRef = envRef?.content,
            )
        }
        return DshSettingsNamespaceForm(
            ns = ns,
            revision = revision,
            applies = applies,
            writable = nsWritable,
            fields = fields,
        )
    }

    /**
     * 字段枚举选项：rehydrate 形态 {refs:{id:const}, union:list:[ids]}（permission
     * 档位先例——DshApiClient.parseSchemaEnumOptions 同源语义）+ 朴素 {enum:[...]}
     * 兜底。schema 面向顶层键的声明位置：refs 表按 const 值命中（无字段级索引），
     * 仅当当前值也在选项集内才采信（防错绑到别的字段的 union）。
     */
    private fun enumOptionsOf(schemaEl: JsonElement?, key: String, currentValue: String): List<String> {
        val schema = schemaEl as? JsonObject ?: return emptyList()
        val refs = (schema["refs"] as? JsonObject)
            ?.mapNotNull { (id, node) -> (node as? JsonObject)?.let { id to it } }
            ?.toMap()
            ?: return emptyList()
        fun constValue(node: JsonObject): String? =
            if ((node["type"] as? JsonPrimitive)?.content == "const") {
                (node["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            } else null
        for (node in refs.values) {
            if ((node["type"] as? JsonPrimitive)?.content == "union") {
                val ids = (node["list"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                val values = ids.mapNotNull { id -> refs[id]?.let { constValue(it) } }
                if (values.size >= 2 && currentValue in values) return values
            }
        }
        return emptyList()
    }
}

/** settings/mutate 泛化 op（Set=顶层键写值 / Unset=顶层键删除）。 */
sealed interface DshSettingsOp {
    data class Set(val key: String, val value: JsonElement) : DshSettingsOp
    data class Unset(val key: String) : DshSettingsOp
}

/** settings/describe 全量快照（namespaces 保持原始 JSON 供映射器二次投影）。 */
data class DshSettingsSnapshot(
    val writable: Boolean,
    val hasDocument: Boolean,
    val namespaces: List<JsonObject>,
)

// ============ #324③ pluginInventory/list 域模型 ============

/** 运行态（fiberPhase；null = 无 fiber——未加载/静态声明）。 */
data class DshPluginInventoryEntry(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val fiberPhase: String? = null,
)

/** per-preset 行启用三态（对齐 wire false|true|"conditional"）。 */
enum class DshPluginEnabled { ENABLED, DISABLED, CONDITIONAL;

    companion object {
        fun fromWire(raw: String?): DshPluginEnabled? = when (raw) {
            "true" -> ENABLED
            "false" -> DISABLED
            "conditional" -> CONDITIONAL
            else -> null
        }
    }
}

data class DshPluginInventoryPresetRow(
    val entryId: String?,
    val moduleName: String,
    val enabled: DshPluginEnabled,
    val condition: String? = null,
    val fiberPhase: String? = null,
)

data class DshPluginInventoryPreset(
    val id: String,
    val trust: String,
    val name: String,
    val isDefault: Boolean,
    val broken: String? = null,
    val rows: List<DshPluginInventoryPresetRow> = emptyList(),
)

/** pluginInventory/list 快照（清单只读——web 同为只读展示，无 mutate 面）。 */
data class DshPluginInventory(
    val entries: List<DshPluginInventoryEntry> = emptyList(),
    val presets: List<DshPluginInventoryPreset> = emptyList(),
)
