package dev.leonardo.ocbeacon.data.api

import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

/**
 * #569（2026-10-30 定案修正）：发送链 REST 调用的 per-request 超时。
 *
 * 机制事实（Ktor 3.5.2 反汇编实证，journal 2026-10-30 §#569）：
 * - OkHttp engine 的 `setupTimeoutAttributes` 只把 connect/socket 两项映射到
 *   OkHttpClient（connectTimeout/readTimeout+writeTimeout）；`requestTimeoutMillis`
 *   不进 engine 配置——engine 层它是死配置；
 * - 但 HttpTimeout 插件在 Send 管线自装 request-timeout killer（到点取消请求
 *   执行上下文，抛 HttpRequestTimeoutException），对非 WS / 非 SSEClientContent
 *   请求生效——即 requestTimeout 在本引擎下依旧有效，由插件层实施；
 * - SSE 客户端以 `requestTimeoutMillis = Long.MAX_VALUE` 显式豁免 killer
 *   （长流不限时——浸泡 18min/300K 字符流不受影响）。
 *
 * 浸泡 A-10（265s+ 挂起）根因：V2 发送链 = switchModel → switchAgent
 * （runCatching 吞超时）→ prompt 三串行调用，各吃 client 级 120s
 * requestTimeout——被吞的 120s + 最终调用的 120s ≈ 265s 后才见失败框。
 * 本 helper 把发送链每调用压到 30s，配合 ChatSendDelegate 的链级死线
 * （[dev.leonardo.ocbeacon.ui.screens.chat.SEND_CHAIN_DEADLINE_MS]）保证
 * 「冻结服务器下发送 ≤65s 必出失败反馈」。
 */
internal const val SEND_CHAIN_CALL_TIMEOUT_MS: Long = 30_000L
internal const val SEND_CHAIN_CONNECT_TIMEOUT_MS: Long = 10_000L

/**
 * 发送链 REST 调用统一超时（#569）：requestTimeout 30s（插件 killer，总时长上限）
 * + connect 10s + socket 30s（engine 映射，读空闲上限）。
 *
 * 适用范围：用户点击「发送」后同步等待受理的短 REST 调用（prompt 受理 /
 * model/agent 切换）。不适用于：SSE 流、导出流、大文件传输——那些有自己的
 * 超时策略（SSE = MAX_VALUE + socket 行级；导出 = ExportOkHttpClient 120s）。
 */
internal fun HttpRequestBuilder.sendChainTimeout() {
    timeout {
        requestTimeoutMillis = SEND_CHAIN_CALL_TIMEOUT_MS
        connectTimeoutMillis = SEND_CHAIN_CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SEND_CHAIN_CALL_TIMEOUT_MS
    }
}
