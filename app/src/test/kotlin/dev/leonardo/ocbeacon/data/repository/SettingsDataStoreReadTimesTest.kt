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
        store.markSessionRead("svr1", "ses1", 5000L)

        assertEquals(mapOf("ses1" to 5000L), store.sessionReadTimes("svr1").first())
    }

    @Test
    fun `markSessionRead is server-scoped`() = runTest {
        val store = newStore()
        store.markSessionRead("svr1", "ses1", 1000L)
        store.markSessionRead("svr2", "ses1", 2000L)

        assertEquals(mapOf("ses1" to 1000L), store.sessionReadTimes("svr1").first())
        assertEquals(mapOf("ses1" to 2000L), store.sessionReadTimes("svr2").first())
    }

    @Test
    fun `empty read times by default`() = runTest {
        val store = newStore()
        assertEquals(emptyMap<String, Long>(), store.sessionReadTimes("svr1").first())
    }

    @Test
    fun `markSessionRead overwrites previous timestamp`() = runTest {
        val store = newStore()
        store.markSessionRead("svr1", "ses1", 5000L)
        // 第二次标记传入更大的 completed：maxOf 单调保护取 max → 已读位置推进为 9000
        store.markSessionRead("svr1", "ses1", 9000L)

        assertEquals(mapOf("ses1" to 9000L), store.sessionReadTimes("svr1").first())
    }

    @Test
    fun `markSessionRead smaller value does not overwrite`() = runTest {
        val store = newStore()
        store.markSessionRead("svr1", "ses1", 9000L)
        // 双 VM 乱序写入更小的 completed：maxOf 单调保护，不回退已读位置
        store.markSessionRead("svr1", "ses1", 5000L)

        assertEquals(mapOf("ses1" to 9000L), store.sessionReadTimes("svr1").first())
    }

    @Test
    fun `markAllSessionsRead then read back`() = runTest {
        val store = newStore()
        store.markAllSessionsRead("svr1", 8000L)

        assertEquals(8000L, store.allReadAt("svr1").first())
    }

    @Test
    fun `markAllSessionsRead smaller value does not overwrite`() = runTest {
        val store = newStore()
        store.markAllSessionsRead("svr1", 8000L)
        // 全量重同步旧数据/服务器时钟异常导致 globalMax 变小：maxOf 单调保护，不回退 allReadAt
        store.markAllSessionsRead("svr1", 3000L)

        assertEquals(8000L, store.allReadAt("svr1").first())
    }

    @Test
    fun `favorite tag unrelated to read times`() = runTest {
        // 确保未读功能不依赖/不干扰标签体系
        val store = newStore()
        store.markSessionRead("svr1", "ses1", 5000L)
        val tags = store.sessionTags("svr1").first()
        assertTrue(tags.none { it.id == FAVORITE_TAG_ID })
    }

    @Test
    fun `v2 migration clears read times and all read once`() = runTest {
        val store = newStore()
        store.markSessionRead("svr1", "ses1", 5000L)
        store.markAllSessionsRead("svr1", 8000L)
        store.runUnreadStateV2Migration()
        assertEquals(emptyMap<String, Long>(), store.sessionReadTimes("svr1").first())
        assertEquals(0L, store.allReadAt("svr1").first())
        // 幂等：迁移标记存在则跳过——写入新值后再次迁移不动
        store.markSessionRead("svr1", "ses2", 1000L)
        store.runUnreadStateV2Migration()
        assertEquals(mapOf("ses2" to 1000L), store.sessionReadTimes("svr1").first())
    }
}
