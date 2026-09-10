package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.adapter.ConnectionStatus
import dev.leonardo.ocbeacon.data.adapter.ConnectionStrategy
import dev.leonardo.ocbeacon.data.adapter.Handshake
import dev.leonardo.ocbeacon.data.adapter.WireKind
import dev.leonardo.ocbeacon.data.api.dsh.DshProbeOutcome
import dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource
import dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * DSH 连接策略（#391 切片6）——多路复用 WS 传输。
 *
 * 握手走 [DshProtocolSource] 的双形态探测（0.1.2 斜杠端点 + args 包装 / 0.1.1 点式），
 * 判别 {线面世代 × 鉴权态}：TokenNeeded → AUTH_REQUIRED、Unreachable → UNREACHABLE、
 * Online → ONLINE。未探测（协议源无探针）保守 V011 并标记 degraded。
 */
class DshConnectionStrategy(
    private val protocolSource: DshProtocolSource,
) : ConnectionStrategy {

    override val wireKind: WireKind = WireKind.MUX

    override suspend fun probe(conn: ServerConnection): Handshake = when (
        val outcome = protocolSource.ensureProbed(conn.baseUrl)
    ) {
        is DshProbeOutcome.Online -> Handshake(
            status = ConnectionStatus.ONLINE,
            wireGeneration = generation(outcome.protocol),
            authenticated = outcome.authenticated,
        )

        is DshProbeOutcome.TokenNeeded -> Handshake(
            status = ConnectionStatus.AUTH_REQUIRED,
            wireGeneration = generation(DshWireProtocol.V012),
            authenticated = false,
            detail = "token required",
        )

        is DshProbeOutcome.Unreachable -> Handshake(
            status = ConnectionStatus.UNREACHABLE,
            wireGeneration = generation(protocolSource.protocolOf(conn.baseUrl)),
            authenticated = false,
            degraded = protocolSource.protocolOf(conn.baseUrl) == null,
            detail = outcome.detail,
        )
    }

    private fun generation(protocol: DshWireProtocol?): String = when (protocol) {
        DshWireProtocol.V012 -> WIRE_V012
        DshWireProtocol.V011, null -> WIRE_V011
    }

    companion object {
        const val WIRE_V011 = "v011"
        const val WIRE_V012 = "v012"
    }
}
