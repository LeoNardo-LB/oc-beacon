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
 *
 * #276 后端接口补全（2026-08-31 第二批）：三位（revert/messageDelete/shell
 * Command）——52 方法面终局无 revert/unrevert、无消息删除、无 shell 域 → DSH
 * 全 false；OpenCode V1/V2 均有对应端点 → 全 true。
 */
class ServerCapabilitiesDshTest {

    /** #276 UI 卡新增六位（缺口清单补位）+ 接口补全批三位（revert/delete/shell）。 */
    private val newBits: List<kotlin.reflect.KProperty1<ServerCapabilities, Boolean>> = listOf(
        ServerCapabilities::terminalSupported,
        ServerCapabilities::fileReadSupported,
        ServerCapabilities::sessionDeleteSupported,
        ServerCapabilities::vcsSupported,
        ServerCapabilities::fileSearchSupported,
        ServerCapabilities::commandsSupported,
        ServerCapabilities::revertSupported,
        ServerCapabilities::messageDeleteSupported,
        ServerCapabilities::shellCommandSupported,
    )

    @Test
    fun `dsh matrix is all false regardless of apiVersion`() {
        for (version in ApiVersion.entries) {
            val caps = ServerCapabilities.of(ServerType.Dsh, version)
            assertFalse("shareSupported v=$version", caps.shareSupported)
            assertFalse("backgroundSessionsSupported v=$version", caps.backgroundSessionsSupported)
            assertFalse("runningSessionsFilterSupported v=$version", caps.runningSessionsFilterSupported)
            assertFalse("configEditable v=$version", caps.configEditable)
            for (bit in newBits) {
                assertFalse("${bit.name} v=$version", bit.get(caps))
            }
        }
    }

    /**
     * #276 后端接口补全：DSH compact 走 /compact 命令通道（§1.6）——HTTP 受理
     * 即回，完成只由 compaction/end → SessionCompacted 事件通告 = V2 式异步
     * 语义（旧 false 会按 V1 路径本地置态又秒杀——59ms 分割线闪现 bug 复活）。
     */
    @Test
    fun `dsh compaction is async via slash command channel`() {
        for (version in ApiVersion.entries) {
            assertTrue(
                "compactionAsync v=$version",
                ServerCapabilities.of(ServerType.Dsh, version).compactionAsync,
            )
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

    /**
     * #276 终验 V5：DSH /compact 走斜杠命令通道（§1.6：prompt 以 / 开头 =
     * 服务端命令注册表执行，mode 无关、不进模型）——压缩与模型无关。
     * OpenCode V1/V2 的 summarize/compact 端点仍带 providerID/modelID，
     * 客户端「no model selected」护栏维持。
     */
    @Test
    fun `dsh compaction is model independent, opencode is not`() {
        for (version in ApiVersion.entries) {
            assertTrue(
                "compactionModelIndependent v=$version",
                ServerCapabilities.of(ServerType.Dsh, version).compactionModelIndependent,
            )
        }
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V1).compactionModelIndependent)
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V2).compactionModelIndependent)
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, null).compactionModelIndependent)
    }

    /**
     * #276 终验 V6：DSH session.export 响应体是 ZIP 流（§5 P-4 非信封入口），
     * SAF 落盘须 .zip 命名；OpenCode 导出是 JSON 文档（{"info","messages"}），
     * 维持 .json——位区分两族导出格式。
     */
    @Test
    fun `dsh export is archive, opencode is json document`() {
        for (version in ApiVersion.entries) {
            assertTrue(
                "exportIsArchive v=$version",
                ServerCapabilities.of(ServerType.Dsh, version).exportIsArchive,
            )
        }
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V1).exportIsArchive)
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, ApiVersion.V2).exportIsArchive)
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, null).exportIsArchive)
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

    /**
     * 权限预设切换器门控（DSH 专属）：DSH=true（commands/execute + permissions
     * 投影），OpenCode V1/V2/UNKNOWN 全 false（无对应域，UI 不渲染）。
     */
    @Test
    fun `permission switch supported only on dsh`() {
        for (version in ApiVersion.entries) {
            assertTrue(
                "dsh v=$version",
                ServerCapabilities.of(ServerType.Dsh, version).permissionSwitchSupported,
            )
            assertFalse(
                "opencode v=$version",
                ServerCapabilities.of(ServerType.OpenCode, version).permissionSwitchSupported,
            )
        }
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, null).permissionSwitchSupported)
    }

    /**
     * Agent 预设选择器门控（DSH 专属）：DSH=true（agentPreset.list/select 方法面 +
     * agentPreset/blank 字段），OpenCode V1/V2/UNKNOWN 全 false（无对应域，UI 不渲染）。
     */
    @Test
    fun `agent preset supported only on dsh`() {
        for (version in ApiVersion.entries) {
            assertTrue(
                "dsh v=$version",
                ServerCapabilities.of(ServerType.Dsh, version).agentPresetSupported,
            )
            assertFalse(
                "opencode v=$version",
                ServerCapabilities.of(ServerType.OpenCode, version).agentPresetSupported,
            )
        }
        assertFalse(ServerCapabilities.of(ServerType.OpenCode, null).agentPresetSupported)
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
