# 会话列表状态切片（Session List State Slicing）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根治 SessionListViewModel 的 23 源 combine 魔法索引与无关全量重算——状态切片为内容册/外壳册双管线。

**Architecture:** 输入按变化频率分组（嵌套 combine，每组 ≤5 源、全具名参数）→ `SessionListDataInputs`（低频数据 12 源）+ `SessionListUiInputs`（高频 UI 7 源）→ `contentState`（列表渲染）+ `shellState`（顶栏/框架）两个独立 StateFlow。`isRefreshing`/`isLoading`/`error` 变化不再触发内容重算。

**Tech Stack:** Kotlin + Coroutines Flow（combine/stateIn）+ Jetpack Compose（collectAsStateWithLifecycle）

## Global Constraints

- 行为零变化：`mergeReadTimes`/`isUnread`/`buildTreeNodes` 纯函数逻辑不得改动（仅搬家）
- 所有 combine lambda **必须 ≤5 源具名参数**，禁止 `args[N]` 索引（否则索引魔法复发）
- `draftSessionIds` 保持 transform 内同步读取（`draftRepository.getDraftSessionIds()`）
- `baseDirectories` 字段删除（永远 emptySet 的死字段）；`prefillDirectory` 归内容册
- 聊天页（Chat*）、SSE 管线、`SessionReadSignal`、红点逻辑不触碰
- 每任务结束：`.\gradlew :app:compileDevDebugKotlin` 通过 + 相关测试通过 + commit
- JDK 21；Windows PowerShell；单测命令 `.\gradlew :app:testDevDebugUnitTest --rerun`

---
## 文件结构

| 文件 | 职责 | 变更 |
|------|------|------|
| `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt` | 状态数据类定义 | Task 1 新增 4 类；Task 3 删除 `SessionListUiState` |
| `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt` | 纯函数构建器 | Task 1 新增 `buildContentState`；Task 3 删 `buildSessionListUiState` |
| `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt` | 状态管线 | Task 2 新增双管线；Task 3 删旧 combine |
| `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt` | UI 渲染 | Task 3 双收集 |
| `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt` | 纯函数测试 | Task 1 适配 |
| `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelPaginationTest.kt` | VM 测试 | Task 2 适配 |
| `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelSearchTest.kt` | VM 测试 | Task 2 适配 |
| `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt` | shell 测试 | Task 2 新建 |

---

### Task 1: 新数据类 + buildContentState 纯函数

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt`

**Interfaces:**
- Consumes: 现有 `SessionItem`/`SessionViewMode`/`TreeNode`/`isUnread`/`mergeReadTimes`/`buildTreeNodes`（不动）
- Produces:
  - `data class SessionListDataInputs(sessions, statuses, serverSessionMap, lastUserMessageTime, categoryAssignments, sessionTags, favoritesOnly, lastReplyTime, readTimes, unreadBaseline, justRead, allReadAt)`（类型：`List<Session>` / `Map<String, SessionStatus>` / `Map<String, Set<String>>` / `Map<String, Long>` / `Map<String, List<String>>` / `List<Tag>` / `Boolean` / `Map<String, Long>` / `Map<String, Long>` / `Long` / `Map<String, Long>` / `Long`）
  - `data class SessionListUiInputs(expandedPaths, selectedIds, baseDirectory, lastToggledDirectory, searchQuery, viewMode, categoryFilterIds)`（类型：`Set<String>` ×3 + `String?` + `String?` + `SessionViewMode` + `Set<String>`）
  - `data class SessionListContentState(treeNodes, sessions, selectedIds, isSelectionMode, baseDirectory, searchQuery, prefillDirectory)`（默认值：emptyList/emptyList/emptySet/false/null/null/null）
  - `internal fun buildContentState(data: SessionListDataInputs, ui: SessionListUiInputs, serverId: String, draftRepository: DraftRepository): SessionListContentState`

- [ ] **Step 1: 在 SessionListUiState.kt 追加 4 个新数据类**（不改旧类）

```kotlin
// 低频数据输入（DataStore/服务派生，变化少）
data class SessionListDataInputs(
    val sessions: List<Session>,
    val statuses: Map<String, SessionStatus>,
    val serverSessionMap: Map<String, Set<String>>,
    val lastUserMessageTime: Map<String, Long>,
    val categoryAssignments: Map<String, List<String>>,
    val sessionTags: List<Tag>,
    val favoritesOnly: Boolean,
    val lastReplyTime: Map<String, Long>,
    val readTimes: Map<String, Long>,
    val unreadBaseline: Long,
    val justRead: Map<String, Long>,
    val allReadAt: Long,
)

