package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * OpenCode 连接策略（#391 切片6）——SSE 传输。
 *
 * 握手是**纯投影**：ApiVersionDetector 的双探（/api/health 与 /global/health，含 JSON
 * 内容型与 version/pid 交叉校验）已在连接建立前的健康检查中完成并持久化到
 * ServerConnection.apiVersion，本策略把它翻译为统一握手产物，不重复往返。
 */
class OpenCodeConnectionStrategy : ConnectionStrategy {

    override val wireKind: WireKind = WireKind.SSE

    override suspend fun probe(conn: ServerConnection): Handshake {
        val generation = if (conn.apiVersion.isV2) {
            OpenCodeServerAdapter.WIRE_V2
        } else {
            OpenCodeServerAdapter.WIRE_V1
        }
        // UNKNOWN：探测失败回落 V1 基线并标记降级（不冒充已确认）
        val degraded = conn.apiVersion == ApiVersion.UNKNOWN
        return Handshake(
            status = ConnectionStatus.ONLINE,
            wireGeneration = generation,
            authenticated = true,
            degraded = degraded,
            detail = if (degraded) "apiVersion UNKNOWN — V1 baseline, unverified" else null,
        )
    }
}
