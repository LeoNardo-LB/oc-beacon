# 标签系统 UX 优化设计

> 日期：2026-08-06 · 状态：已与用户对齐 · 基于 2026-08-06-session-tags-design.md 的迭代

## 1. 背景与目标

标签系统（8 任务）已交付后，用户提出 5 个 UX 优化点：

1. **多语言同步**：8 条新 strings 仅英文默认值，需跑 lokit 同步 15 种语言（AGENTS.md 要求）
2. **分配弹窗布局改造**：新增 tag 改为底部按钮 + 中间空白填充 + 空状态占位
3. **改名**：长按菜单按钮"分配 Tag"与弹窗标题"分类"统一改为"新增 Tag"
4. **长按详情弹框展示已有 tag**：一级弹框加 tag 展示行（纯展示，无 tag 不显示）
5. **设置页统一列表样式**：MCP 与标签管理两块收敛到统一组件

## 2. 需求清单（已对齐）

| # | 需求 | 决策 |
|---|------|------|
| 1 | lokit 多语言同步 | 8 条新 strings + 清理孤儿 `delete_category` |
| 2 | 弹窗新增交互 | 底部"新增 Tag"按钮 → **内联展开表单**（名称+颜色+图标+取消/添加），确认后收起并自动勾选 |
| 3 | 弹窗标签展示 | 复选框行 → **FilterChip 流式布局**（方案 A：保留 tag 颜色语义） |
| 4 | 改名 | 长按菜单按钮"分配 Tag" + 弹窗标题"分类" → 均改"新增 Tag" |
| 5 | 详情弹框 tag 行 | 纯展示（chips），无 tag 不显示该行 |
| 6 | 设置页统一组件 | 抽取区块标题 + 行组件，MCP 与标签管理共用 |
| 7 | 会话设置页列表 | 保持现状（管理页列表合理） |

## 3. FilterChip 调研结论（2026-08-06 context7 验证）

Material 3 `FilterChip` 官方 API 全覆盖所需样式，**零自定义组件**：

| 需求 | 官方参数 | 说明 |
|------|---------|------|
| 勾选图标 | `leadingIcon` | 官方示例标准用法：selected 时传 `Icons.Filled.Done`（勾选），未选中时传标签图标 |
| 选中背景加深 | `colors.selectedContainerColor` | `FilterChipDefaults.filterChipColors(selectedContainerColor = tag色加深)` |
| 选中边框实体化 | `border` | `FilterChipDefaults.filterChipBorder(selectedBorderColor = tag色, selectedBorderWidth = 1.dp)` |
| 选中文字/图标色 | `selectedLabelColor` / `selectedLeadingIconColor` | 按 tag 颜色亮度取反（白/黑），保证可读 |

Chip 流式排列：官方 `FlowRow`（`androidx.compose.foundation.layout.FlowRow`，ExperimentalLayoutApi——稳定可用）。

## 4. TagPickerDialog 改造（点 2 + 3）

```
┌─ 新增 Tag ────────────────────┐   ← 标题（原"分类"）
│                                │
│  🏷前端  🏷后端   🏷文档   │   ← FilterChip 流式布局（FlowRow）
│  🏷紧急                        │     未选中：tag色浅背景 + 浅边框
│  [✓]🏷修复中   🏷重构          │     已选中：tag色加深背景 + 实体边框 + 勾选图标
│                                │
│  （中间空白填充 + 空状态占位）   │   ← 列表区 minHeight(~160dp) + weight
│  [+ 新增 Tag]                  │   ← 底部按钮 → 内联展开表单
│  ── 展开后 ──                  │
│  [标签名称] [颜色] [图标]       │   ← 内联表单（取消/添加）
│        [取消]  [确定]          │
└────────────────────────────────┘
```

**交互细节**：
- 标签列表区：`FlowRow`（horizontalArrangement.spacedBy(8.dp)），`Modifier.weight(1f).fillMaxWidth()` + `heightIn(min = 160.dp)`——中间空白填充
- **空状态**（tags 为空）：占位组件（图标 `Icons.Outlined.Label` + 文案"暂无标签，点击下方按钮创建"），垂直居中
- 内联表单：点击"新增 Tag"按钮展开（AnimatedVisibility expandVertically）——名称输入 + 颜色/图标选择（复用现有 ColorDot/IconOption）——"添加"确认后收起 + 自动勾选（现有 onCreateTag 返回 id 机制不变）——"取消"收起
- 底部主操作栏：`[取消] [确定]`（确定保存多选结果）

**FilterChip 颜色方案 A 实现**（每个 tag 独立颜色）：

```kotlin
val tagColor = SessionCategoryStyle.color(tag.color)
val onColor = if (tagColor.luminance() > 0.5f) Color.Black else Color.White
FilterChip(
    selected = tag.id in selected,
    onClick = { selected = if (tag.id in selected) selected - tag.id else selected + tag.id },
    label = {
        Text(tag.name, style = MaterialTheme.typography.labelMedium,
            color = if (tag.id in selected) onColor else tagColor)
    },
    leadingIcon = if (tag.id in selected) {
        { Icon(Icons.Filled.Done, null, Modifier.size(FilterChipDefaults.IconSize), tint = onColor) }
    } else {
        { Icon(SessionCategoryStyle.icon(tag.icon), null, Modifier.size(FilterChipDefaults.IconSize), tint = tagColor) }
    },
    colors = FilterChipDefaults.filterChipColors(
        containerColor = tagColor.copy(alpha = AlphaTokens.FAINT),
        selectedContainerColor = tagColor.copy(alpha = AlphaTokens.HIGH),
        labelColor = tagColor,
        selectedLabelColor = onColor,
        selectedLeadingIconColor = onColor,
    ),
    border = FilterChipDefaults.filterChipBorder(
        enabled = true,
        selected = tag.id in selected,
        borderColor = tagColor.copy(alpha = AlphaTokens.FAINT),
        selectedBorderColor = tagColor,
        selectedBorderWidth = 1.dp,
    ),
)
```

