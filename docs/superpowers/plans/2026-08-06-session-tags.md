# 会话标签系统（Tag）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将会话分类系统重构为服务器级 Tag 实体（多对多分配 + 收藏统一模型 + 设置页管理），并删除跨服务器入口。

**Architecture:** 数据层新建 `Tag` 实体与统一分配 map（DataStore JSON，按 serverId 隔离），内置 `FAVORITE` 类型标签承载收藏；Repository 双层 API（标签视图 + 收藏视图）共享同一真相源；UI 层替换分配弹窗为复选框多选、会话行多标签显示、设置页新增标签管理，并移除跨服务器收藏页。

**Tech Stack:** Kotlin + DataStore Preferences + kotlinx.serialization + Jetpack Compose + JUnit4/MockK（测试）

## Global Constraints

- 存储继续用 DataStore Preferences（不引入 Room）
- Tag 按服务器隔离：key 为 `session_tags_<serverId>` / `session_tag_assignments_<serverId>`
- 内置收藏标签 id 固定为 `"builtin:favorite"`，type = `FAVORITE`，不可删除/修改，不出现在分配弹窗与标签管理列表
- `setSessionTags` 只处理 USER 类型标签（收藏只能通过 `toggleFavorite` 修改）
- 旧分类数据（`session_categories` / `session_category_assignments_*`）废弃不读；收藏旧数据（favorite_session_ids_*）迁移为内置标签分配
- 删除标签必须与分配清理在同一 DataStore edit 内完成（原子）
- 跨服务器入口删除后编译不得残留引用（`CrossServerSessions*`、`FavoriteSessionSnapshot`、`crossServerFavoriteOrder`）
- 编译检查：`.\gradlew :app:compileDevDebugKotlin`（120s）；单元测试：`.\gradlew :app:testDevDebugUnitTest --rerun`（180s）；完整构建：`.\gradlew :app:assembleBetaRelease`（300s）

---

### Task 1: Tag 实体 + DataStore 存储层

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/Tag.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTags.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTagsTest.kt`

**Interfaces:**
- Produces:
  - `data class Tag(id, name, color="blue", icon="folder", type: TagType=USER, createdAt: Long=0)`
  - `enum class TagType { USER, FAVORITE }`
  - `const val FAVORITE_TAG_ID = "builtin:favorite"`（Tag.kt 顶层）
  - `fun builtinFavoriteTag(): Tag`（SettingsDataStoreTags.kt 顶层，id=FAVORITE_TAG_ID, name="收藏", icon="star", type=FAVORITE）
  - `fun SettingsDataStore.sessionTags(serverId): Flow<List<Tag>>`
  - `fun SettingsDataStore.sessionTagAssignments(serverId): Flow<Map<String, List<String>>>`
  - `suspend fun SettingsDataStore.addSessionTag(serverId, tag)`
  - `suspend fun SettingsDataStore.updateSessionTag(serverId, tag)`
  - `suspend fun SettingsDataStore.removeSessionTag(serverId, tagId)`（同一 edit 内清理分配）
  - `suspend fun SettingsDataStore.setSessionTags(serverId, sessionId, tagIds: Set<String>)`（只写 USER 类型 id）
  - `suspend fun SettingsDataStore.removeSessionTagAssignment(serverId, sessionId, tagId)`
  - `suspend fun SettingsDataStore.toggleFavorite(serverId, sessionId)`（切换内置标签分配）
  - `fun SettingsDataStore.favoriteSessionIds(serverId): Flow<Set<String>>`（从统一分配 map 派生：含 FAVORITE_TAG_ID 的会话 id 集合）

- [ ] **Step 1: 写失败测试（Tag 序列化）**

`app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTagsTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.model.TagType
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class SettingsDataStoreTagsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): SettingsDataStore {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tmp.newFile("test.preferences_pb") },
        )
        return SettingsDataStoreForTest(dataStore)
    }

    @Test
    fun `tag serialization round trip`() = runTest {
        val tag = Tag(id = "t1", name = "前端", color = "blue", icon = "code")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val decoded = json.decodeFromString<Tag>(json.encodeToString(Tag.serializer(), tag))
        assertEquals(tag, decoded)
    }
}
```

> 注：`SettingsDataStoreForTest` 是测试辅助构造（见 Step 3）。若 `SettingsDataStore` 主构造含 context，测试辅助用 `internal constructor` 或伴生工厂暴露 DataStore 注入点。先写测试编译失败（Tag 不存在）→ 实施后通过。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.data.repository.SettingsDataStoreTagsTest"`
Expected: FAIL（`Tag` 未定义 / 测试辅助不存在）

- [ ] **Step 3: 创建 Tag 实体 + 存储层实现**

`app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/Tag.kt`：

```kotlin
package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TagType { USER, FAVORITE }

/** 内置收藏标签 id —— 每个服务器固定一个，承载收藏星标。 */
const val FAVORITE_TAG_ID = "builtin:favorite"

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String = "blue",
    val icon: String = "folder",
    val type: TagType = TagType.USER,
    val createdAt: Long = 0,
)
```

