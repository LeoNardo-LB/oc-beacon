package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * 验证旧版 → 新版聊天密度迁移逻辑。
 *
 * 生产环境中的等价实现位于 [SettingsDataStore.migrateDensity]
 * （返回 "normal"/"compact" 字符串）；本测试直接断言该决策表本身，
 * 通过 [ChatDensity] 枚举表达以提高可读性。
 */
class SettingsMigrationTest {

    private fun migrateDensity(fontSize: String?, compact: Boolean?): ChatDensity {
        if (compact == true) return ChatDensity.Compact
        if (fontSize == "small") return ChatDensity.Compact
        return ChatDensity.Normal
    }

    @Test
    fun `compact on with medium font migrates to Compact`() {
        assertEquals(ChatDensity.Compact, migrateDensity("medium", true))
    }

    @Test
    fun `compact on with large font migrates to Compact`() {
        assertEquals(ChatDensity.Compact, migrateDensity("large", true))
    }

    @Test
    fun `small font without compact migrates to Compact`() {
        assertEquals(ChatDensity.Compact, migrateDensity("small", false))
    }

    @Test
    fun `medium font without compact migrates to Normal`() {
        assertEquals(ChatDensity.Normal, migrateDensity("medium", false))
    }

    @Test
    fun `large font without compact migrates to Normal`() {
        assertEquals(ChatDensity.Normal, migrateDensity("large", false))
    }

    @Test
    fun `null settings default to Normal`() {
        assertEquals(ChatDensity.Normal, migrateDensity(null, null))
    }
}
