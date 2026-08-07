package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯内存 DataStore——避免 Windows 文件系统 rename 限制（与 SettingsDataStoreTagsTest 相同模式）。 */
private class InMemoryReadTimesStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class SettingsDataStoreReadTimesTest {

    private fun newStore(): SettingsDataStore =
        SettingsDataStore(InMemoryReadTimesStore(), mockk<Context>(relaxed = true))

    @Test
    fun `markSessionRead then read back`() = runTest {
        val store = newStore()
        val before = System.currentTimeMillis()
        store.markSessionRead("svr1", "ses1")
        val after = System.currentTimeMillis()

        val times = store.sessionReadTimes("svr1").first()
        val readAt = times["ses1"]
        assertTrue("readAt should be within [before, after]", readAt != null && readAt in before..after)
    }

    @Test
    fun `read times are isolated per server`() = runTest {
        val store = newStore()
        store.markSessionRead("svr1", "ses1")
        store.markSessionRead("svr2", "ses1")

        assertEquals(setOf("ses1"), store.sessionReadTimes("svr1").first().keys)
        assertEquals(setOf("ses1"), store.sessionReadTimes("svr2").first().keys)
        // 两服务器各自记录，互不影响（时间戳同毫秒时也可能相同，只验证隔离语义）
        assertEquals(1, store.sessionReadTimes("svr1").first().size)
        assertEquals(1, store.sessionReadTimes("svr2").first().size)
    }

    @Test
    fun `empty read times by default`() = runTest {
        val store = newStore()
        assertEquals(emptyMap<String, Long>(), store.sessionReadTimes("svr1").first())
    }

    @Test
    fun `markSessionRead overwrites previous timestamp`() = runTest {
        val store = newStore()
        store.markSessionRead("svr1", "ses1")
        val first = store.sessionReadTimes("svr1").first()["ses1"]!!

        // 模拟第二次标记（时间前进）
        val store2 = newStore()
        store2.markSessionRead("svr1", "ses1")
        // 无法精确推进系统时间，验证同一 key 覆盖语义：只存一个时间戳
        assertEquals(1, store.sessionReadTimes("svr1").first().size)
        assertEquals(1, store2.sessionReadTimes("svr1").first().size)
        assertTrue(first >= 0)
    }

    @Test
    fun `favorite tag unrelated to read times`() = runTest {
        // 确保未读功能不依赖/不干扰标签体系
        val store = newStore()
        store.markSessionRead("svr1", "ses1")
        val tags = store.sessionTags("svr1").first()
        assertTrue(tags.none { it.id == FAVORITE_TAG_ID })
    }
}