`app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTags.kt`（完整实现）：

```kotlin
package dev.leonardo.ocbeacon.data.repository

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.model.TagType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG_DIAG = "TagDiag"

private val tagJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val tagListSerializer = ListSerializer(Tag.serializer())
private val assignmentMapSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

private const val SESSION_TAGS_PREFIX = "session_tags_"
private const val SESSION_TAG_ASSIGNMENTS_PREFIX = "session_tag_assignments_"
/** 旧收藏 key（迁移源）—— SettingsDataStoreFavorites.kt 历史格式：stringSetPreferencesKey("favorite_sessions_" + serverId)。 */
private const val FAVORITE_SESSIONS_PREFIX = "favorite_sessions_"

private fun tagsKey(serverId: String) = stringPreferencesKey(SESSION_TAGS_PREFIX + serverId)
private fun assignmentsKey(serverId: String) = stringPreferencesKey(SESSION_TAG_ASSIGNMENTS_PREFIX + serverId)
private fun legacyFavoriteKey(serverId: String) = stringSetPreferencesKey(FAVORITE_SESSIONS_PREFIX + serverId)

/** 内置收藏标签（每服务器固定一个，不可删改）。 */
fun builtinFavoriteTag(): Tag = Tag(
    id = FAVORITE_TAG_ID,
    name = "收藏",
    color = "amber",
    icon = "star",
    type = TagType.FAVORITE,
    createdAt = 0,
)

/** 该服务器的标签集（不含内置收藏标签）。 */
fun SettingsDataStore.sessionTags(serverId: String): Flow<List<Tag>> =
    dataStore.data.map { prefs ->
        val json = prefs[tagsKey(serverId)]
        val tags = if (json.isNullOrBlank()) emptyList()
        else runCatching { tagJson.decodeFromString(tagListSerializer, json) }.getOrDefault(emptyList())
        tags.filter { it.type != TagType.FAVORITE }
    }

/** 统一分配 map（sessionId → tagIds，含内置收藏标签）。 */
fun SettingsDataStore.sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
    dataStore.data.map { prefs ->
        val json = prefs[assignmentsKey(serverId)]
        if (json.isNullOrBlank()) emptyMap()
        else runCatching { tagJson.decodeFromString(assignmentMapSerializer, json) }.getOrDefault(emptyMap())
    }

suspend fun SettingsDataStore.addSessionTag(serverId: String, tag: Tag) {
    dataStore.edit { prefs ->
        val current = prefs[tagsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()
        prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tag.id } + tag)
    }
}

suspend fun SettingsDataStore.updateSessionTag(serverId: String, tag: Tag) = addSessionTag(serverId, tag)

suspend fun SettingsDataStore.removeSessionTag(serverId: String, tagId: String) {
    dataStore.edit { prefs ->
        val current = prefs[tagsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(tagListSerializer, it) }.getOrDefault(emptyList())
        } ?: emptyList()
        prefs[tagsKey(serverId)] = tagJson.encodeToString(tagListSerializer, current.filterNot { it.id == tagId })
        // 同一 edit：清理所有会话的该标签分配（原子）
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        if (assignments.values.any { tagId in it }) {
            prefs[assignmentsKey(serverId)] = tagJson.encodeToString(
                assignmentMapSerializer,
                assignments.mapValues { (_, ids) -> ids.filterNot { it == tagId } }
            )
        }
    }
    Log.d(TAG_DIAG, "[removeTag] done server=$serverId tag=$tagId")
}

suspend fun SettingsDataStore.setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) {
    dataStore.edit { prefs ->
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val current = assignments[sessionId].orEmpty().filter { it == FAVORITE_TAG_ID } // 保留收藏，只替换 USER 标签
        prefs[assignmentsKey(serverId)] = tagJson.encodeToString(
            assignmentMapSerializer,
            assignments + (sessionId to (current + tagIds).distinct())
        )
    }
    Log.d(TAG_DIAG, "[setTags] done server=$serverId session=$sessionId tags=$tagIds")
}

suspend fun SettingsDataStore.removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) {
    dataStore.edit { prefs ->
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val updated = assignments[sessionId].orEmpty().filterNot { it == tagId }
        val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
        prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
    }
}

suspend fun SettingsDataStore.toggleFavorite(serverId: String, sessionId: String) {
    dataStore.edit { prefs ->
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val current = assignments[sessionId].orEmpty()
        val updated = if (FAVORITE_TAG_ID in current) {
            current.filterNot { it == FAVORITE_TAG_ID }
        } else {
            current + FAVORITE_TAG_ID
        }
        val next = if (updated.isEmpty()) assignments - sessionId else assignments + (sessionId to updated)
        prefs[assignmentsKey(serverId)] = tagJson.encodeToString(assignmentMapSerializer, next)
    }
}

/** 收藏会话 id（从统一分配 map 派生）。首次读取时迁移旧 favorite_session_ids_* 数据。 */
fun SettingsDataStore.favoriteSessionIds(serverId: String): Flow<Set<String>> =
    dataStore.data.map { prefs ->
        val assignments = prefs[assignmentsKey(serverId)]?.let {
            runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
        val fromAssignments = assignments.filterValues { FAVORITE_TAG_ID in it }.keys
        // 迁移：旧独立收藏 key（stringSet）→ 内置标签分配（一次性，写入后下次直接走 assignments）
        val legacy = prefs[legacyFavoriteKey(serverId)]
        if (legacy != null && fromAssignments.isEmpty() && legacy.isNotEmpty()) {
            dataStore.edit { p ->
                val cur = p[assignmentsKey(serverId)]?.let {
                    runCatching { tagJson.decodeFromString(assignmentMapSerializer, it) }.getOrDefault(emptyMap())
                } ?: emptyMap()
                p[assignmentsKey(serverId)] = tagJson.encodeToString(
                    assignmentMapSerializer,
                    legacy.fold(cur) { acc, sid -> acc + (sid to (acc[sid].orEmpty() + FAVORITE_TAG_ID).distinct()) }
                )
            }
            Log.d(TAG_DIAG, "[favoriteMigrate] server=$serverId count=${legacy.size}")
            legacy
        } else {
            fromAssignments
        }
    }
```

