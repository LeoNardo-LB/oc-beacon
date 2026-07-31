package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.ui.theme.ChatDensity
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Validates the legacy → new chat-density migration logic.
 *
 * The production equivalent lives in [SettingsDataStore.migrateDensity]
 * (returns "normal"/"compact" strings); this test asserts the decision table
 * itself, expressed via the [ChatDensity] enum for readability.
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
