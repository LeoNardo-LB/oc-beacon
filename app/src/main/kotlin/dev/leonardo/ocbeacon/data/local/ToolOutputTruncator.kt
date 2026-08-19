package dev.leonardo.ocbeacon.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * #79 P0（2026-08-18）：tool part 本地落库截断——工具返回值占 DB 97%
 *（实测 28MB 中 12.4MB shell 输出等），只影响本地缓存不影响内存渲染
 *（消息在内存时工具卡片完整可展开；服务器始终保留全量可重拉）。
 *
 * #79 P1（2026-08-19）：扩展至 state.input / state.metadata 内的超长字符串
 *（实测 write input 18.8KB 文件内容、edit metadata 5.5KB diff 为 P0 后剩余
 * 大头）+ 新增 Reasoning text 截断（实测 637 条共 736KB、单条最大 45KB）。
 * input/metadata 是 Map<String, JsonElement>——不能字符串级截断（会破坏
 * JSON 合法性），改为递归重写超长字符串**原语**（结构原样保留）。
 *
 * 截断是优化不是正确性：解析失败一律原样返回。
 */
object ToolOutputTruncator {

    const val DEFAULT_PREVIEW_LIMIT = 500
    private const val TRUNCATION_SUFFIX = "…[truncated, full output on server]"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 若 part payload 是 tool 类型且有超限内容，返回截断后的 payload；
     * 否则返回原字符串。改写范围：state.output（字符串）+ state.input /
     * state.metadata 内超长字符串原语（递归，结构保留）。
     */
    fun truncateIfNeeded(payload: String, previewLimit: Int = DEFAULT_PREVIEW_LIMIT): String {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val stateObj = root["state"]?.let { it as? JsonObject } ?: return payload
            val newState = truncateState(stateObj, previewLimit)
            if (newState === stateObj) return payload
            JsonObject(root.toMap() + ("state" to newState)).toString()
        } catch (t: Throwable) {
            payload  // 解析失败原样保留（截断是优化不是正确性）
        }
    }

    /**
     * #79 P1：Reasoning part 落库截断——思考全文占 DB 显著空间
     *（实测单条最大 45KB）。只改写顶层 text 字段，其余原样。
     */
    fun truncateReasoningIfNeeded(payload: String, previewLimit: Int = DEFAULT_PREVIEW_LIMIT): String {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val textEl = root["text"] ?: return payload
            val text = textEl.jsonPrimitive.content
            if (text.length <= previewLimit) return payload
            val truncated = text.take(previewLimit) + TRUNCATION_SUFFIX
            JsonObject(root.toMap() + ("text" to JsonPrimitive(truncated))).toString()
        } catch (t: Throwable) {
            payload
        }
    }

    /** state 对象级截断：output（字符串）+ input/metadata（递归原语）。 */
    internal fun truncateState(stateObj: JsonObject, previewLimit: Int): JsonObject {
        var changed = false
        val out = stateObj.toMap().toMutableMap()

        // 1. output：字符串直接截断（P0 行为保持）
        (out["output"] as? JsonPrimitive)?.let { p ->
            val s = p.contentOrNullSafe()
            if (s != null && s.length > previewLimit) {
                out["output"] = JsonPrimitive(s.take(previewLimit) + TRUNCATION_SUFFIX)
                changed = true
            }
        }

        // 2. input / metadata：递归截断超长字符串原语（结构保留）
        for (key in listOf("input", "metadata")) {
            val el = out[key] ?: continue
            // 快速路径：整体序列化不超 2×limit 时跳过递归（绝大多数 input
            // 是短命令/参数，避免白付遍历成本）
            if (el.toString().length <= previewLimit * 2) continue
            val truncated = truncateElement(el, previewLimit)
            if (truncated !== el) {
                out[key] = truncated
                changed = true
            }
        }
        return if (changed) JsonObject(out) else stateObj
    }

    /** 递归重写超长字符串原语；对象/数组结构原样重建，短值零拷贝共享。 */
    private fun truncateElement(element: JsonElement, previewLimit: Int): JsonElement {
        return when (element) {
            is JsonObject -> {
                var changed = false
                val newMap = element.toMap().mapValues { (_, v) ->
                    val t = truncateElement(v, previewLimit)
                    if (t !== v) changed = true
                    t
                }
                if (changed) JsonObject(newMap) else element
            }
            is JsonArray -> {
                var changed = false
                val newList = element.map { e ->
                    val t = truncateElement(e, previewLimit)
                    if (t !== e) changed = true
                    t
                }
                if (changed) JsonArray(newList) else element
            }
            is JsonPrimitive -> {
                val s = element.contentOrNullSafe() ?: return element
                if (s.length > previewLimit) {
                    JsonPrimitive(s.take(previewLimit) + TRUNCATION_SUFFIX)
                } else element
            }
        }
    }

    /** JsonPrimitive.content 对非字符串（数字/布尔/null）会抛异常——安全读取。 */
    private fun JsonPrimitive.contentOrNullSafe(): String? = try {
        if (isString) content else null
    } catch (t: Throwable) {
        null
    }
}