> 注：`legacyFavoriteKey` 用 `stringSetPreferencesKey`（旧格式为 stringSet，无需 JSON 解码）。

- [ ] **Step 4: 测试辅助构造（SettingsDataStoreForTest）**

在 `SettingsDataStore.kt` 增加 internal 测试构造（若主构造是 context 版）：

```kotlin
// SettingsDataStore.kt 类定义处追加
internal constructor(dataStore: androidx.datastore.core.DataStore<Preferences>) : this(
    androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
        produceFile = { throw IllegalStateException("use real file") },
    )
)
```

> 若不可行（主构造签名复杂），改为在测试中直接用 `PreferenceDataStoreFactory.create` 构造 DataStore 并调用**顶层扩展函数**（扩展函数只依赖 `SettingsDataStore.dataStore` 属性——若 dataStore 是 val 且私有则需 internal 暴露）。实现时以编译通过为准，最小改动。

- [ ] **Step 5: 补充存储层测试（原子清理 / 收藏迁移 / 内置保护）**

追加到 SettingsDataStoreTagsTest.kt：

```kotlin
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
    assertTrue(assigns["ses1"].orEmpty().contains(dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID))
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
    assertTrue(store.sessionTags("srv").first().none { it.id == dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID })
}
```

> `first()` 需要 `import kotlinx.coroutines.flow.first`。若迁移测试（legacy key）难构造，可跳过该测试（迁移代码走 review 检查）。

- [ ] **Step 6: 运行全部测试通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.data.repository.SettingsDataStoreTagsTest"`
Expected: PASS（4 个测试）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/Tag.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTags.kt app/src/test/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreTagsTest.kt
git commit -m "feat(tags): Tag 实体 + DataStore 存储层（多对多分配 + 内置收藏标签 + 原子清理 + 旧收藏迁移）"
```

---

### Task 2: SettingsRepository 接口与实现改造

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryImpl.kt`

**Interfaces:**
- Consumes: Task 1 的 `SettingsDataStoreTags.kt` 扩展函数
- Produces:
  - `fun sessionTags(serverId: String): Flow<List<Tag>>`
  - `fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>>`
  - `suspend fun addSessionTag(serverId: String, tag: Tag)`
  - `suspend fun updateSessionTag(serverId: String, tag: Tag)`
  - `suspend fun removeSessionTag(serverId: String, tagId: String)`
  - `suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>)`
  - `suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String)`
  - `fun favoriteSessionIds(serverId: String): Flow<Set<String>>`（保留签名，语义改为内置标签派生）
  - `suspend fun toggleFavorite(serverId: String, sessionId: String)`
  - 删除：`sessionCategories` / `sessionCategoryAssignments` / `addSessionCategory` / `removeSessionCategory` / `assignSessionCategory` / `unassignSessionCategory` / `crossServerFavoriteOrder` / `favoriteSessionSnapshots` / `addFavoriteSession` / `removeFavoriteSession` / `setCrossServerFavoriteOrder` / `setCrossServerFavoriteOrderItem` / `saveFavoriteSessionSnapshot` / `clearFavoriteSessionSnapshot`

- [ ] **Step 1: 改造接口**

`SettingsRepository.kt`：删除旧分类与跨服务器收藏接口（列表见 Interfaces-Produces），新增标签接口与 `toggleFavorite`，保留 `favoriteSessionIds(serverId)`。删除 `import SessionCategory`、`import FavoriteSessionSnapshot`，新增 `import Tag`。

