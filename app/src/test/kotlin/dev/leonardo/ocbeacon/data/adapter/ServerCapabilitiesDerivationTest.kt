package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource
import dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol
import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #391 切片 2：能力派生的等价网。
 *
 * 逐位断言「核心行为标志 + 端口派生 + 适配器声明」的结果与迁移前的集中矩阵一致
 * （OpenCode V1/V2/UNKNOWN、DSH），并断言端口缺席语义不产生能力。
 */
class ServerCapabilitiesDerivationTest {

    private val registry = ServerAdapterRegistry(
        setOf(
            OpenCodeServerAdapter(mockk<V1ApiClient>(relaxed = true), mockk<V2ApiClient>(relaxed = true)),
            DshServerAdapter(
                mockk<DshApiClient>(relaxed = true),
                object : DshProtocolSource {
                    override fun protocolOf(baseUrl: String): DshWireProtocol? = DshWireProtocol.V012
                },
            ),
        )
    )

    private fun caps(type: ServerType, version: ApiVersion): ServerCapabilities =
        registry.capabilities(ServerConnection("http://srv", null, version, type))

    private fun has(caps: ServerCapabilities, feature: ServerFeature) = feature in caps

    private fun assertOpenCodeCommon(caps: ServerCapabilities) {
        assertTrue(has(caps, ServerFeatures.TERMINAL))
        assertTrue(has(caps, ServerFeatures.SHELL))
        assertTrue(has(caps, ServerFeatures.COMMANDS))
        assertTrue(has(caps, ServerFeatures.FILE_READ))
        assertTrue(has(caps, ServerFeatures.VCS))
        assertTrue(has(caps, ServerFeatures.FILE_SEARCH))
        assertTrue(has(caps, ServerFeatures.SESSION_DELETE))
        assertTrue(has(caps, ServerFeatures.SESSION_REVERT))
        assertFalse(has(caps, ServerFeatures.PERMISSION_SWITCH))
        assertFalse(has(caps, ServerFeatures.AGENT_PRESET))
        assertFalse(has(caps, ServerFeatures.GOALS))
        assertFalse(has(caps, ServerFeatures.FEEDBACK))
        assertFalse(has(caps, ServerFeatures.SESSION_ARCHIVE))
        assertFalse(has(caps, ServerFeatures.WORKSPACE))
        // 端口在场性决定私有能力（四类新端口 + 设置特权面仅 DSH 挂载）
        assertFalse(has(caps, ServerFeatures.SUBAGENTS))
        assertFalse(has(caps, ServerFeatures.SERVER_SETTINGS))
    }

    @Test
    fun `openCode V1 bits match the legacy matrix`() {
        val c = caps(ServerType.OpenCode, ApiVersion.V1)
        assertOpenCodeCommon(c)
        assertTrue(has(c, ServerFeatures.SESSION_SHARE))
        assertFalse(has(c, ServerFeatures.SESSION_BACKGROUND))
        assertFalse(has(c, ServerFeatures.QUEUE))
        assertFalse(has(c, ServerFeatures.QUEUE_EDIT))
        assertEquals(CoreFlags(false, false, false, true), c.coreFlags)
    }

    @Test
    fun `openCode V2 bits match the legacy matrix`() {
        val c = caps(ServerType.OpenCode, ApiVersion.V2)
        assertOpenCodeCommon(c)
        assertFalse(has(c, ServerFeatures.SESSION_SHARE))
        assertTrue(has(c, ServerFeatures.SESSION_BACKGROUND))
        assertTrue(has(c, ServerFeatures.QUEUE))
        assertFalse(has(c, ServerFeatures.QUEUE_EDIT))
        assertEquals(CoreFlags(true, false, false, false), c.coreFlags)
    }

    @Test
    fun `openCode unknown version stays on V1 behaviour`() {
        val c = caps(ServerType.OpenCode, ApiVersion.UNKNOWN)
        assertOpenCodeCommon(c)
        assertTrue(has(c, ServerFeatures.SESSION_SHARE))
        assertFalse(has(c, ServerFeatures.SESSION_BACKGROUND))
        assertFalse(has(c, ServerFeatures.QUEUE))
        assertEquals(CoreFlags(false, false, false, true), c.coreFlags)
    }

    @Test
    fun `dsh bits match the legacy matrix and lack absent ports`() {
        val c = caps(ServerType.Dsh, ApiVersion.V1)
        assertTrue(has(c, ServerFeatures.COMMANDS))
        assertTrue(has(c, ServerFeatures.GOALS))
        assertTrue(has(c, ServerFeatures.FEEDBACK))
        assertTrue(has(c, ServerFeatures.PERMISSION_SWITCH))
        assertTrue(has(c, ServerFeatures.AGENT_PRESET))
        assertTrue(has(c, ServerFeatures.SESSION_ARCHIVE))
        assertTrue(has(c, ServerFeatures.WORKSPACE))
        assertTrue(has(c, ServerFeatures.QUEUE))
        assertTrue(has(c, ServerFeatures.QUEUE_EDIT))
        assertTrue(has(c, ServerFeatures.SUBAGENTS))
        assertTrue(has(c, ServerFeatures.SERVER_SETTINGS))
        // 端口缺席 ⇒ 无能力（终端 / shell）
        assertFalse(has(c, ServerFeatures.TERMINAL))
        assertFalse(has(c, ServerFeatures.SHELL))
        // 既有子能力
        assertFalse(has(c, ServerFeatures.SESSION_SHARE))
        assertFalse(has(c, ServerFeatures.SESSION_BACKGROUND))
        assertFalse(has(c, ServerFeatures.SESSION_DELETE))
        assertFalse(has(c, ServerFeatures.SESSION_REVERT))
        assertFalse(has(c, ServerFeatures.FILE_READ))
        assertFalse(has(c, ServerFeatures.VCS))
        assertFalse(has(c, ServerFeatures.FILE_SEARCH))
        // 核心行为标志
        assertEquals(CoreFlags(true, true, true, false), c.coreFlags)
    }

    @Test
    fun `default capabilities follow the default server type V1 semantics`() {
        val c = registry.defaultCapabilities()
        assertTrue(has(c, ServerFeatures.TERMINAL))
        assertTrue(has(c, ServerFeatures.SESSION_SHARE))
        assertTrue(c.coreFlags.configEditable)
        assertFalse(has(c, ServerFeatures.GOALS))
    }

    @Test
    fun `capabilities are computed per connection not per adapter instance`() {
        val cV1 = caps(ServerType.OpenCode, ApiVersion.V1)
        val cV2 = caps(ServerType.OpenCode, ApiVersion.V2)
        assertTrue(has(cV1, ServerFeatures.SESSION_SHARE))
        assertFalse(has(cV2, ServerFeatures.SESSION_SHARE))
        assertFalse(has(cV1, ServerFeatures.QUEUE))
        assertTrue(has(cV2, ServerFeatures.QUEUE))
    }
}
