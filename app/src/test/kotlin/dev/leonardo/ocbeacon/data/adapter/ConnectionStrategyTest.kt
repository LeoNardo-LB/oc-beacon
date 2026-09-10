package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.adapter.dsh.DshConnectionStrategy
import dev.leonardo.ocbeacon.data.api.dsh.DshProbeOutcome
import dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource
import dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.testing.FakeServerAdapter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片6：连接策略契约——传输种类 + 一次握手的三态映射与降级语义。
 */
class ConnectionStrategyTest {

    private fun openCode(version: ApiVersion) =
        ServerConnection("http://srv", null, version, ServerType.OpenCode)

    private fun dsh() = ServerConnection("http://srv", null, ApiVersion.V1, ServerType.Dsh)

    private class FakeSource(
        private val protocol: DshWireProtocol?,
        private val outcome: DshProbeOutcome,
    ) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
        override suspend fun ensureProbed(authority: String): DshProbeOutcome = outcome
    }

    @Test
    fun `openCode strategy is SSE and projects persisted version`() = runTest {
        val strategy = OpenCodeConnectionStrategy()
        assertEquals(WireKind.SSE, strategy.wireKind)

        val v2 = strategy.probe(openCode(ApiVersion.V2))
        assertEquals(ConnectionStatus.ONLINE, v2.status)
        assertEquals(OpenCodeServerAdapter.WIRE_V2, v2.wireGeneration)
        assertFalse(v2.degraded)
        assertNull(v2.detail)

        val v1 = strategy.probe(openCode(ApiVersion.V1))
        assertEquals(OpenCodeServerAdapter.WIRE_V1, v1.wireGeneration)
        assertFalse(v1.degraded)
    }

    @Test
    fun `openCode unknown version falls back to V1 baseline marked degraded`() = runTest {
        val handshake = OpenCodeConnectionStrategy().probe(openCode(ApiVersion.UNKNOWN))
        assertEquals(OpenCodeServerAdapter.WIRE_V1, handshake.wireGeneration)
        assertTrue(handshake.degraded)
    }

    @Test
    fun `dsh strategy is MUX and maps probe outcomes to handshake states`() = runTest {
        val online = DshConnectionStrategy(
            FakeSource(DshWireProtocol.V012, DshProbeOutcome.Online(DshWireProtocol.V012, authenticated = true))
        )
        assertEquals(WireKind.MUX, online.wireKind)
        val h1 = online.probe(dsh())
        assertEquals(ConnectionStatus.ONLINE, h1.status)
        assertEquals(DshConnectionStrategy.WIRE_V012, h1.wireGeneration)
        assertTrue(h1.authenticated)

        val token = DshConnectionStrategy(FakeSource(null, DshProbeOutcome.TokenNeeded))
        val h2 = token.probe(dsh())
        assertEquals(ConnectionStatus.AUTH_REQUIRED, h2.status)
        assertFalse(h2.authenticated)
        assertEquals(DshConnectionStrategy.WIRE_V012, h2.wireGeneration)

        val down = DshConnectionStrategy(FakeSource(null, DshProbeOutcome.Unreachable("boom")))
        val h3 = down.probe(dsh())
        assertEquals(ConnectionStatus.UNREACHABLE, h3.status)
        assertTrue(h3.degraded)
        assertEquals("boom", h3.detail)
    }

    @Test
    fun `dsh unreachable with known V011 baseline is not marked degraded`() = runTest {
        val strategy = DshConnectionStrategy(
            FakeSource(DshWireProtocol.V011, DshProbeOutcome.Unreachable("boom"))
        )
        val handshake = strategy.probe(dsh())
        assertEquals(ConnectionStatus.UNREACHABLE, handshake.status)
        assertEquals(DshConnectionStrategy.WIRE_V011, handshake.wireGeneration)
        assertFalse(handshake.degraded)
    }

    @Test
    fun `adapters expose the strategy matching their transport`() {
        val registry = ServerAdapterRegistry(
            setOf(
                FakeServerAdapter(ServerType.OpenCode, ServerPorts(
                    session = io.mockk.mockk(relaxed = true),
                    message = io.mockk.mockk(relaxed = true),
                    system = io.mockk.mockk(relaxed = true),
                )),
                FakeServerAdapter(
                    ServerType.Dsh,
                    ServerPorts(
                        session = io.mockk.mockk(relaxed = true),
                        message = io.mockk.mockk(relaxed = true),
                        system = io.mockk.mockk(relaxed = true),
                    ),
                    strategy = DshConnectionStrategy(FakeSource(null, DshProbeOutcome.Unreachable("x"))),
                ),
            )
        )
        assertEquals(WireKind.SSE, registry.connectionStrategy(openCode(ApiVersion.V1)).wireKind)
        assertEquals(WireKind.MUX, registry.connectionStrategy(dsh()).wireKind)
    }
}