- [ ] **Step 2: 改造实现**

`SettingsRepositoryImpl.kt`：删除对应委托，新增：

```kotlin
override fun sessionTags(serverId: String): Flow<List<Tag>> = dataRepo.sessionTags(serverId)
override fun sessionTagAssignments(serverId: String): Flow<Map<String, List<String>>> =
    dataRepo.sessionTagAssignments(serverId)
override suspend fun addSessionTag(serverId: String, tag: Tag) = dataRepo.addSessionTag(serverId, tag)
override suspend fun updateSessionTag(serverId: String, tag: Tag) = dataRepo.updateSessionTag(serverId, tag)
override suspend fun removeSessionTag(serverId: String, tagId: String) = dataRepo.removeSessionTag(serverId, tagId)
override suspend fun setSessionTags(serverId: String, sessionId: String, tagIds: Set<String>) =
    dataRepo.setSessionTags(serverId, sessionId, tagIds)
override suspend fun removeSessionTagAssignment(serverId: String, sessionId: String, tagId: String) =
    dataRepo.removeSessionTagAssignment(serverId, sessionId, tagId)
override fun favoriteSessionIds(serverId: String): Flow<Set<String>> = dataRepo.favoriteSessionIds(serverId)
override suspend fun toggleFavorite(serverId: String, sessionId: String) =
    dataRepo.toggleFavorite(serverId, sessionId)
```

- [ ] **Step 3: 编译检查（预期现有调用方报错——Task 3/7 修复）**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: 报错来自 `SessionListViewModel`/`CrossServerSessionsViewModel` 等调用方（旧接口调用）——本任务只改 Repository 层，编译错误列表记录下来供 Task 3/7 修复。若报错集中在可接受的调用方清单内，本任务结束。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/domain/repository/SettingsRepository.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsRepositoryImpl.kt
git commit -m "feat(tags): SettingsRepository 标签接口（替换分类 + 收藏视图统一为内置标签）"
```

---

### Task 3: 会话列表状态适配（多标签）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TreeNode.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionTreeList.kt`

**Interfaces:**
- Consumes: Task 2 的 Repository 接口
- Produces:
  - `SessionItem.tags: List<Tag>`（替换 `category: SessionCategory?`）
  - `buildTreeNodes(..., sessionTags: Map<String, List<Tag>> = emptyMap())`（替换 `sessionCategories` 参数）
  - `SessionListViewModel.assignTags(sessionId: String, tagIds: Set<String>)`
  - `SessionListViewModel.toggleFavorite(session: Session)`（改用新签名）
  - `SessionTreeList.onAssignTags: (sessionId: String, currentTagIds: Set<String>) -> Unit`（替换 onAssignCategory）

- [ ] **Step 1: SessionItem 改多标签**

`SessionListUiState.kt`：`category: SessionCategory? = null` → `tags: List<Tag> = emptyList()`，import 换 `Tag`。删除 `import dev.leonardo.ocbeacon.domain.model.SessionCategory`。

- [ ] **Step 2: StateBuilder 改多标签**

`SessionListStateBuilder.kt`：
- `val categoryAssignments = values[14] as Map<String, String>` → `val tagAssignments = values[14] as Map<String, List<String>>`
- `val categoriesList = values[16] as List<SessionCategory>` → `val tagsList = values[16] as List<Tag>`
- `resolvedCategories` 替换为：

```kotlin
val tagsById = tagsList.associateBy { it.id }
val resolvedTags: Map<String, List<Tag>> = buildMap {
    tagAssignments.forEach { (sessionId, tagIds) ->
        put(sessionId, tagIds.mapNotNull { tagsById[it] })
    }
}
```

- RECENT 分支：`category = resolvedCategories[session.id]` → `tags = resolvedTags[session.id].orEmpty()`
- FOLDER 分支：`buildTreeNodes(..., resolvedCategories)` → `buildTreeNodes(..., resolvedTags)`

- [ ] **Step 3: TreeNode/buildTreeNodes 参数改名**

`TreeNode.kt`：`sessionCategories: Map<String, SessionCategory>` → `sessionTags: Map<String, List<Tag>>`；内部 `SessionItem(category = sessionCategories[it.id])` 处改为 `tags = sessionTags[it.id].orEmpty()`（具体行以实现时 grep `category` 定位）。

- [ ] **Step 4: ViewModel combine 源与操作**

`SessionListViewModel.kt`：
- `sessionCategories` StateFlow → `sessionTags: StateFlow<List<Tag>> = settingsRepository.sessionTags(serverId).stateIn(...)`
- combine 源 `settingsRepository.sessionCategoryAssignments(serverId)` → `settingsRepository.sessionTagAssignments(serverId)`
- `sessionCategories` → `sessionTags`
- `assignCategory(sessionId, categoryId)` → `assignTags(sessionId, tagIds: Set<String>) { viewModelScope.launch { settingsRepository.setSessionTags(serverId, sessionId, tagIds) } }`
- `unassignCategory` → `removeSessionTagAssignment(sessionId, tagId)`（Task 6 用）
- `toggleFavorite(session)`（206 行附近）→ `settingsRepository.toggleFavorite(serverId, session.id)`（删除 snapshot/order 逻辑）

