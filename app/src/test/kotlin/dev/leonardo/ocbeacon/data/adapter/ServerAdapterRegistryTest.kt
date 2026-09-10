package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.shell.ShellApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource
import dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片 1：适配器契约 seam 测试。
 *
 * 断言注册表构造期覆盖/重复校验、按连接解析、端口在场性与世代映射、核心行为标志。
 * 只断言外部行为（契约），不碰实现细节。
 */
class ServerAdapterRegistryTest {

    private fun ports(
        terminal: TerminalApi? = mockk(),
        shell: ShellApi? = mockk(),
        file: FileApi? = mockk(),
        provider: ProviderApi? = mockk(),
    ): ServerPorts = ServerPorts(
        session = mockk<SessionApi>(),
        message = mockk<MessageApi>(),
        system = mockk<SystemApi>(),
        file = file,
        provider = provider,
        terminal = terminal,
        shell = shell,
    )

    private fun fake(
        type: ServerType,
        ports: ServerPorts = ports(),
        wire: String = "fake",
    ): ServerAdapter = object : ServerAdapter {
        override val type = type
        override fun wireGeneration(conn: ServerConnection) = wire
        override fun ports(conn: ServerConnection) = ports
        override fun coreFlags(conn: ServerConnection) = CoreFlags(false, false, false, false)
    }

    private fun conn(type: ServerType, version: ApiVersion = ApiVersion.V1) =
        ServerConnection("http://127.0.0.1:4096", null, version, type)

    // ---- 注册表校验 --------------------------------------------------------

