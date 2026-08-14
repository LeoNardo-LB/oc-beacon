package dev.leonardo.ocbeacon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import dev.leonardo.ocbeacon.domain.model.Tag
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯内存 DataStore——避免 Windows 文件系统 rename 限制（androidx.datastore FileStorage
 * 在 Windows 上无法可靠地用 .tmp 覆盖已存在的目标文件）。
 *
 * 被测扩展函数（[androidx.datastore.preferences.core.edit] 及本项目自定义扩展）只依赖
 * [DataStore.data] flow 与 [DataStore.updateData]，内存实现语义等价。
 */
private class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

class SettingsDataStoreTagsTest {

    private fun newStore(): SettingsDataStore =
        SettingsDataStore(InMemoryPreferencesDataStore(), mockk<Context>(relaxed = true))

    @Test
    fun `tag serialization round trip`() {
        val tag = Tag(id = "t1", name = "前端", color = "blue", icon = "code")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = json.decodeFromString<Tag>(json.encodeToString(Tag.serializer(), tag))
        assertEquals(tag, decoded)
    }

    @Test
    fun `removeTag clears assignments atomically`() = runTest {
        val store = newStore()
        store.addSessionTag("srv", Tag(id = "t1", name = "a"))
        store.setSessionTags("srv", "ses1", setOf("t1"))
        store.removeSessionTag("srv", "t1")
        val tags = store.sessionTags("srv").first()
        val assigns = store.sessionTagAssignments("srv").first()
        assertTrue(tags.isEmpty())
        assertTrue(assigns["ses1"].orEmpty().none { it == "t1" })
    }

    @Test
    fun `setSessionTags keeps favorite tag`() = runTest {
        val store = newStore()
        store.toggleFavorite("srv", "ses1")
        store.setSessionTags("srv", "ses1", setOf("t2"))
        val assigns = store.sessionTagAssignments("srv").first()
        assertTrue(assigns["ses1"].orEmpty().contains(FAVORITE_TAG_ID))
        assertTrue(assigns["ses1"].orEmpty().contains("t2"))
    }

    @Test
    fun `favoriteSessionIds reflects toggle`() = runTest {
        val store = newStore()
        store.toggleFavorite("srv", "ses1")
        assertTrue(store.favoriteSessionIds("srv").first().contains("ses1"))
        store.toggleFavorite("srv", "ses1")
        assertTrue(store.favoriteSessionIds("srv").first().isEmpty())
    }

    @Test
    fun `sessionTags excludes favorite tag`() = runTest {
        val store = newStore()
        store.toggleFavorite("srv", "ses1")
        assertTrue(store.sessionTags("srv").first().none { it.id == FAVORITE_TAG_ID })
    }

    @Test
    fun `favoriteSessionIds migrates legacy stringSet on first read`() = runTest {
        val store = newStore()
        // 模拟旧 favorite_sessions_<serverId> stringSet 数据（SettingsDataStoreFavorites 历史格式）
        val legacyKey = stringSetPreferencesKey("favorite_sessions_srv")
        store.dataStore.edit { it[legacyKey] = setOf("legacy-a", "legacy-b") }
        // #137（D2-L59）：迁移显式触发（原藏在 flow map 内，已移出）
        store.migrateLegacyFavoritesIfNeeded("srv")
        val firstRead = store.favoriteSessionIds("srv").first()
        assertEquals(setOf("legacy-a", "legacy-b"), firstRead)
        // 迁移已写入 assignments map：再次读取时直接从 assignments 派生
        val assigns = store.sessionTagAssignments("srv").first()
        assertTrue(assigns["legacy-a"]?.contains(FAVORITE_TAG_ID) == true)
        assertTrue(assigns["legacy-b"]?.contains(FAVORITE_TAG_ID) == true)
    }

    @Test
    fun `favoriteSessionIds migrate then unfavorite all does not resurrect`() = runTest {
        val store = newStore()
        val legacyKey = stringSetPreferencesKey("favorite_sessions_srv")
        store.dataStore.edit { it[legacyKey] = setOf("legacy-a", "legacy-b") }
        // #137（D2-L59）：迁移显式触发（原藏在 flow map 内，已移出）
        store.migrateLegacyFavoritesIfNeeded("srv")
        val firstRead = store.favoriteSessionIds("srv").first()
        assertEquals(setOf("legacy-a", "legacy-b"), firstRead)
        // 迁移成功后 legacy key 必须已被删除（否则后续取消全部收藏会让迁移条件再次满足）
        val legacyAfterMigrate = store.dataStore.data.first()[legacyKey]
        assertTrue("legacy key should be removed after migration", legacyAfterMigrate == null)
        // 取消全部收藏
        store.toggleFavorite("srv", "legacy-a")
        store.toggleFavorite("srv", "legacy-b")
        // 再读：必须为空，不应因 legacy key 残留而重新迁移"复活"
        val afterUnfavorite = store.favoriteSessionIds("srv").first()
        assertTrue("unfavorited sessions must not resurrect", afterUnfavorite.isEmpty())
    }
}