- [ ] **Step 5: SessionTreeList 回调改名**

`SessionTreeList.kt`：`onAssignCategory: (sessionId: String, currentCategoryId: String?) -> Unit` → `onAssignTags: (sessionId: String, currentTagIds: Set<String>) -> Unit`；`SessionRow` 调用处传 `node.session.tags.map { it.id }.toSet()`。

- [ ] **Step 6: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS（Task 4/5 的 UI 消费方尚未改——若 SessionRow 仍引用 `item.category` 报错，先临时改为 `item.tags` 空实现，Task 5 完善显示）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TreeNode.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionTreeList.kt
git commit -m "feat(tags): 会话列表状态改多标签（SessionItem.tags + resolvedTags + ViewModel 新接口）"
```

---

### Task 4: 分配弹窗 TagPickerDialog（复选框多选）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagPickerDialog.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt`

**Interfaces:**
- Consumes: Task 3 的 `assignTags` / `onAssignTags`
- Produces: `fun TagPickerDialog(tags: List<Tag>, selectedTagIds: Set<String>, onConfirm: (Set<String>) -> Unit, onDismiss: () -> Unit, onCreateTag: (name: String, color: String, icon: String) -> Unit)`

- [ ] **Step 1: 创建 TagPickerDialog**

`TagPickerDialog.kt`（基于现有 `SessionCategoryPickerDialog.kt` 改造）：

```kotlin
@Composable
fun TagPickerDialog(
    tags: List<Tag>,                          // USER 标签
    selectedTagIds: Set<String>,              // 当前会话已选
    onConfirm: (Set<String>) -> Unit,         // 点确定：传最终勾选集合
    onDismiss: () -> Unit,
    onCreateTag: (name: String, color: String, icon: String) -> Unit,
) {
    val params = amoledDialogParams()
    var selected by remember { mutableStateOf(selectedTagIds) }   // 本地多选状态
    var newCategoryName by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(SessionCategoryStyle.colorKeys.first()) }
    var selectedIcon by remember { mutableStateOf(SessionCategoryStyle.iconKeys.first()) }

    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(...) {
            Column(...) {
                Text(stringResource(R.string.category), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                tags.forEach { tag ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(...).clickable {
                            selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = tag.id in selected, onCheckedChange = {
                            selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                        })
                        Icon(SessionCategoryStyle.icon(tag.icon), null, Modifier.size(20.dp),
                            tint = SessionCategoryStyle.color(tag.color))
                        Spacer(Modifier.width(10.dp))
                        Text(tag.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider(...)
                // 新建标签区（名称/颜色/图标 + 添加按钮）—— 创建后由调用方刷新 tags 列表，
                // 新标签通过 onCreateTag 创建；本 dialog 不持有创建后自动勾选逻辑的
                // 数据源（tags 列表来自外部）—— 见 Step 2 的自动勾选实现。
                OutlinedTextField(value = newCategoryName, onValueChange = { newCategoryName = it },
                    label = { Text(stringResource(R.string.category_name)) }, singleLine = true)
                // 颜色选择 Row（复用 SessionCategoryStyle.colorKeys + ColorDot）
                // 图标选择 Row（复用 SessionCategoryStyle.iconKeys + IconOption）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (newCategoryName.isNotBlank()) {
                                onCreateTag(newCategoryName.trim(), selectedColor, selectedIcon)
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.isNotBlank(),
                    ) { Text(stringResource(R.string.add)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
}
```

> 颜色/图标选择器与 `ColorDot`/`IconOption` 从 `SessionCategoryPickerDialog.kt` 复制（保持视觉一致）。若缺少 `R.string.save`，用现有 `R.string.close` 相邻文案或 `common_ok`（实现时 grep strings.xml 确认）。

- [ ] **Step 2: 新增标签自动勾选**

`SessionListScreen.kt` 的 dialog 调用处：

```kotlin
if (showCategoryPicker) {
    TagPickerDialog(
        tags = categories,
        selectedTagIds = assignTagIds,          // 打开时传入当前会话已选
        onConfirm = { tagIds ->
            showCategoryPicker = false
            viewModel.assignTags(assignSessionId, tagIds)
        },
        onDismiss = { showCategoryPicker = false },
        onCreateTag = { name, color, icon ->
            val newId = "tag_${System.currentTimeMillis()}"
            viewModel.addCategory(name, color, icon)  // Task 6 改为 addTag 并返回 id
            // 自动勾选：新标签加入本地选择集合（dialog 关闭后下次打开由 selectedTagIds 同步）
            // 若 tags 列表来自 categories（collectAsStateWithLifecycle），创建后 categories
            // 自动刷新；本地 selected 集合需在 onCreateTag 后加入新 id —— 通过回调把新 id 传回：
            // 实现时在 ViewModel 增加 addSessionTag 返回 id 或预先生成 id 传入。
        },
    )
}
```

