package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

/**
 * #114（D2-27）：认证头统一扩展——替代 147 处内联
 * `conn.authHeader?.let { header("Authorization", it) }`。
 *
 * 不配置全局 Auth 插件：认证是**每服务器**属性（ServerConnection.authHeader
 * 由各服务器配置生成），而 HttpClient 是全局单例——插件级静态配置无法
 * 表达多服务器。统一走本扩展：调用方显式传 conn，认证演进只需改一处。
 *
 * 用法：`httpClient.get(url) { auth(conn); ... }`
 */
fun HttpRequestBuilder.auth(conn: ServerConnection): HttpRequestBuilder {
    conn.authHeader?.let { header("Authorization", it) }
    return this
}