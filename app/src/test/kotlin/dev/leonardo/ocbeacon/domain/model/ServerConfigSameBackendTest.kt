package dev.leonardo.ocbeacon.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #325 ④：sameBackend 类型化判定——DSH 条目 username 不参与后端同一性。
 *
 * 根因（调研 2026-09-04 §5④）：DSH 鉴权是 token→cookie（authority 绑定），
 * username 字段对 DSH 无语义；而 normalizeBackendKey 把 username 混入键 →
 * 同一 DSH 后端因 username 不同（如 debug 通道 "dsh" vs 手动添加 "opencode"）
 * 裂成两条条目/两条连接。
 *
 * 修复语义（保守收窄）：**双侧均为 Dsh** 时忽略 username；其余组合（含
 * OpenCode↔OpenCode、DSH↔OpenCode）维持旧键行为——#319 类型错配修正通道
 * （debug_server_type 覆写既有条目）依赖跨类型命中，不得收窄。
 */
class ServerConfigSameBackendTest {

    private val url = "http://192.168.110.248:3080"

    @Test
    fun `B1_DSH同url不同username视为同一后端`() {
        assertTrue(
            ServerConfig.sameBackend(
                ServerType.Dsh, url, "dsh",
                ServerType.Dsh, url, "opencode",
            ),
        )
    }

    @Test
    fun `B2_DSH归一化仍然生效_尾斜杠大小写`() {
        assertTrue(
            ServerConfig.sameBackend(
                ServerType.Dsh, "http://192.168.110.248:3080/", "user-a",
                ServerType.Dsh, "HTTP://192.168.110.248:3080", "user-b",
            ),
        )
    }

    @Test
    fun `B3_OpenCode不同username仍为不同后端_行为不变`() {
        assertFalse(
            ServerConfig.sameBackend(
                ServerType.OpenCode, url, "alice",
                ServerType.OpenCode, url, "bob",
            ),
        )
        assertTrue(
            ServerConfig.sameBackend(
                ServerType.OpenCode, url, "alice",
                ServerType.OpenCode, url, "alice",
            ),
        )
    }

    @Test
    fun `B4_跨类型维持旧键命中_319类型修正通道保留`() {
        // DSH 条目 vs OpenCode 条目同 url 同 username → 仍命中（覆写转换通道）
        assertTrue(
            ServerConfig.sameBackend(
                ServerType.Dsh, url, "opencode",
                ServerType.OpenCode, url, "opencode",
            ),
        )
    }

    @Test
    fun `B5_不同url仍为不同后端`() {
        assertFalse(
            ServerConfig.sameBackend(
                ServerType.Dsh, "http://10.0.0.5:3080", "dsh",
                ServerType.Dsh, "http://10.0.0.6:3080", "dsh",
            ),
        )
    }

    @Test
    fun `B6_旧四参重载行为不变`() {
        // 旧签名等价于双侧 OpenCode——OpenCode 侧 username 仍参与判定
        assertTrue(ServerConfig.sameBackend(url, "alice", url, "alice"))
        assertFalse(ServerConfig.sameBackend(url, "alice", url, "bob"))
    }
}