> **自动勾选的关键实现**：`assignTagIds` 是 `remember` 状态——打开 dialog 时初始化为当前会话 tags；`onCreateTag` 里预先生成 `newId` 并 `assignTagIds += newId`（本地状态），`onConfirm` 时集合已含新标签。`viewModel.addCategory` 在 Task 6 改为 `addSessionTag`（接收完整 Tag 含预生成 id）。

- [ ] **Step 3: SessionListScreen 状态调整**

`SessionListScreen.kt`：
- `var assignCategoryId by remember { mutableStateOf<String?>(null) }` → `var assignTagIds by remember { mutableStateOf<Set<String>>(emptySet()) }`
- 280 行 `onAssignCategory = { sessionId, currentCategoryId -> assignSessionId = sessionId; assignCategoryId = currentCategoryId; showCategoryPicker = true }` → `onAssignTags = { sessionId, currentTagIds -> assignSessionId = sessionId; assignTagIds = currentTagIds; showCategoryPicker = true }`
- `categories` 变量（91 行）→ `viewModel.sessionTags` 的收集

- [ ] **Step 4: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagPickerDialog.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt
git commit -m "feat(tags): 分配弹窗复选框多选 + 确定保存 + 新增标签自动勾选"
```

---

### Task 5: SessionRow 多标签显示

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionRow.kt`

**Interfaces:**
- Consumes: Task 3 的 `SessionItem.tags: List<Tag>`
- Produces: 无（纯 UI）

- [ ] **Step 1: 替换单标签为多标签横排**

`SessionRow.kt` 200-229 行：`item.category?.let { ... }` 单标签块替换为：

```kotlin
// 标签区（第三行右对齐；多标签横排，内容超出可用宽度时循环滚动播放）
if (item.tags.isNotEmpty()) {
    Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier = Modifier
                .basicMarquee()
                .clip(RoundedCornerShape(4.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item.tags.forEach { tag ->
                Row(
                    modifier = Modifier
                        .background(SessionCategoryStyle.color(tag.color).copy(alpha = AlphaTokens.SELECTED))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = SessionCategoryStyle.icon(tag.icon),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = SessionCategoryStyle.color(tag.color),
                    )
                    Text(
                        text = tag.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = SessionCategoryStyle.color(tag.color),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
```

> `item.category` 引用改为 `item.tags`（`SessionItem` 字段已在 Task 3 改名）。`category?.id` 的详情对话框传参（270 行附近）改为 tags。

- [ ] **Step 2: 详情对话框 onAssignTags 适配**

`SessionRow.kt` 270-272 行：`onAssignCategory()` 调用保持（签名已在 Task 3 改为 `onAssignTags`），参数 `item.session.category?.id` 相关处改为 `item.tags.map { it.id }.toSet()`。

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionRow.kt
git commit -m "feat(tags): 会话行多标签横排显示（复用 basicMarquee 滚动）"
```

---

### Task 6: 设置页标签管理

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagManagementSection.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/ServerSettingsContent.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt`

**Interfaces:**
- Consumes: Task 2 的 Repository 接口、Task 3 的 ViewModel
- Produces:
  - `fun TagManagementSection(tags: List<Tag>, tagAssignments: Map<String, List<String>>, sessions: List<Session>, onAddTag: (Tag) -> Unit, onUpdateTag: (Tag) -> Unit, onDeleteTag: (String) -> Unit, onRemoveAssignment: (sessionId: String, tagId: String) -> Unit)`
  - `SessionListViewModel.sessionTags`（已存在）、`addSessionTag(tag)`、`updateSessionTag(tag)`、`removeSessionTag(tagId)`、`removeSessionTagAssignment(sessionId, tagId)`、`sessionTagAssignments`

- [ ] **Step 1: ViewModel 补标签管理操作**

`SessionListViewModel.kt` 增加：

```kotlin
fun addSessionTag(name: String, color: String, icon: String, id: String = "tag_${System.currentTimeMillis()}") {
    viewModelScope.launch {
        settingsRepository.addSessionTag(serverId, Tag(id = id, name = name, color = color, icon = icon, createdAt = System.currentTimeMillis()))
    }
}

fun updateSessionTag(tag: Tag) {
    viewModelScope.launch { settingsRepository.updateSessionTag(serverId, tag) }
}

fun removeSessionTag(tagId: String) {
    viewModelScope.launch { settingsRepository.removeSessionTag(serverId, tagId) }
}

fun removeSessionTagAssignment(sessionId: String, tagId: String) {
    viewModelScope.launch { settingsRepository.removeSessionTagAssignment(serverId, sessionId, tagId) }
}

val sessionTagAssignments: StateFlow<Map<String, List<String>>> =
    settingsRepository.sessionTagAssignments(serverId).stateIn(viewModelScope, WhileSubscribed5s, emptyMap())
```

