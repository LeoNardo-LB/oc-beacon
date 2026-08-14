package dev.leonardo.ocbeacon.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #136（D2-L56）：语言镜像收敛决策（纯函数）。
 * DataStore 为真相源；镜像（SharedPreferences，attachBaseContext 同步读取）
 * 不一致时以 DataStore 为准回写。
 */
class SettingsLanguageMirrorTest {

    @Test
    fun `mirror matches datastore - no correction needed`() {
        assertNull(SettingsDataStore.resolveLanguageMirror(stored = "ru", mirror = "ru"))
    }

    @Test
    fun `both empty - no correction needed`() {
        assertNull(SettingsDataStore.resolveLanguageMirror(stored = "", mirror = ""))
    }

    @Test
    fun `mirror stale after crash window - correct from datastore`() {
        // 镜像仍为旧值（双写窗口崩溃），DataStore 已有新值 → 以 DataStore 收敛
        assertEquals("de", SettingsDataStore.resolveLanguageMirror(stored = "de", mirror = ""))
    }

    @Test
    fun `mirror newer than datastore - datastore wins as source of truth`() {
        // 旧版先写镜像的窗口：镜像新、DataStore 旧 → 收敛为 DataStore 值（真相源优先）
        assertEquals("", SettingsDataStore.resolveLanguageMirror(stored = "", mirror = "ru"))
    }

    @Test
    fun `mirror different value - datastore wins`() {
        assertEquals("en", SettingsDataStore.resolveLanguageMirror(stored = "en", mirror = "ru"))
    }
}
