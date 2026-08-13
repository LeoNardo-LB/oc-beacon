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