> 现有 `addCategory(name, color, icon)`（227 行附近）替换为此实现（删除旧 SessionCategory 版本）。`assignCategory` 的 `Tag` 参数复用 Task 4 的自动勾选（id 预生成传入）。

- [ ] **Step 2: 创建 TagManagementSection**

`TagManagementSection.kt`（参考 `ServerSettingsContent.kt` 的 MCP 区块样式——可展开区块）：

```kotlin
@Composable
fun TagManagementSection(
    tags: List<Tag>,                                   // USER 标签
    tagAssignments: Map<String, List<String>>,         // sessionId → tagIds
    sessions: List<Session>,                           // 本服务器会话（标题查找用）
    onAddTag: (Tag) -> Unit,
    onUpdateTag: (Tag) -> Unit,
    onDeleteTag: (String) -> Unit,
    onRemoveAssignment: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedTagId by remember { mutableStateOf<String?>(null) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column {
        // 区块标题（可点击展开/收起，样式同 MCP 区块）
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.tag_management_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        if (expanded) {
            // 新建按钮
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.new_tag)) }
            // 标签列表
            tags.forEach { tag ->
                val sessionCount = tagAssignments.values.count { tag.id in it }
                Row(
                    Modifier.fillMaxWidth().clickable { expandedTagId = if (expandedTagId == tag.id) null else tag.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SessionCategoryStyle.icon(tag.icon), null, Modifier.size(18.dp), tint = SessionCategoryStyle.color(tag.color))
                    Spacer(Modifier.width(8.dp))
                    Text(tag.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("(${sessionCount})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { editingTag = tag }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                    }
                    IconButton(onClick = { onDeleteTag(tag.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
                // 展开：关联会话列表（标题 + 解除按钮）
                if (expandedTagId == tag.id) {
                    tagAssignments.filterValues { tag.id in it }.keys.forEach { sessionId ->
                        val session = sessions.firstOrNull { it.id == sessionId }
                        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(session?.title ?: sessionId.take(12), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRemoveAssignment(sessionId, tag.id) }) {
                                Text(stringResource(R.string.remove_tag))
                            }
                        }
                    }
                    if (tagAssignments.none { tag.id in it.value }) {
                        Text(stringResource(R.string.no_sessions_with_tag), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 32.dp, bottom = 4.dp))
                    }
                }
            }
        }
    }
    // 新建/编辑 dialog：名称 + 颜色 + 图标（复用 TagPickerDialog 的颜色/图标选择器结构）
    if (creating || editingTag != null) {
        TagEditDialog(
            initial = editingTag,
            onDismiss = { creating = false; editingTag = null },
            onSave = { name, color, icon ->
                val tag = editingTag ?: Tag(id = "tag_${System.currentTimeMillis()}", name = name, color = color, icon = icon, createdAt = System.currentTimeMillis())
                val updated = tag.copy(name = name, color = color, icon = icon)
                if (editingTag != null) onUpdateTag(updated) else onAddTag(updated)
                creating = false; editingTag = null
            },
        )
    }
}

@Composable
private fun TagEditDialog(
    initial: Tag?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: String, icon: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: SessionCategoryStyle.colorKeys.first()) }
    var icon by remember { mutableStateOf(initial?.icon ?: SessionCategoryStyle.iconKeys.first()) }
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(...) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(if (initial == null) R.string.new_tag else R.string.edit_tag), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.category_name)) }, singleLine = true)
                // 颜色 Row + 图标 Row（复制 SessionCategoryPickerDialog 的选择器）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), color, icon) }, enabled = name.isNotBlank()) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }
}
```

> 新增 strings：`tag_management_title`、`new_tag`、`edit_tag`、`remove_tag`、`no_sessions_with_tag`、`save`（若缺失）。先检查 `app/src/main/res/values/strings.xml` 是否已有可复用文案（如 `assign_category`、`delete_category`），优先复用；新增的走 lokit 同步（AGENTS.md：编辑字符串后运行 lokit）。

- [ ] **Step 3: 接入 ServerSettingsContent**

`ServerSettingsContent.kt`：参数增加 `tags`/`tagAssignments`/`sessions`/各回调（从 SessionListScreen 传入），LazyColumn 内 MCP 区块后追加：

```kotlin
item {
    TagManagementSection(
        tags = tags,
        tagAssignments = tagAssignments,
        sessions = sessions,
        onAddTag = onAddTag,
        onUpdateTag = onUpdateTag,
        onDeleteTag = onDeleteTag,
        onRemoveAssignment = onRemoveAssignment,
    )
}
```

- [ ] **Step 4: SessionListScreen 传参**

