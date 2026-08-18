package dev.leonardo.ocbeacon.data.local

import kotlinx.serialization.json.Json
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
 * 在 payload JSON 层重写 output 字段（保留前 [previewLimit] 字符 + 截断标记），
 * 其余字段原样保留（input/metadata/title/time 不动——它们通常很小且 UI 依赖）。
 * 解析失败时原样返回（防御：截断是优化，绝不因它丢数据结构）。
 */
object ToolOutputTruncator {

    const val DEFAULT_PREVIEW_LIMIT = 500
    private const val TRUNCATION_SUFFIX = "…[truncated, full output on server]"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 若 part payload 是 tool 类型且 output 超限，返回截断后的 payload；
     * 否则返回原字符串。只改写 state.output（Running/Completed/Error 变体）。
     */
    fun truncateIfNeeded(payload: String, previewLimit: Int = DEFAULT_PREVIEW_LIMIT): String {
        return try {
            val root = json.parseToJsonElement(payload).jsonObject
            val stateObj = root["state"]?.let { it as? JsonObject } ?: return payload
            val outputEl = stateObj["output"] ?: return payload
            val output = outputEl.jsonPrimitive.content
            if (output.length <= previewLimit) return payload
            val truncated = output.take(previewLimit) + TRUNCATION_SUFFIX
            // 重建：root + state'（output 替换）
            val newState = JsonObject(stateObj.toMap() + ("output" to JsonPrimitive(truncated)))
            val newRoot = JsonObject(root.toMap() + ("state" to newState))
            newRoot.toString()
        } catch (t: Throwable) {
            payload  // 解析失败原样保留（截断是优化不是正确性）
        }
    }
}
