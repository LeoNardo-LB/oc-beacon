package dev.leonardo.ocbeacon.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #335：opencode_prefs DataStore 损坏处置纯逻辑测试——handler 提取为可测函数
 * （DI 无单测基建；对应 NetworkModule corruptionHandler lambda 行为）。
 *
 * 契约（backlog #335 评估修法）：损坏仍重置（防崩溃循环）但**可观测 + 可取证**——
 * ① AppLogger.e 错误日志；② 损坏文件副本留档 .corrupt-<ts>（取证）；③ 返回
 * emptyPreferences。
 */
class PreferencesCorruptionRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun `recover archives corrupt file copy and returns empty preferences`() {
        val prefsFile = tmp.newFile("opencode_prefs.preferences_pb")
        prefsFile.writeText("corrupt-pb-bytes")

        val result = PreferencesCorruptionRecovery.recover(
            prefsFile,
            IllegalStateException("corrupted preferences_pb"),
        )

        // ③ 重置语义：返回空 preferences（崩溃循环防护不变）
        assertTrue(result.asMap().isEmpty())
        // ② 留档：同目录 .corrupt-<ts> 副本，内容=损坏原文件（取证）
        val archived = tmp.root.listFiles()!!
            .single { it.name.startsWith("opencode_prefs.preferences_pb.corrupt-") }
        val timestampSuffix = archived.name.removePrefix("opencode_prefs.preferences_pb.corrupt-")
        assertTrue(timestampSuffix.isNotEmpty() && timestampSuffix.all { it.isDigit() })
        assertEquals("corrupt-pb-bytes", archived.readText())
        // 原文件保持不动——重置(重写/删除)由 DataStore 自身在 handler 返回后执行
        assertEquals("corrupt-pb-bytes", prefsFile.readText())
    }

    @Test
    fun `recover tolerates unknown file location without crash`() {
        // produceFile 未捕获前损坏（理论窗口）——null 文件仍重置不崩
        val result = PreferencesCorruptionRecovery.recover(null, IllegalStateException("corrupt"))
        assertTrue(result.asMap().isEmpty())
    }

    @Test
    fun `recover tolerates archive failure and still resets`() {
        // 留档失败注入：corruptFile 位置是目录（读流必败）——不得影响重置返回
        val dirAsFile = tmp.newFolder("opencode_prefs.preferences_pb")

        val result = PreferencesCorruptionRecovery.recover(
            dirAsFile,
            IllegalStateException("corrupt"),
        )

        assertTrue(result.asMap().isEmpty())
    }
}
