package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * OpenCode Server API 版本标识。
 *
 * - [V1]: OpenCode ≤ 1.x（路径无 /api 前缀，SSE 事件为 { type, properties }）
 * - [V2]: OpenCode ≥ 2.x（路径有 /api 前缀，响应包裹在 { data } 中，SSE 事件为 { id, event, data }）
 * - [UNKNOWN]: 未检测（回退到 V1 行为）
 */
@Serializable
enum class ApiVersion {
    V1,
    V2,
    UNKNOWN;

    /**
     * 如果版本为 V2 返回 true。
     */
    val isV2: Boolean get() = this == V2

    companion object {
        /**
         * 从版本字符串推断 API 版本。
         * OpenCode ≥ 2.x 使用 V2 API。
         */
        fun fromVersionString(version: String?): ApiVersion {
            if (version.isNullOrBlank()) return UNKNOWN
            // 2.x → V2
            val major = version.substringBefore('.').toIntOrNull() ?: return UNKNOWN
            return if (major >= 2) V2 else V1
        }
    }
}
