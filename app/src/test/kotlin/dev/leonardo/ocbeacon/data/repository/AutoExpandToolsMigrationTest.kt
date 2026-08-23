package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier C-2（#202）：collapse_tools → auto_expand_tools 键改名迁移测试。
 *
 * 关键事实（2026-08-24 取证）：存储值语义**从未反转**——UI 开关文案自始就是
 * "Auto-expand tool results" 且 checked 原值绑定，true=展开。故本迁移是
 * **纯键名搬家，值原样保留**（CONTEXT.md 词条「自动展开工具结果」Phase 2 改名）。
 */
private class AutoExpandInMemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class AutoExpandToolsMigrationTest {

    private val legacyKey = booleanPreferencesKey("collapse_tools")
    private val newKey = booleanPreferencesKey("auto_expand_tools")

    private suspend fun storeWith(vararg prefs: Preferences): Pair<SettingsDataStore, AutoExpandInMemoryDataStore> {
        val ds = AutoExpandInMemoryDataStore()
        prefs.forEach { p -> ds.updateData { p } }
        return SettingsDataStore(ds, mockk<Context>(relaxed = true)) to ds
    }

    @Test
    fun `legacy value readable before migration via read fallback`() = runTest {
        val seed = preferencesOf(legacyKey to true)
        val (store, _) = storeWith(seed)
        assertEquals(true, store.autoExpandTools.first())
    }

    @Test
    fun `migration copies value without inversion and removes legacy key`() = runTest {
        val seed = preferencesOf(legacyKey to true)
        val (store, ds) = storeWith(seed)
        store.runAutoExpandToolsKeyMigration()
        val prefs = ds.data.first()
        // 值原样：true(=展开) → true(=展开)，无取反
        assertEquals(true, prefs[newKey])
        assertEquals(null, prefs[legacyKey])
        assertEquals(true, store.autoExpandTools.first())
    }

    @Test
    fun `migration false value also preserved without inversion`() = runTest {
        val seed = preferencesOf(legacyKey to false)
        val (store, ds) = storeWith(seed)
        store.runAutoExpandToolsKeyMigration()
        assertEquals(false, ds.data.first()[newKey])
        assertEquals(null, ds.data.first()[legacyKey])
        assertEquals(false, store.autoExpandTools.first())
    }

    @Test
    fun `migration idempotent and new key wins once migrated`() = runTest {
        val (store, ds) = storeWith(preferencesOf(legacyKey to true))
        store.runAutoExpandToolsKeyMigration()
        // 新键已存在、旧键已删——再跑一遍不得破坏
        store.runAutoExpandToolsKeyMigration()
        assertEquals(true, ds.data.first()[newKey])
    }

    @Test
    fun `migration no-op when nothing stored`() = runTest {
        val (store, ds) = storeWith()
        store.runAutoExpandToolsKeyMigration()
        assertEquals(null, ds.data.first()[newKey])
        assertEquals(false, store.autoExpandTools.first())
    }

    @Test
    fun `setAutoExpandTools writes new key only`() = runTest {
        val (store, ds) = storeWith(preferencesOf(legacyKey to true))
        store.setAutoExpandTools(false)
        val prefs = ds.data.first()
        assertEquals(false, prefs[newKey])
        // 旧键仍在（迁移职责分离）——但读取以新键为准
        assertEquals(false, store.autoExpandTools.first())
    }

    @Test
    fun `fresh install default is false`() = runTest {
        val (store, _) = storeWith()
        assertEquals(false, store.autoExpandTools.first())
    }
}