// 高频 UI 输入（用户交互）
data class SessionListUiInputs(
    val expandedPaths: Set<String>,
    val selectedIds: Set<String>,
    val baseDirectory: String?,
    val lastToggledDirectory: String?,
    val searchQuery: String?,
    val viewMode: SessionViewMode,
    val categoryFilterIds: Set<String>,
)

// 内容册：列表渲染相关
data class SessionListContentState(
    val treeNodes: List<TreeNode> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val baseDirectory: String? = null,
    val searchQuery: String? = null,
    val prefillDirectory: String? = null,
)

// 外壳册：顶栏/框架相关
data class SessionListShellState(
    val serverName: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)
```

（`SessionListShellState` 也在本文件定义，Task 2 的 shell 测试要用。）

- [ ] **Step 2: 写 buildContentState 的失败测试**（在 SessionListUnreadTest.kt 追加，沿用该文件现有 fixtures/风格）

```kotlin
@Test
fun `buildContentState 保持未读判定与过滤语义`() {
    // 沿用 SessionListUnreadTest 现有 fixture：1 个会话 + 分配 tag
    val data = SessionListDataInputs(
        sessions = sessions,           // 现有 fixture
        statuses = emptyMap(),
        serverSessionMap = mapOf(SERVER_ID to sessions.map { it.id }.toSet()),
        lastUserMessageTime = emptyMap(),
        categoryAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = mapOf(sessions[0].id to 5000L),
        readTimes = mapOf(sessions[0].id to 1000L),
        unreadBaseline = 0L,
        justRead = emptyMap(),
        allReadAt = 0L,
    )
    val ui = SessionListUiInputs(
        expandedPaths = emptySet(),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = null,
        viewMode = SessionViewMode.RECENT,
        categoryFilterIds = emptySet(),
    )
    val state = buildContentState(data, ui, SERVER_ID, draftRepository)
    assertTrue(state.treeNodes.singleOrNull()?.let {
        it is TreeNode.Session && it.session.hasUnread
    } ?: false)
    assertEquals(5000L, data.lastReplyTime[sessions[0].id])
}
```

（若文件无 `SERVER_ID`/`draftRepository` fixture，用现有测试中的等价值。）

- [ ] **Step 3: 运行确认失败**

Run: `.\gradlew :app:compileDevDebugUnitTestKotlin`
Expected: FAIL — `buildContentState` 未定义

- [ ] **Step 4: 实现 buildContentState**（SessionListStateBuilder.kt 追加；逻辑与旧 buildSessionListUiState 逐行一致，仅输入改为具名）

```kotlin
/**
 * 内容册构建纯函数——从 [SessionListDataInputs] + [SessionListUiInputs] 构建列表渲染状态。
 * 逻辑与旧 buildSessionListUiState 完全一致（过滤/搜索/分类/收藏/树构建/未读）。
 * 外壳字段（isLoading/isRefreshing/error/serverName）不再进入此函数。
 */