**改名（点 3）**：
- 长按菜单按钮：`R.string.assign_category`（"分配 Tag"）→ 新 string `add_tag`（"新增 Tag"）
- 弹窗标题：`R.string.category`（"分类"）→ 复用 `add_tag`（或新 string `add_tag_title`）——两处统一
- 会话行行尾 chips 的语义不变（展示性）

## 5. 长按详情弹框 tag 展示行（点 4）

`SessionRow.kt` 的 `SessionDetailsDialog`：在会话标题区下方加一行：

```
if (item.tags.isNotEmpty()) {
    Row(padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = CenterVertically) {
        Text("标签", style = labelSmall, color = onSurfaceVariant)
        Spacer(width = 8.dp)
        // 复用会话行的 tag chip 渲染（抽公共私有 composable TagChipsRow）
        TagChipsRow(tags = item.tags)
    }
}
```

- **纯展示**：不响应点击
- **无 tag 不显示**该行（`if (item.tags.isNotEmpty())`）
- **抽公共组件**：`TagChipsRow(tags: List<Tag>)`（内部：Row + basicMarquee + 每 tag chip）——会话行与详情弹框共用（消除重复，同时服务点 6 的统一诉求）

## 6. 设置页统一组件（点 6）

`ServerSettingsContent.kt` 的两块（MCP 服务 + 标签管理）收敛到统一组件：

**新增 `SettingsSectionHeader.kt`**（区块标题）：

```kotlin
@Composable
fun SettingsSectionHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,   // 可选计数/操作
    modifier: Modifier = Modifier,
)
```

- 统一：`padding(horizontal = 16.dp, vertical = 12.dp)`、`titleSmall` 标题、展开/收起箭头（`KeyboardArrowDown/Right`）、可选 trailing
- 替换：ServerSettingsContent 的 MCP 标题 Row（61-84 行）与 TagManagementSection 的标题 Row（92-113 行）

**新增 `SettingsListRow.kt`**（列表行）：

```kotlin
@Composable
fun SettingsListRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

- 统一：`padding(horizontal = 16.dp, vertical = 12.dp)`（取 McpServerRow 的 12 为基准）、leading icon 间距 16dp、title `bodyLarge`、subtitle `bodySmall`（secondary 色）
- 替换：`McpServerRow` 内部行结构（保留 Switch 作 trailing）；TagManagementSection 的标签行（leading tag 图标 + name + 计数 + 编辑/删除按钮作 trailing）
- 标签管理的**二级展开**（标签 → 关联会话行）用 `SettingsListRow`（leading 会话图标 + 标题 + "解除"按钮 trailing），保持 32dp 左缩进层级

**注意**：`McpServerRow` 与 `TagManagementSection` 的对外签名尽量不变（内部改用统一组件），避免扩散改动。

## 7. 多语言（点 1）

- 新增 strings（8 条，TagManagementSection 已用）：`tag_management_title` / `new_tag` / `edit_tag` / `edit` / `remove_tag` / `no_sessions_with_tag` / `delete_tag_title` / `delete_tag_message`
- 新增：`add_tag`（"新增 Tag"，替换 `assign_category` 的引用）
- 清理孤儿：`delete_category`（被删的 SessionCategoryPickerDialog 遗留）
- 运行 `lokit` 同步 15 种语言（AGENTS.md 要求；若 lokit 不可用，报告说明并标记发版阻塞）

## 8. 测试与验证

**单元测试**：无新逻辑（纯 UI + 文案）——依赖现有测试全绿

**真机验证**：
1. 弹窗：FilterChip 多选（勾选/颜色加深/边框实体化）、底部"新增 Tag"展开内联表单、空状态占位、确定/取消语义、新增自动勾选
2. 改名：长按菜单"新增 Tag"、弹窗标题"新增 Tag"
3. 详情弹框：有 tag 会话显示 tag 行（纯展示）、无 tag 会话不显示
4. 设置页：MCP 与标签管理区块样式统一（标题/行/展开动画）
5. 多语言：切换语言验证新文案

## 9. 影响文件清单

| 动作 | 文件 |
|------|------|
| 改 | `TagPickerDialog.kt`（FilterChip 布局 + 底部按钮 + 内联表单 + 空状态） |
| 改 | `SessionRow.kt`（详情弹框 tag 行 + 抽 TagChipsRow + 按钮改名） |
| 新建 | `TagChipsRow.kt`（或并入 SessionRow——按实现方便） |
| 新建 | `SettingsSectionHeader.kt` / `SettingsListRow.kt`（统一组件） |
| 改 | `ServerSettingsContent.kt`（MCP 区块用统一组件） |
| 改 | `TagManagementSection.kt`（标题/行用统一组件） |
| 改 | `McpServerRow.kt`（内部行结构用 SettingsListRow） |
| 改 | `strings.xml`（+add_tag 等，删 assign_category/delete_category 若孤儿） |
| 运行 | `lokit` 同步 |