`SessionListScreen.kt` 292 行 `ServerSettingsContent(...)` 调用处传入：`viewModel.sessionTags` 收集值、`viewModel.sessionTagAssignments` 收集值、`uiState.sessions`、以及新增的 4 个回调（调 ViewModel 的 addSessionTag/updateSessionTag/removeSessionTag/removeSessionTagAssignment）。删除旧 `SessionCategoryPickerDialog` 的创建/删除回调（Task 4 已替换为 TagPickerDialog）。

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagManagementSection.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/ServerSettingsContent.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt
git commit -m "feat(tags): 设置页标签管理（增删改 + 展开关联会话 + 逐会话解除）"
```

---

### Task 7: 删除跨服务器入口与代码

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerSessionsScreen.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerSessionsViewModel.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerSessionsAggregator.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerFavoritesNav.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/FavoriteSessionSnapshot.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreFavorites.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStore.kt`（删除对 Favorites 扩展文件的引用注释，若有）

**Interfaces:**
- Consumes: 无（纯删除）
- Produces: 无

- [ ] **Step 1: NavGraph 移除路由与导航**

`NavGraph.kt`：删除 34 行 import、298-299 行 `composable(CrossServerFavoritesNav.route)` 块、444-445 行 `onNavigateToFavorites` 参数及 `CrossServerFavoritesNav` 常量（若在 NavGraph.kt 内定义）。

- [ ] **Step 2: SessionListScreen 移除星标入口**

`SessionListScreen.kt`：删除 126-134 行星标 IconButton（`onNavigateToFavorites`）、函数参数 `onNavigateToFavorites: () -> Unit = {}` 及其调用处（若 NavGraph 传参）。

- [ ] **Step 3: 删除文件**

删除 Task 7 Files-Delete 列出的 6 个文件。`SettingsDataStoreFavorites.kt` 中 `favoriteSessionIds`/`addFavoriteSession`/`removeFavoriteSession` 的 DataStore 层已被 Task 1 的 `SettingsDataStoreTags.kt` 取代（Repository 层 Task 2 已改）——直接删。

- [ ] **Step 4: 清理残留引用**

Run: `rg -n "CrossServerSessions|FavoriteSessionSnapshot|crossServerFavoriteOrder|favoriteSessionSnapshots|CrossServerFavoritesNav" app/src/main app/src/test`
Expected: 0 结果（除了可能的注释）

- [ ] **Step 5: 编译 + 全量测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add -A app/src/main/kotlin/dev/leonardo/ocbeacon/ui/navigation/NavGraph.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt
git add -u app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerSessionsScreen.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerSessionsViewModel.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerSessionsAggregator.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/CrossServerFavoritesNav.kt app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/FavoriteSessionSnapshot.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreFavorites.kt
git commit -m "refactor(tags): 删除跨服务器收藏/标签入口与代码（收藏统一为内置标签）"
```

---

### Task 8: 清理与回归

**Files:**
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/SessionCategory.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreCategories.kt`
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionCategoryPickerDialog.kt`

**Interfaces:**
- Consumes: 无（纯清理）

- [ ] **Step 1: 全局 grep 残留**

Run: `rg -n "SessionCategory|sessionCategories|sessionCategoryAssignments|assignSessionCategory|unassignSessionCategory|SessionCategoryPickerDialog|SessionCategoryStyle" app/src/main app/src/test`
Expected: 仅剩 `SessionCategoryStyle`（样式映射复用）与无关注释。其余全部清理。

- [ ] **Step 2: 删除废弃文件**

删除 `SessionCategory.kt`、`SettingsDataStoreCategories.kt`、`SessionCategoryPickerDialog.kt`（`SessionCategoryStyle.kt` 保留，重命名可选——为最小改动保留原名）。

- [ ] **Step 3: 全量编译 + 测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL + 全部测试通过

- [ ] **Step 4: 完整构建 + 安装**

Run: `.\gradlew :app:assembleBetaRelease`（300s）→ `adb install -r app/build/outputs/apk/beta/release/app-beta-release.apk`

- [ ] **Step 5: 真机验证清单**

1. 长按会话 → 分配标签 → 弹窗复选框多选 → 新增标签自动勾选 → 确定 → 会话行显示多个标签（横排 + 滚动）
2. 星标切换收藏（内部走内置标签）
3. 设置页 → 标签管理：新增/编辑/删除标签；点击标签展开关联会话；解除单个会话的标签
4. 删除标签后关联会话的该标签消失
5. 会话页按标签过滤正常
6. 跨服务器收藏入口消失，无崩溃

- [ ] **Step 6: 提交**

```bash
git add -u app/src/main/kotlin/dev/leonardo/ocbeacon/domain/model/SessionCategory.kt app/src/main/kotlin/dev/leonardo/ocbeacon/data/repository/SettingsDataStoreCategories.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionCategoryPickerDialog.kt
git commit -m "refactor(tags): 删除废弃分类代码（SessionCategory/Categories DataStore/PickerDialog）"
```
