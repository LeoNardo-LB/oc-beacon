package dev.leonardo.ocbeacon.ui.navigation.routes

import java.net.URLDecoder

/**
 * 安全地对导航参数值进行 URL 解码。
 *
 * Navigation 的 StringType 把原始（仍编码的）查询参数值
 * 传给 [androidx.navigation.NavBackStackEntry.arguments]，需要我们自己解码。
 * 但 [URLDecoder.decode] 遇到畸形百分号序列（如 `%NR` 或 `%25`，可能
 * 出现在用户密码/路径中）时会抛出 [IllegalArgumentException]。
 * 此包装器在那种情况下回退到原始字符串。
 */
internal fun safeDecodeParam(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (_: IllegalArgumentException) {
    // 畸形百分号序列（如 %NR）— 原样返回以避免崩溃。
    value
}
