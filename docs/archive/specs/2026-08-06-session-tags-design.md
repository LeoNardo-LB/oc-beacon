# 会话标签系统（Tag）设计

> 日期：2026-08-06 · 状态：已与用户对齐 · 方案：B（Tag 独立实体 + 收藏统一模型）

## 1. 背景与目标

现有"分类（SessionCategory）"系统存在三个问题：

1. **分配与新增是两步操作**：新建标签后还要再次打开弹窗点击标签才能分配（用户困惑点）
2. **单选且点选即关闭**：分配弹窗是单选，选中/取消后立即关闭，无法连续操作、无法多选
3. **无标签管理入口**：只能在长按弹窗里创建，无独立管理界面，无法按标签维度查看/管理会话

目标：

- 标签独立实体（按服务器隔离，架构支持跨服务器扩展）
- 会话 ↔ 标签多对多（复选框多选 + 显式确认）
- 设置页提供标签管理（增删改 + 按标签查看关联会话 + 解除分配）
- **收藏（favorite）与标签统一数据模型**（收藏 = 内置特殊标签），UI 保持独立
- **删除跨服务器收藏/标签入口**：只保留服务器内入口（会话列表 + 设置页）

## 2. 需求清单（已对齐）

| # | 需求 | 决策 |
|---|------|------|
| 1 | 设置页标签管理 | 服务器 pager 第二页（设置页）新增"标签管理"入口：增删改 + 点击标签展开关联会话 + 逐会话解除 |
| 2 | 新增标签后直接选中 | 分配弹窗内新增 → 创建后自动勾选 |
| 3 | 分配弹窗多选复选框 | 复选框 + "确定"按钮才保存关闭（不再点选即关） |
| 4 | 收藏 = 特殊标签 | 底层统一数据模型（内置 FAVORITE 类型标签），UI 星标独立渲染 |
| 5 | 跨服务器入口删除 | 星标导航入口、跨服务器收藏页、跨服务器页的标签功能全部移除 |
| 6 | 按服务器隔离 | 每个服务器独立标签集 + 独立分配 map |
| 7 | 旧数据 | 收藏旧数据迁移（favoriteSessionIds → 内置标签分配）；旧分类数据废弃不读 |

## 3. 数据层设计

### 3.1 Tag 实体（替代 SessionCategory）

```kotlin
@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String = "blue",
    val icon: String = "folder",
    val type: TagType = TagType.USER,
    val createdAt: Long = 0,   // 列表排序依据（按创建顺序）
)

enum class TagType { USER, FAVORITE }
```

**内置收藏标签**：每个服务器固定一个 `id = "builtin:favorite"` 的 `FAVORITE` 标签——不可删除、不可修改、不参与分配弹窗。

### 3.2 存储（DataStore Preferences，沿用现有技术）

| Key | 内容 | 格式 |
|-----|------|------|
| `session_tags_<serverId>` | 该服务器的标签集 | JSON `List<Tag>` |
| `session_tag_assignments_<serverId>` | 会话 → 标签多对多（含内置收藏） | JSON `Map<String, List<String>>`（sessionId → tagIds） |

旧 key（`session_categories` / `session_category_assignments_*` / `favorite_session_ids_*` 相关）废弃或迁移。

### 3.3 Repository API（SettingsRepository）

**标签视图（USER 类型）**：
- `sessionTags(serverId): Flow<List<Tag>>`
- `addSessionTag(serverId, tag)` / `updateSessionTag(serverId, tag)` / `removeSessionTag(serverId, tagId)`
  - `removeSessionTag` 在**同一 DataStore edit 内**清理所有会话的该标签分配（原子）

**收藏视图（FAVORITE 内置标签）**：
- `favoriteSessionIds(serverId): Flow<Set<String>>`（读内置标签分配派生，UI 星标逻辑不变）
- `toggleFavorite(serverId, sessionId)`（写内置标签分配）