    @Test
    fun `duplicate registration is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAdapterRegistry(
                setOf(
                    fake(ServerType.OpenCode),
                    fake(ServerType.OpenCode),
                    fake(ServerType.Dsh),
                )
            )
        }
    }

    @Test
    fun `missing coverage is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerAdapterRegistry(setOf(fake(ServerType.OpenCode)))
        }
    }

    @Test
    fun `registry resolves adapter and ports per connection`() {
        val ocPorts = ports()
        val dshPorts = ports(terminal = null, shell = null)
        val oc = fake(ServerType.OpenCode, ocPorts)
        val dsh = fake(ServerType.Dsh, dshPorts)
        val registry = ServerAdapterRegistry(setOf(oc, dsh))

        assertEquals(setOf(ServerType.OpenCode, ServerType.Dsh), registry.supportedTypes())
        assertSame(oc, registry.adapterFor(conn(ServerType.OpenCode)))
        assertSame(dsh, registry.adapterFor(conn(ServerType.Dsh)))
        assertSame(ocPorts, registry.ports(conn(ServerType.OpenCode)))
        assertSame(dshPorts, registry.ports(conn(ServerType.Dsh)))
    }

    @Test
    fun `verifyComplete passes for full registration`() {
        ServerAdapterRegistry(
            setOf(fake(ServerType.OpenCode), fake(ServerType.Dsh))
        ).verifyComplete()
    }

    // ---- 端口在场性 = 能力唯一真相 -----------------------------------------

    @Test
    fun `derived features follow port presence`() {
        val full = ports().derivedFeatures()
        assertTrue(full.containsAll(ServerFeatures.let {
            listOf(it.SESSION, it.MESSAGES, it.SYSTEM, it.FILES, it.PROVIDERS, it.TERMINAL, it.SHELL)
        }))

        val noTerminalNoShell = ports(terminal = null, shell = null).derivedFeatures()
        assertFalse(noTerminalNoShell.contains(ServerFeatures.TERMINAL))
        assertFalse(noTerminalNoShell.contains(ServerFeatures.SHELL))
        assertTrue(noTerminalNoShell.contains(ServerFeatures.FILES))
        assertTrue(noTerminalNoShell.contains(ServerFeatures.PROVIDERS))
    }

    @Test
    fun `dsh adapter exposes no terminal or shell ports`() {
        val adapter = DshServerAdapter(mockk<DshApiClient>(relaxed = true), FakeProtocolSource(null))
        val p = adapter.ports(conn(ServerType.Dsh))
        assertNull(p.terminal)
        assertNull(p.shell)
        assertTrue(p.session is DshApiClient)
        assertFalse(p.derivedFeatures().contains(ServerFeatures.TERMINAL))
        assertFalse(p.derivedFeatures().contains(ServerFeatures.SHELL))
    }

    @Test
    fun `openCode adapter exposes all ports per version`() {
        val v1 = mockk<V1ApiClient>()
        val v2 = mockk<V2ApiClient>()
        val adapter = OpenCodeServerAdapter(v1, v2)

        val p1 = adapter.ports(conn(ServerType.OpenCode, ApiVersion.V1))
        assertSame(v1, p1.session)
        assertSame(v1, p1.terminal)
        assertSame(v1, p1.shell)
        assertEquals(OpenCodeServerAdapter.WIRE_V1, adapter.wireGeneration(conn(ServerType.OpenCode, ApiVersion.V1)))

        val p2 = adapter.ports(conn(ServerType.OpenCode, ApiVersion.V2))
        assertSame(v2, p2.session)
        assertSame(v2, p2.terminal)
        assertEquals(OpenCodeServerAdapter.WIRE_V2, adapter.wireGeneration(conn(ServerType.OpenCode, ApiVersion.V2)))
    }

    @Test
    fun `openCode unknown version stays on v1 behavior`() {
        val v1 = mockk<V1ApiClient>()
        val v2 = mockk<V2ApiClient>()
        val adapter = OpenCodeServerAdapter(v1, v2)
        val c = conn(ServerType.OpenCode, ApiVersion.UNKNOWN)
        assertSame(v1, adapter.ports(c).session)
        assertEquals(OpenCodeServerAdapter.WIRE_V1, adapter.wireGeneration(c))
        assertTrue(adapter.coreFlags(c).configEditable)
        assertFalse(adapter.coreFlags(c).compactionAsync)
    }

    @Test
    fun `openCode core flags per version`() {
        val adapter = OpenCodeServerAdapter(mockk(), mockk())
        val v1 = adapter.coreFlags(conn(ServerType.OpenCode, ApiVersion.V1))
        assertTrue(v1.configEditable)
        assertFalse(v1.compactionAsync)

        val v2 = adapter.coreFlags(conn(ServerType.OpenCode, ApiVersion.V2))
        assertFalse(v2.configEditable)
        assertTrue(v2.compactionAsync)
        assertFalse(v2.exportIsArchive)
    }

    @Test
    fun `dsh core flags are server native semantics`() {
        val adapter = DshServerAdapter(mockk(relaxed = true), FakeProtocolSource(null))
        val flags = adapter.coreFlags(conn(ServerType.Dsh))
        assertTrue(flags.compactionAsync)
        assertTrue(flags.compactionModelIndependent)
        assertTrue(flags.exportIsArchive)
        assertFalse(flags.configEditable)
    }

    @Test
    fun `dsh wire generation follows probe state conservatively`() {
        val dsh = mockk<DshApiClient>(relaxed = true)
        val c = conn(ServerType.Dsh)

        assertEquals(
            DshServerAdapter.WIRE_V011,
            DshServerAdapter(dsh, FakeProtocolSource(null)).wireGeneration(c),
        )
        assertEquals(
            DshServerAdapter.WIRE_V011,
            DshServerAdapter(dsh, FakeProtocolSource(DshWireProtocol.V011)).wireGeneration(c),
        )
        assertEquals(
            DshServerAdapter.WIRE_V012,
            DshServerAdapter(dsh, FakeProtocolSource(DshWireProtocol.V012)).wireGeneration(c),
        )
    }

    private class FakeProtocolSource(private val protocol: DshWireProtocol?) : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = protocol
    }
}
