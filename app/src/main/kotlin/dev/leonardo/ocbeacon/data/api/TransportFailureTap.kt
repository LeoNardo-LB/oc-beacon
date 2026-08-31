package dev.leonardo.ocbeacon.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #267（spec §3.3 检测滞后补刀）：HTTP 传输层失败上拍。
 *
 * 共享 HttpClient 的 Send 管线拦截器在 IOException 族抛出（连接拒绝/超时/
 * UnknownHost——OkHttp `retryOnConnectionFailure` 耗尽后）时上报请求 origin
 * （scheme://host:port）；SseConnectionManager 接线映射回 serverId 并踢重连
 * ——SSE 读循环可能仍阻塞在半开 TCP 上（OS 超时前无感知），不等它。
 *
 * Holder 形态解依赖环：NetworkModule/ApiClient 无法反向注入 manager
 * （manager → *ApiImpl → ApiClient → HttpClient，若 HttpClient 直依赖 manager
 * 即成环），故经此可变回调中转，由 manager init 接线。
 */
@Singleton
class TransportFailureTap @Inject constructor() {
    @Volatile
    var reportFailure: ((origin: String) -> Unit)? = null

    fun onTransportFailure(origin: String) {
        reportFailure?.invoke(origin)
    }
}

/**
 * 在已构建的 [HttpClient] 上安装传输失败拦截（#267）。
 *
 * 挂 **request 管线 Before 相位** try/proceed——对齐 Ktor 自家 RequestError 钩子
 * 的挂点（HttpCallValidator.kt：引擎异常从整条管线最外层冒泡；实测 Send/
 * Engine 相位 proceed 链上不经过）。Before 相位 subject = [HttpRequestBuilder]。
 * 响应体流式读错误发生在管线之外，不在拦截范围——那是调用方既有错误处理域。
 */
fun HttpClient.installTransportFailureTap(tap: TransportFailureTap) {
    requestPipeline.intercept(HttpRequestPipeline.Before) {
        try {
            proceed()
        } catch (e: IOException) {
            val url = context.url.build()
            tap.onTransportFailure(url.protocol.name + "://" + url.host + ":" + url.port)
            throw e
        }
    }
}