**共同**：
- `sessionTagAssignments(serverId): Flow<Map<String, List<String>>>`（统一分配 map，单一真相源）
- `setSessionTags(serverId, sessionId, tagIds: Set<String>)`（幂等设置 USER 标签多选；不含内置标签）
- `removeSessionTagAssignment(serverId, sessionId, tagId)`（设置页解除单个）

### 3.4 迁移

- **收藏**：旧 `favoriteSessionIds` → 内置标签分配（简单映射，保留用户星标）
- **分类**：旧 `session_categories` 数据废弃不读（零迁移代码，新版本从空标签集开始）

## 4. UI 设计

### 4.1 分配弹窗（长按会话 → "分配 Tag"）— TagPickerDialog

- 标签列表：复选框多选（本地状态，不自动保存）
- 底部按钮："确定"（保存并关闭）/ "取消"
- 内联新增：输入名称 + 颜色/图标选择 + "添加" → 创建并**自动勾选**
- 内置收藏标签不显示
- 多选清空 = 全部取消勾选（无"无分类"选项）

### 4.2 会话行（SessionRow）

- 星标（现有 UI 不变，内部走内置标签）
- 用户标签：多个横排 + 现有 `basicMarquee` 滚动逻辑，行尾右对齐，与星标按钮并存

### 4.3 设置页"标签管理"

- 入口：设置页（pager 第二页）新增"标签管理"区块
- 标签列表：每个标签显示（图标 + 名称 + 关联会话数 + 编辑/删除按钮）
- 点击标签 → 展开关联会话列表（会话标题）→ 每个会话有"解除"按钮（`removeSessionTagAssignment`）
- 新增/编辑：名称 + 颜色 + 图标选择
- 删除：确认对话框 → 清理所有会话该标签分配
- 内置收藏标签不显示（星标是会话行的职责）

### 4.4 会话页过滤

- 现有单标签过滤保留（内部适配新数据结构），多选过滤暂不扩展

### 4.5 跨服务器入口删除

- 会话列表顶栏星标导航入口移除
- `CrossServerSessionsScreen` / `CrossServerSessionsViewModel` / `CrossServerSessionsAggregator` 及跨服务器收藏模型（`FavoriteSessionSnapshot`、`crossServerFavoriteOrder` 等）删除（确认无其他引用后）

## 5. 边界情况

| 场景 | 处理 |
|------|------|
| 删除标签 | 同一 DataStore edit 内：删标签 + 清理分配（原子） |
| 内置收藏标签 | 不可删除/修改；`setSessionTags` 只处理 USER 类型 |
| 会话删除 | 孤儿分配容忍（残留 id 不显示，无 UI 影响，惰性） |
| 服务器切换/删除 | 数据按 serverId key 独立；残留无害 |
| 标签重名 | 不做限制（以 id 区分）——YAGNI |

## 6. 测试

**单元测试（JVM）**：
- Tags CRUD + 分配 CRUD
- 删除标签 → 分配清理（原子性）
- 收藏迁移（favoriteSessionIds → 内置标签分配）
- 内置标签保护（不可删改、setSessionTags 不含内置）

**真机验证**：
- 分配弹窗：多选 + 确定才保存 + 新增即选中
- 会话行：多标签横排 + 滚动 + 星标共存
- 设置页：增删改 + 展开关联会话 + 解除
- 星标切换收藏正常
- 按标签过滤正常

## 7. 影响文件清单

| 动作 | 文件 |
|------|------|
| 重写 | `SettingsDataStoreCategories.kt` → Tags 版本 |
| 新建 | `Tag.kt`（替代 `SessionCategory.kt`） |
| 改 | `SettingsRepository.kt` / Impl、`SessionListStateBuilder.kt`、`SessionListViewModel.kt`、`SessionListScreen.kt`、`SessionRow.kt`、`TreeNode.kt`、`SessionTreeList.kt` |
| 改 | `SessionCategoryPickerDialog.kt` → `TagPickerDialog` |
| 复用 | `SessionCategoryStyle.kt`（颜色/图标映射） |
| 删除 | `CrossServerSessionsScreen/ViewModel/Aggregator` + 星标导航入口 + 跨服务器收藏模型 |
