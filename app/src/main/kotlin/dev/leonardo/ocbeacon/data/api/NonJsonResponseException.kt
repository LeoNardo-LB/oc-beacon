package dev.leonardo.ocbeacon.data.api

/**
 * 服务器返回 HTML 而非 JSON 时抛出的异常。
 *
 * 触发场景：服务器（如 opencode V1 1.18.18 过渡形态）对**不存在的 API 路径**
 * 执行 SPA fallback，返回 `<!doctype html>...` 页面（HTTP 200）而非 JSON 404。
 * 若客户端误判 API 版本（V1 被当成 V2），V2ApiClient 请求不存在的 `/api/...` 路径，
 * 就会拿到 HTML → 无防护的 `parseToJsonElement` 会抛难以理解的
 * `JsonDecodingException: Unexpected JSON token at offset 11...`（用户报错原样）。
 *
 * 统一抛出本异常，携带端点上下文与响应预览，便于用户/日志定位。
 */
class NonJsonResponseException(
    message: String,
) : Exception(message)

/**
 * HTML 响应防御（D2-22/#121，2026-08-19 提取公共）：服务器 SPA fallback
 * 会把不存在的 API 路径返回为 HTML 页面（HTTP 200）。在 JSON 解析前
 * 检测 HTML 特征，抛 [NonJsonResponseException]（而非难懂的
 * JsonDecodingException）。触发通常是 API 版本误判（V1 服务器被当 V2
 * 请求 /api/... 或反之）。
 *
 * [logTag] 非空时额外打 ERROR 日志（预览 120 字符）。
 */
fun rejectHtmlResponse(bodyText: String, logTag: String? = null) {
    val trimmed = bodyText.trimStart()
    if (trimmed.startsWith("<!doctype html", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
        val preview = trimmed.take(120).replace('\n', ' ')
        if (logTag != null) {
            dev.leonardo.ocbeacon.logging.AppLogger.e(logTag, "Non-JSON (HTML) response from server: $preview")
        }
        throw NonJsonResponseException("服务器返回了 HTML 页面而非 JSON（API 路径可能不存在或版本不匹配）：$preview")
    }
}