internal fun buildContentState(
    data: SessionListDataInputs,
    ui: SessionListUiInputs,
    serverId: String,
    draftRepository: DraftRepository,
): SessionListContentState {
    val readTimes = mergeReadTimes(data.readTimes, data.justRead)

    val serverSessionIds = data.serverSessionMap[serverId].orEmpty()

    val filteredSessions = data.sessions
        .filter { it.id in serverSessionIds && it.parentId == null }
        .sortedByDescending { session ->
            data.lastUserMessageTime[session.id] ?: session.time.updated
        }

    val baseFilteredSessions = if (ui.baseDirectory != null) {
        filteredSessions.filter { session ->
            val dir = session.directory.replace('\\', '/').trimEnd('/')
            dir.startsWith(ui.baseDirectory)
        }
    } else {
        filteredSessions
    }

    val searchedSessions = if (!ui.searchQuery.isNullOrBlank()) {
        val query = ui.searchQuery.lowercase()
        baseFilteredSessions.filter { session ->
            session.directory.lowercase().contains(query) ||
                session.title?.lowercase()?.contains(query) == true
        }
    } else {
        baseFilteredSessions
    }

    val categoryFilteredSessions = if (ui.categoryFilterIds.isEmpty()) {
        searchedSessions
    } else {
        searchedSessions.filter { session ->
            val sessionTags = data.categoryAssignments[session.id].orEmpty()
            ui.categoryFilterIds.all { it in sessionTags }
        }
    }

    val favoritesFilteredSessions = if (data.favoritesOnly) {
        val favoriteIds = data.categoryAssignments
            .filterValues { dev.leonardo.ocbeacon.domain.model.FAVORITE_TAG_ID in it }
            .keys
        categoryFilteredSessions.filter { it.id in favoriteIds }
    } else {
        categoryFilteredSessions
    }

    val tagsById = data.sessionTags.associateBy { it.id }
    val resolvedTags: Map<String, List<Tag>> = buildMap {
        data.categoryAssignments.forEach { (sessionId, tagIds) ->
            put(sessionId, tagIds.mapNotNull { tagsById[it] })
        }
    }

    val treeNodes = if (ui.viewMode == SessionViewMode.RECENT) {
        favoritesFilteredSessions.map { session ->
            TreeNode.Session(
                id = session.id,
                session = SessionItem(
                    session = session,
                    status = data.statuses[session.id] ?: SessionStatus.Idle,
                    hasDraft = session.id in draftRepository.getDraftSessionIds(),
                    tags = resolvedTags[session.id].orEmpty(),
                    hasUnread = isUnread(session.id, data.lastReplyTime, readTimes, data.unreadBaseline, data.allReadAt)
                )
            )
        }
    } else {
        buildTreeNodes(favoritesFilteredSessions, ui.expandedPaths, ui.baseDirectory, data.statuses, draftRepository.getDraftSessionIds(), resolvedTags, data.lastReplyTime, readTimes, data.unreadBaseline, data.allReadAt)
    }

    val prefillDirectory = if (ui.lastToggledDirectory != null && ui.lastToggledDirectory in ui.expandedPaths)
        ui.lastToggledDirectory
    else
        ui.baseDirectory

    return SessionListContentState(
        treeNodes = treeNodes,
        sessions = filteredSessions,
        selectedIds = ui.selectedIds,
        isSelectionMode = ui.selectedIds.isNotEmpty(),
        baseDirectory = ui.baseDirectory,
        searchQuery = ui.searchQuery,
        prefillDirectory = prefillDirectory,
    )
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "*SessionListUnreadTest*"`
Expected: PASS（含新增用例）

- [ ] **Step 6: 编译检查 + Commit**

```bash
.\gradlew :app:compileDevDebugKotlin
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUnreadTest.kt
git commit -m "refactor: #23 状态切片——新增输入/输出数据类与 buildContentState 纯函数"
```

---

### Task 2: ViewModel 双管线（contentState/shellState）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt:203-229`（uiState combine 区段）
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelPaginationTest.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModelSearchTest.kt`
- Create: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListShellStateTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `SessionListDataInputs`/`SessionListUiInputs`/`SessionListContentState`/`SessionListShellState`/`buildContentState`
- Produces:
  - `val contentState: StateFlow<SessionListContentState>`（stateIn viewModelScope, WhileSubscribed5s）
  - `val shellState: StateFlow<SessionListShellState>`（stateIn viewModelScope, WhileSubscribed5s）
  - 旧 `uiState` 在 Task 3 删除——Task 2 期间**并存**

- [ ] **Step 1: 写 shell 失败测试**（新建 SessionListShellStateTest.kt）

```kotlin
package dev.leonardo.ocbeacon.ui.screens.sessions

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListShellStateTest {
    @Test
    fun `shellState 反映 loading 与 error 翻转`() = runTest {
        // 用与 SessionListViewModelSearchTest 相同的 ViewModel 构造方式（复制其 setup/依赖 mock）
        // 假设可构造 vm（沿用现有测试的 mock 工厂）
        val vm = /* 现有测试的构造方式 */
        vm.shellState.test {
            // 初始：isLoading 由构造决定（参考现有测试断言惯例）
            val initial = awaitItem()
            assertEquals(false, initial.isRefreshing)
            // error 翻转（触发 vm 内部 error 写入——参考现有测试如何注入错误）
            // 若无法注入错误，仅断言初始字段即可（加载完成后 isLoading=false）
        }
    }
}
```

（若 ViewModel 构造需要大量 mock 依赖，直接复制 `SessionListViewModelSearchTest` 的 setup 代码。若 error 注入在当前测试基建中不可行，将测试简化为：构造后 `shellState` 各字段默认值正确 + `contentState` 非空——核心断言是**两个流独立存在**。）

- [ ] **Step 2: 运行确认失败**

Run: `.\gradlew :app:compileDevDebugUnitTestKotlin`
Expected: FAIL — `vm.contentState`/`vm.shellState` 不存在

- [ ] **Step 3: 实现 ViewModel 双管线**（在 `uiState` combine 之前/之后追加；uiState 保持不动）

```kotlin
// ============ 聚合 UI 状态（#23 状态切片） ============

// 分组设计：每组只携带自己拥有的字段（部分数据类），最终 dataFlow 合并 3 组。
// 禁止"占位填充"（会重置其他组的字段）。

// 分组1：会话数据（5 源）→ 部分字段
private data class SessionDataPart(
    val sessions: List<Session>,
    val statuses: Map<String, SessionStatus>,
    val serverSessionMap: Map<String, Set<String>>,
    val lastUserMessageTime: Map<String, Long>,
    val lastReplyTime: Map<String, Long>,
)

private val sessionDataFlow = combine(
    sessionRepository.getSessionsFlow(serverId),
    sessionStateService.statusFlow,
    sessionRepository.getServerSessionsFlow(),
    sessionRepository.getLastUserMessageTimeFlow(),
    sessionRepository.getLastReplyTimeFlow(),
) { sessions, statuses, serverSessionMap, lastUserMessageTime, lastReplyTime ->
    SessionDataPart(sessions, statuses, serverSessionMap, lastUserMessageTime, lastReplyTime)
}
```

// 分组2：设置数据（5 源）
private data class SettingDataPart(
    val categoryAssignments: Map<String, List<String>>,
    val sessionTags: List<Tag>,
    val readTimes: Map<String, Long>,
    val unreadBaseline: Long,
    val justRead: Map<String, Long>,
)

private val settingDataFlow = combine(
    settingsRepository.sessionTagAssignments(serverId),
    sessionTags,
    settingsRepository.sessionReadTimes(serverId),
    _unreadBaseline,
    sessionReadSignal.justRead,
) { assignments, tags, readTimes, baseline, justRead ->
    SettingDataPart(assignments, tags, readTimes, baseline, justRead)
}

// 分组3：杂项（2 源）
private data class MiscDataPart(
    val favoritesOnly: Boolean,
    val allReadAt: Long,
)

private val miscDataFlow = combine(
    _favoritesOnly,
    settingsRepository.allReadAt(serverId),
) { favoritesOnly, allReadAt ->
    MiscDataPart(favoritesOnly, allReadAt)
}

// 数据流：3 组合并（3 源具名）
private val dataFlow = combine(
    sessionDataFlow, settingDataFlow, miscDataFlow,
) { sessionData, settingData, miscData ->
    SessionListDataInputs(
        sessions = sessionData.sessions,
        statuses = sessionData.statuses,
        serverSessionMap = sessionData.serverSessionMap,
        lastUserMessageTime = sessionData.lastUserMessageTime,
        categoryAssignments = settingData.categoryAssignments,
        sessionTags = settingData.sessionTags,
        favoritesOnly = miscData.favoritesOnly,
        lastReplyTime = sessionData.lastReplyTime,
        readTimes = settingData.readTimes,
        unreadBaseline = settingData.unreadBaseline,
        justRead = settingData.justRead,
        allReadAt = miscData.allReadAt,
    )
}

// UI 流：2 组合并
private data class UiGroup1Part(
    val expandedPaths: Set<String>,
    val selectedIds: Set<String>,
    val baseDirectory: String?,
    val lastToggledDirectory: String?,
)

private data class UiGroup2Part(
    val searchQuery: String?,
    val viewMode: SessionViewMode,
    val categoryFilterIds: Set<String>,
)

private val uiFlow = combine(
    combine(
        _expandedPaths, _selectedIds, _baseDirectory, _lastToggledDirectory,
    ) { expandedPaths, selectedIds, baseDirectory, lastToggledDirectory ->
        UiGroup1Part(expandedPaths, selectedIds, baseDirectory, lastToggledDirectory)
    },
    combine(
        _searchQuery, _viewMode, _categoryFilters,
    ) { searchQuery, viewMode, categoryFilterIds ->
        UiGroup2Part(searchQuery, viewMode, categoryFilterIds)
    },
) { g1, g2 ->
    SessionListUiInputs(
        expandedPaths = g1.expandedPaths,
        selectedIds = g1.selectedIds,
        baseDirectory = g1.baseDirectory,
        lastToggledDirectory = g1.lastToggledDirectory,
        searchQuery = g2.searchQuery,
        viewMode = g2.viewMode,
        categoryFilterIds = g2.categoryFilterIds,
    )
}

// 内容册（最终）
val contentState: StateFlow<SessionListContentState> = combine(
    dataFlow, uiFlow,
) { data, ui ->
    buildContentState(data, ui, serverId, draftRepository)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionListContentState())

// 外壳册（独立）
val shellState: StateFlow<SessionListShellState> = combine(
    _isLoading, _isRefreshing, _error,
) { isLoading, isRefreshing, error ->
    SessionListShellState(
        serverName = serverName,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        error = error,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionListShellState())
```

需要的 import：`SharingStarted`（若未导入）、`Tag`（若未导入）。`SessionListDataInputs` 等与 `SessionListUiState` 同包，无需 import。

- [ ] **Step 4: 适配现有 VM 测试**（PaginationTest/SearchTest 中 `uiState` 断言改为 `contentState`；在旧 uiState 删除前先验证新管线）

对每个测试文件：
1. 把 `vm.uiState.` 断言改为 `vm.contentState.`（内容相关字段：treeNodes/sessions/searchQuery/baseDirectory/selectedIds/prefillDirectory）
2. 若断言涉及 `isLoading`/`isRefreshing`/`error`/`serverName` → 改为 `vm.shellState.`
3. Turbine 收集改 `vm.contentState.test { ... }` / `vm.shellState.test { ... }`

- [ ] **Step 5: 全量单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL（新 ShellStateTest + 适配后的 Pagination/Search + 既有全部测试）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/
git commit -m "refactor: #23 状态切片——ViewModel 双管线（contentState/shellState 嵌套分组 combine）"
```

---

### Task 3: Screen 双收集 + 删除旧管线

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListScreen.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt:203-229`（删除旧 uiState combine）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt:36-170`（删除 buildSessionListUiState）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt`（删除 SessionListUiState 类）

**Interfaces:**
- Consumes: Task 2 的 `contentState`/`shellState`
- Produces: 无（终态）

- [ ] **Step 1: Screen 双收集**

```kotlin
// L72 处替换
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
// 改为：
val content by viewModel.contentState.collectAsStateWithLifecycle()
val shell by viewModel.shellState.collectAsStateWithLifecycle()
```

- [ ] **Step 2: 读取点机械适配**（替换表）

| 原引用 | 新引用 |
|--------|--------|
| L126 `uiState.serverName` | `shell.serverName` |
| L155 `uiState.sessions.isNotEmpty()` | `content.sessions.isNotEmpty()` |
| L271 `uiState.isRefreshing` | `shell.isRefreshing` |
| L296 `uiState.isLoading && uiState.treeNodes.isEmpty() && uiState.searchQuery.isNullOrBlank()` | `shell.isLoading && content.treeNodes.isEmpty() && content.searchQuery.isNullOrBlank()` |
| L299 `uiState.error != null && uiState.treeNodes.isEmpty()` | `shell.error != null && content.treeNodes.isEmpty()` |
| L301 `uiState.error` | `shell.error` |
| L305 `uiState.treeNodes.isEmpty()` | `content.treeNodes.isEmpty()` |
| L311 `uiState.treeNodes` | `content.treeNodes` |
| L350 `uiState.sessions` | `content.sessions` |
| L368 `uiState.prefillDirectory` | `content.prefillDirectory` |
| L380 `uiState.sessions` | `content.sessions` |

（`currentViewMode`/`favoritesOnly`/`categoryFilters`/`sessionTags` 等直接收集的独立流不变。）

- [ ] **Step 3: 删除旧管线**（三处联动，一次性删否则编译失败）

1. ViewModel：删除旧 `uiState` combine 块（L203-229）+ `@Suppress("UNCHECKED_CAST")`（若仅此处使用）+ 旧 imports
2. StateBuilder：删除 `buildSessionListUiState`（含 values 索引注释块）
3. SessionListUiState.kt：删除 `SessionListUiState` data class（保留 SessionItem/SessionViewMode/TAG_SESSION_LIST_VM/新数据类）

- [ ] **Step 4: 编译 + 全量单测**

Run: `.\gradlew :app:compileDevDebugKotlin`
Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: BUILD SUCCESSFUL 两次

- [ ] **Step 5: 搜索残留**

Run: `rg "uiState|buildSessionListUiState|SessionListUiState" app/src/main app/src/test`
Expected: 仅剩 `shellState`/`contentState` 相关命名（无 `SessionListUiState` 引用）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/
git commit -m "refactor: #23 状态切片——Screen 双收集，删除旧 uiState 管线与魔法索引 builder"
```

---

### Task 4: 构建 + 真机回归

**Files:** 无代码变更

- [ ] **Step 1: 构建安装**

Run: `.\gradlew :app:assembleDevDebug`
Expected: BUILD SUCCESSFUL；APK 输出 `app/build/outputs/apk/dev/debug/app-dev-debug.apk`

- [ ] **Step 2: 安装到模拟器**（emulator-5554）

```bash
adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

- [ ] **Step 3: 回归**（复用既有回归资产）

1. 手动/复刻：列表加载（内容渲染）、搜索打字（contentState 更新）、下拉刷新（shell.isRefreshing 转圈且**列表不闪**）、tag 多选过滤、视图切换、未读红点出现/消费
2. 如服务器可用：`maestro/regression-unread-chain-a/b.yaml`（或等价手动流程）
3. 关注点：刷新时列表**不重建闪烁**（本次根治的直接收益，肉眼验证）

- [ ] **Step 4: 收尾**

1. 更新 backlog：#23 标记完成（勾选条目内 checkbox）
2. Commit backlog.md：

```bash
git add backlog.md
git commit -m "chore: backlog #23 完成（状态切片落地）"
```

---

## Self-Review 记录

- **Spec 覆盖**：§3.1 输入分组 → Task 2 分组 combine；§3.2 输出拆分 → Task 1 数据类 + Task 3 删除；§3.3 管线 → Task 2；§4 UI 适配 → Task 3 Step1-2；§5 测试 → Task 1/2；§8 验收 1（无索引）→ Task 2 具名参数 + Task 3 删除；验收 2（shell 独立）→ Task 2 结构 + ShellStateTest；验收 3 → Task 4；验收 4 → 数据类设计
- **占位符扫描**：ShellStateTest 的 ViewModel 构造依赖现有测试基建（明确指引复制 SearchTest setup，非 TBD）
- **类型一致性**：`SessionListDataInputs` 12 字段 / `SessionListUiInputs` 7 字段 / `buildContentState` 签名在 Task 1 定义、Task 2/3 引用一致；`contentState`/`shellState` 命名一致
