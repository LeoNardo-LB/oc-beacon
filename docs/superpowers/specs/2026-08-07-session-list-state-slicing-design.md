# 设计：会话列表状态切片（Session List State Slicing）

- 日期：2026-08-07
- 关联：backlog #23（SessionList combine 魔法索引 tuple 化重构，升级为根治方案）
- 状态：已评审通过（brainstorming 对话确认）

## 1. 背景与问题

`SessionListViewModel.uiState` 由 **23 个源的单一 combine** 构建，transform 内通过 `buildSessionListUiState(values: Array<Any?>, ...)` 用**魔法索引**（`values[0..22]`）取数。两个根问题：

1. **索引错位 bug（高发）**：加源/删源/调整顺序时索引整体偏移，编译期不报错，运行时 ClassCastException 或静默算错。2026-08-07 未读红点功能加 4 个源时踩坑 3 次。
2. **全量重算（CPU 浪费）**：任一源发射 → transform 全量重建（tag 解析 + 树构建 + 过滤），即使只有 `isRefreshing`/`isLoading`/`error` 这类不影响列表内容的源变化。Compose 层已按值 equals 防重画，但重算本身仍有开销，且规模增大（几百会话）时感知明显。

## 2. 目标与范围

- **根治**索引错位 bug：魔法索引 → 具名 data class
- **根治**无关重算：外壳状态（loading/refreshing/error）变化不触发内容重算
- 纯重构：**行为零变化**，不触碰红点逻辑（`isUnread`/`mergeReadTimes` 纯函数仅搬家）
- 范围：会话列表页（SessionList 系列）。**聊天页不在范围**（ChatViewModel 已按域拆分，无同类问题）

## 3. 方案：状态切片（双管线 + 外壳独立）

### 3.1 输入分组（按变化频率）

**`SessionListDataInputs`**（低频数据，12 源，combine 生成）：

| 字段 | 源 |
|------|-----|
| sessions | `getSessionsFlow(serverId)` |
| statuses | `sessionStateService.statusFlow` |
| serverSessionMap | `getServerSessionsFlow()` |
| lastUserMessageTime | `getLastUserMessageTimeFlow()` |
| categoryAssignments | `settingsRepository.sessionTagAssignments(serverId)` |
| sessionTags | `sessionTags` |
| favoritesOnly | `_favoritesOnly` |
| lastReplyTime | `getLastReplyTimeFlow()` |
| readTimes | `settingsRepository.sessionReadTimes(serverId)` |
| unreadBaseline | `_unreadBaseline` |
| justRead | `sessionReadSignal.justRead` |
| allReadAt | `settingsRepository.allReadAt(serverId)` |

**`SessionListUiInputs`**（高频 UI 状态，7 源，combine 生成）：

| 字段 | 源 |
|------|-----|
| expandedPaths | `_expandedPaths` |
| selectedIds | `_selectedIds` |
| baseDirectory | `_baseDirectory` |
| lastToggledDirectory | `_lastToggledDirectory` |
| searchQuery | `_searchQuery` |
| viewMode | `_viewMode` |
| categoryFilterIds | `_categoryFilters` |

`draftSessionIds` 保持 transform 内同步读取（`draftRepository.getDraftSessionIds()`，现状不变）。

### 3.2 输出拆分

**`SessionListContentState`**（内容册，列表渲染相关）：
`treeNodes`、`sessions`、`selectedIds`、`isSelectionMode`、`baseDirectory`、`searchQuery`、`prefillDirectory`

**`SessionListShellState`**（外壳册，顶栏/框架相关）：
`serverName`、`isLoading`、`isRefreshing`、`error`

归属说明：
- `prefillDirectory` 归内容册——它由 `lastToggledDirectory`/`expandedPaths`（UI 输入）派生，放外壳册会造成跨册依赖；UI 从 content 读新建会话对话框初值
- `baseDirectories` **删除**（自审发现：StateBuilder L165 硬编码 `emptySet()`，永远为空的死字段）

### 3.3 管线

