package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.domain.model.ServerConnection

/** 传输线面种类（#391 切片6）：粗粒度连接策略身份。 */
enum class WireKind {
    /** 单向 SSE 事件流（OpenCode V1/V2）。 */
    SSE,

    /** 多路复用 WS（DSH）。 */
    MUX,
}

/** 握手状态（#391 切片6）：三态分类，权限栅栏不得当作"不存在"。 */
enum class ConnectionStatus {
    /** 可达且已鉴权，可进事件流。 */
    ONLINE,

    /** 可达但需凭据（token / cookie 失效）——调用方等凭据后回环重探。 */
    AUTH_REQUIRED,

    /** 传输不可达 / 协议异常——调用方走既有退避。 */
    UNREACHABLE,
}

/**
 * 一次握手的产物（#391 切片6）：线面世代 + 鉴权态 + 探测状态。
 *
 * 降级语义：[degraded] = 探测不可用、回落适配器静态基线；调用方按"未知"呈现，
 * 不翻成"不支持"，也不假设全支持。
 */
data class Handshake(
    val status: ConnectionStatus,
    /** 线面世代纯 id（与 ServerAdapter.wireGeneration 同源）。 */
    val wireGeneration: String,
    val authenticated: Boolean,
    val degraded: Boolean = false,
    val detail: String? = null,
)

/**
 * 连接策略契约（#391 切片6）——粗粒度：一次握手 + 运行期传输/认证/重连/保活/事件源。
 *
 * 边界铁律：**不触碰平台服务生命周期**（前台服务、通知、Activity）——那些由连接监督层
 * 从回调驱动。共享实现：SSE（OpenCode V1/V2）与多路复用（DSH）。
 */
interface ConnectionStrategy {

    /** 传输线面种类（监督层据此选事件循环，不读服务器类型）。 */
    val wireKind: WireKind

    /**
     * 一次握手：版本 + 鉴权 + 探测状态。
     *
     * OpenCode：投影已持久化的探测结果（ApiVersionDetector 双探在连接建立前的健康检查
     * 中已完成，结果落在 ServerConnection.apiVersion）。
     * DSH：双形态探测（版本 × 鉴权），未探测保守 V011 且标记 degraded。
     */
    suspend fun probe(conn: ServerConnection): Handshake
}
