package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSH 能力位矩阵快照测试（backlog #276 步骤②；设计文档 §2.2）。
 *
 * DSH 分支：现有五个能力位全置 false（share/todo 面缺失、background/active
 * 域缺失、settings 特权面 UI 不开放、compaction 域缺失）。旧签名
 * of(apiVersion) 必须保持原 V1/V2 语义不变（既有调用方兼容）。
 *
 * #276 UI 卡（2026-08-31）：六新位（terminal/fileRead/sessionDelete/vcs/
 * fileSearch/commands）——DSH 全 false（§2.6 终局确认），OpenCode V1/V2/UNKNOWN
 * 全 true（两代端点均存在）。
 */
class ServerCapabilitiesDshTest {

    /** #276 UI 卡新增六位（缺口清单补位）。 */
    private val newBits: List<kotlin.reflect.KProperty1<ServerCapabilities, Boolean>> = listOf(
        ServerCapabilities::terminalSupported,
        ServerCapabilities::fileReadSupported,
        ServerCapabilities::sessionDeleteSupported,
        ServerCapabilities::vcsSupported,
        ServerCapabilities::fileSearchSupported,
        ServerCapabilities::commandsSupported,
    )

    @Test
    fun `dsh matrix is all false regardless of apiVersion`() {
        for (version in ApiVersion.entries) {
            val caps = ServerCapabilities.of(ServerType.Dsh, version)
            assertFalse("shareSupported v=$version", caps.shareSupported)
            assertFalse("backgroundSessionsSupported v=$version", caps.backgroundSessionsSupported)
            assertFalse("runningSessionsFilterSupported v=$version", caps.runningSessionsFilterSupported)
            assertFalse("configEditable v=$version", caps.configEditable)
            assertFalse("compactionAsync v=$version", caps.compactionAsync)
            for (bit in newBits) {
                assertFalse("${bit.name} v=$version", bit.get(caps))
            }
        }
    }

    @Test
    fun `dsh new ui-gating bits are all false`() {
        val caps = ServerCapabilities.of(ServerType.Dsh, ApiVersion.V1)
        assertFalse(caps.terminalSupported)
        assertFalse(caps.fileReadSupported)
        assertFalse(caps.sessionDeleteSupported)
        assertFalse(caps.vcsSupported)
        assertFalse(caps.fileSearchSupported)
        assertFalse(caps.commandsSupported)
    }

    @Test
    fun `opencode branch keeps legacy v1 semantics`() {
        val caps = ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V1)
        assertTrue(caps.shareSupported)
        assertFalse(caps.backgroundSessionsSupported)
        assertFalse(caps.runningSessionsFilterSupported)
        assertTrue(caps.configEditable)
        assertFalse(caps.compactionAsync)
        newBits.forEach { assertTrue(it.name, it.get(caps)) }
    }

    @Test
    fun `opencode branch keeps legacy v2 semantics`() {
        val caps = ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V2)
        assertFalse(caps.shareSupported)
        assertTrue(caps.backgroundSessionsSupported)
        assertTrue(caps.runningSessionsFilterSupported)
        assertFalse(caps.configEditable)
        assertTrue(caps.compactionAsync)
        newBits.forEach { assertTrue(it.name, it.get(caps)) }
    }

    @Test
    fun `opencode null version stays permissive`() {
        val caps = ServerCapabilities.of(ServerType.OpenCode, null)
        assertTrue(caps.shareSupported)
        assertTrue(caps.backgroundSessionsSupported)
        assertTrue(caps.runningSessionsFilterSupported)
        assertTrue(caps.configEditable)
        assertFalse(caps.compactionAsync)
        newBits.forEach { assertTrue(it.name, it.get(caps)) }
    }

    @Test
    fun `legacy single-arg overload delegates to opencode branch`() {
        // 既有调用方（ChatViewModel 等）继续走 of(apiVersion)：行为不变
        assertEquals(
            ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V2),
            ServerCapabilities.of(ApiVersion.V2),
        )
        assertEquals(
            ServerCapabilities.of(ServerType.OpenCode, null),
            ServerCapabilities.of(null as ApiVersion?),
        )
    }

    @Test
    fun `ServerConnection capabilities derive from serverType`() {
        val dsh = ServerConnection(baseUrl = "http://x", authHeader = null, serverType = ServerType.Dsh)
        assertFalse(dsh.capabilities.shareSupported)
        assertFalse(dsh.capabilities.configEditable)
        val v2 = ServerConnection(baseUrl = "http://x", authHeader = null, apiVersion = ApiVersion.V2)
        assertTrue(v2.capabilities.backgroundSessionsSupported)
    }
}
