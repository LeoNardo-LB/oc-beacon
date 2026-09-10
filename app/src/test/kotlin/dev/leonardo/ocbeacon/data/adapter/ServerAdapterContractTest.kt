package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource
import dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.testing.FakeServerAdapter
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片8：适配器契约测试——**同一套断言跑遍每个已注册适配器 + 一个假适配器**。
 *
 * 该 seam 成立即证明"新类型即插即用、上层无感"：上层只需要 ServerAdapterResolver
 * 暴露的能力位 / 世代 / 插槽声明，不接触端口实现或服务器类型。
 */
class ServerAdapterContractTest {

    private val protocolSource = object : DshProtocolSource {
        override fun protocolOf(baseUrl: String): DshWireProtocol? = null
    }

    /** 真实适配器：每类一个实例（覆盖全部 ServerType）。 */
    private val real = ServerAdapterRegistry(
        setOf(
            OpenCodeServerAdapter(mockk<V1ApiClient>(relaxed = true), mockk<V2ApiClient>(relaxed = true)),
            DshServerAdapter(mockk<DshApiClient>(relaxed = true), protocolSource),
        )
    )

    /** 假适配器：与真实适配器共用同一套契约断言。 */
    private val withFake = ServerAdapterRegistry(
        setOf(
            OpenCodeServerAdapter(mockk<V1ApiClient>(relaxed = true), mockk<V2ApiClient>(relaxed = true)),
            FakeServerAdapter(
                ServerType.Dsh,
                ServerPorts(
                    session = mockk(relaxed = true),
                    message = mockk(relaxed = true),
                    system = mockk(relaxed = true),
                ),
            ),
        )
    )

    private fun conn(type: ServerType, version: ApiVersion = ApiVersion.V1) =
        ServerConnection("http://127.0.0.1:4096", null, version, type)

    private fun assertContract(registry: ServerAdapterRegistry) {
        // 1) 覆盖全部类型（唯一共享触点），无重复由构造期校验兜底
        assertEquals(ServerType.entries.toSet(), registry.supportedTypes())

        for (type in ServerType.entries) {
            val c = conn(type)
            val adapter = registry.adapterFor(c)
            assertEquals(type, adapter.type)

            // 2) 能力 = 核心标志 + 端口可选性派生 + 适配器私有声明（唯一真相 = 端口在场）
            val caps = registry.capabilities(c)
            val ports = registry.ports(c)
            assertEquals(registry.coreFlags(c), caps.coreFlags)
            assertEquals(ports.derivedFeatures() + registry.privateFeatures(c), caps.features)

            // 3) 世代 id 非空；连接策略与适配器声明一致
            assertTrue(registry.wireGeneration(c).isNotBlank())
            assertNotNull(registry.connectionStrategy(c))
            assertNotNull(registry.uiSlots(c))

            // 4) 端口缺席 => 不产生对应能力
            if (ports.terminal == null) assertFalse(caps.features.contains(dev.leonardo.ocbeacon.domain.model.ServerFeatures.TERMINAL))
            if (ports.shell == null) assertFalse(caps.features.contains(dev.leonardo.ocbeacon.domain.model.ServerFeatures.SHELL))
            if (ports.queue == null) assertFalse(caps.features.contains(dev.leonardo.ocbeacon.domain.model.ServerFeatures.QUEUE))
            if (ports.goals == null) assertFalse(caps.features.contains(dev.leonardo.ocbeacon.domain.model.ServerFeatures.GOALS))
        }
    }

    @Test
    fun `every registered adapter satisfies the contract`() {
        assertContract(real)
    }

    @Test
    fun `a fake adapter satisfies the same contract (plug-and-play)`() {
        assertContract(withFake)
    }

    @Test
    fun `absent optional port reads empty and throws on required access`() {
        val dshConn = conn(ServerType.Dsh)
        val ports = real.ports(dshConn)
        // DSH 无终端 / shell 端口：读路径拿到 null，强制取值显式失败
        assertNull(ports.terminal)
        assertNull(ports.shell)
        val thrown = assertThrows(UnsupportedServerCapability::class.java) { ports.requireTerminal(dshConn) }
        assertTrue(thrown.message.orEmpty().contains("terminal"))
    }

    @Test
    fun `dsh adapter declares the provider settings slot`() {
        assertTrue(real.uiSlots(conn(ServerType.Dsh)).contains(dev.leonardo.ocbeacon.domain.model.ServerUiSlot.PROVIDER_SETTINGS))
        assertTrue(real.uiSlots(conn(ServerType.OpenCode)).isEmpty())
    }
}