```
dataFlow  = combine(12 源) → SessionListDataInputs
uiFlow    = combine(7 源)  → SessionListUiInputs
contentState = combine(dataFlow, uiFlow)
               → buildContentState(data, ui, serverId, serverName, draftRepository)
               .stateIn(viewModelScope, WhileSubscribed5s, SessionListContentState())
shellState = combine(_isLoading, _isRefreshing, _error)
             → SessionListShellState(loading, refreshing, error, serverName)
             .stateIn(viewModelScope, WhileSubscribed5s, SessionListShellState())
```

根治点：`isRefreshing`/`isLoading`/`error` 变化 → 不进内容管线 → tag 解析/树构建零开销。

### 3.4 文件变更

| 文件 | 变更 |
|------|------|
| `SessionListUiState.kt` | 删除 `SessionListUiState`（字段迁入两个新数据类）；`SessionItem` 保留；新增 `SessionListDataInputs`/`SessionListUiInputs`/`SessionListContentState`/`SessionListShellState` |
| `SessionListStateBuilder.kt` | `buildSessionListUiState(values)` → `buildContentState(data: SessionListDataInputs, ui: SessionListUiInputs, ...)`；`mergeReadTimes`/`isUnread`/`buildTreeNodes` 原样保留 |
| `SessionListViewModel.kt` | 删除 23 源 combine；新增 dataFlow/uiFlow/contentState/shellState 四条管线；`consumePendingReadSessionId`/`scrollSignal` 等一次性信号不变 |
| `SessionListScreen.kt` | 双收集（content/shell），读取点机械适配 |
| 测试 4 个文件 | 见 §5 |

## 4. UI 适配（SessionListScreen）

```kotlin
val content by viewModel.contentState.collectAsStateWithLifecycle()
val shell by viewModel.shellState.collectAsStateWithLifecycle()
```

- 列表区（treeNodes/sessions/空态）读 content；顶栏（serverName/isRefreshing）读 shell
- 空态三态判定（loading/error/empty）混读两者（同一 composable 内读两个 state，重组粒度由 Compose 按读取点自动划分）
- 机械适配点：原 `uiState.serverName`(L126)、`uiState.sessions`(L155)、`uiState.isRefreshing`(L271)、L296-311 三态、L350/368/380 对话框

## 5. 测试适配

| 测试 | 改动 |
|------|------|
| `SessionListUnreadTest`（纯函数，已存在） | 构造 `SessionListDataInputs` + `SessionListUiInputs` 调用 `buildContentState` |
| `SessionListViewModelPaginationTest` | 断言 `contentState` |
| `SessionListViewModelSearchTest` | 断言 `contentState` |
| 新测试 | `SessionListShellStateTest`（loading/error/refreshing 翻转）；若 ViewModel 层缺未读断言则补 |

## 6. 风险与验证

- **风险**：字段归属错位（编译期类型安全兜底）；UI 重组行为变化（需真机/截图回归）
- **验证链**：编译 ✅ → 全量单测（`testDevDebugUnitTest --rerun`）✅ → 构建安装 ✅ → Maestro/手动回归（列表加载/搜索/过滤/刷新/视图切换/未读红点，复用 `maestro/regression-unread-chain-a/b.yaml`）
- **不触碰**：`SessionReadSignal`、markRead 双保险、红点纯函数（仅搬家）、SSE 管线、聊天页

## 7. 已知边界（不做事项）

- 内容册内部任一输入变（搜索打字/statuses 翻转）→ 内容册全量重算（tag 解析 + 树构建）：方案 A 固有边界，根治需 memo 化（方案 B，YAGNI 不做）
- 不引入 UI 层直接订阅 `sessionStateService.statusFlow`（打破"UI 只读 UiState"惯例）
- `ChatStateAggregator.uiState` legacy 兼容层（聊天页，6 源轻量聚合，有注释）不动

## 8. 验收标准

1. 无 `values[N]` 魔法索引残留（会话列表管线）
2. `isRefreshing`/`isLoading`/`error` 变化不触发 contentState 重建（代码结构保证，测试覆盖 shell 翻转）
3. 全量单测通过；真机回归无行为差异
4. 新增源只需改 data class + combine 源列表（无索引偏移）
