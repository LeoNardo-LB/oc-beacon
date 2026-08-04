package dev.leonardo.ocbeacon.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 所有领域 API 实现共享的 HTTP 客户端 + JSON 序列化器持有者。
 *
 * 每个领域 `*ApiImpl` 注入此类以访问在
 * [dev.leonardo.ocbeacon.di.NetworkModule] 中配置的 Ktor [httpClient] 和 [json]。
 * 将两者放在这里可避免每个实现重复相同的构造函数依赖。
 */
@Singleton
class ApiClient @Inject constructor(
    val httpClient: HttpClient,
    val json: Json
)

/**
 * 当 [directory] 非空时，为请求附加 `x-opencode-directory` 头。
 *
 * 由所有领域 API 实现共享——从原始单体 API 类的
 * `directoryHeader` 私有扩展中原样抽取，以便方法体可以不经修改地迁移。
 */
internal fun HttpRequestBuilder.directoryHeader(directory: String?) {
    directory?.let { header("x-opencode-directory", URLEncoder.encode(it, "UTF-8")) }
}
