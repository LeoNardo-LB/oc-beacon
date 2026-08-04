# Context 工具聚合卡片设计

## 概述

将连续的只读探索工具调用（`read` / `glob` / `grep`）聚合为一个可折叠的 SegmentedListItem 卡片，折叠时显示"已探索 N 个文件 · M 次搜索"，展开后列出每个工具的摘要信息。

## 背景

当前 Android 客户端逐个渲染 `Part.Tool`，每个 read/glob/grep 调用都是独立卡片。当 agent 连续探索代码库时（如连续读取 5 个文件 + 2 次 glob 搜索），会产生 7 个独立卡片，视觉冗长。

OpenCode WebUI 通过 `groupParts` 算法将连续的 context 工具合并为一个可折叠卡片（`packages/ui/src/components/message-part.tsx:570-612`），标题显示 "Explored" + 动态计数。

## 设计目标

- 视觉紧凑：连续探索工具合并为一个卡片
- Material 3 原生组件：使用 SegmentedListItem（Expressive API）
- 保留交互：展开后 read 行点击可打开文件查看器
- 最小改动：不破坏现有工具卡片、消息分割线逻辑

## 技术方案

### 1. 分组算法 — `PartGrouper.kt`

**位置**：`ui/screens/chat/tools/PartGrouper.kt`

**逻辑**：线性扫描 parts 序列，将连续的 context 工具（`read` / `glob` / `grep`）合并为一个组。遇到非 context 工具（bash/edit/write/task 等）或非 Tool 类型的 part（text/reasoning）时切断分组。

```kotlin
val CONTEXT_TOOLS = setOf("read", "glob", "grep")

sealed class PartGroup {
    /** 连续的 context 工具，至少 2 个才聚合（单个不聚合，保持原卡片） */
    data class Context(val parts: List<Part.Tool>) : PartGroup()
    /** 非聚合 part，原样渲染 */
    data class Single(val part: Part) : PartGroup()
}

fun groupContextParts(parts: List<Part>): List<PartGroup> {
    val result = mutableListOf<PartGroup>()
    val buffer = mutableListOf<Part.Tool>()

    fun flush() {
        if (buffer.size >= 2) {
            result.add(PartGroup.Context(buffer.toList()))
        } else {
            buffer.forEach { result.add(PartGroup.Single(it)) }
        }
        buffer.clear()
    }

    for (part in parts) {
        if (part is Part.Tool && part.tool.lowercase() in CONTEXT_TOOLS) {
            buffer.add(part)
        } else {
            flush()
            result.add(PartGroup.Single(part))
        }
    }
    flush()
    return result
}
```

**设计决策 — 单个不聚合**：只有 1 个 context 工具时不聚合（`buffer.size >= 2`），直接作为独立卡片渲染。聚合的意义在于减少视觉噪音，单个工具聚合反而增加了一次点击才能看到内容。

### 2. 聚合卡片 — `ContextToolGroupCard.kt`

**位置**：`ui/screens/chat/tools/ContextToolGroupCard.kt`

**组件**：`SegmentedListItem`（`@ExperimentalMaterial3ExpressiveApi`）+ `AnimatedVisibility`

```
折叠态（进行中）：
╭────────────────────────────────────╮
│ 🔍 探索中   2 个文件 · 1 次搜索  ▸ │  ← TextShimmer
╰────────────────────────────────────╯

折叠态（完成）：
╭────────────────────────────────────╮
│ ✓ 已探索   2 个文件 · 1 次搜索   ▸ │
╰────────────────────────────────────╯

展开态：
╭────────────────────────────────────╮
│ ✓ 已探索   2 个文件 · 1 次搜索   ▾ │
├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤  ← SegmentedGap
│ 👓 读取     Main.kt                │  ← 可点击打开文件查看器
├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
│ 👓 读取     Utils.kt               │  ← 可点击打开文件查看器
├ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤
│ 🔎 查找文件 **/*.kt                │
╰────────────────────────────────────╯
```

**头部**：
- `leadingContent`：进行中用 `Icons.Default.Search`（primary 色），完成用 `Icons.Default.CheckCircle`（onSurfaceVariant 色）
- `content`：`"探索中"` / `"已探索"`（i18n）
- `supportingContent`：计数摘要文本，如 `"2 个文件 · 1 次搜索"`
- `trailingContent`：`Icons.Default.ExpandMore` / `Icons.Default.ExpandLess`

**计数摘要**（`contextToolSummary`）：
```kotlin
data class ContextSummary(val read: Int, val search: Int)

fun contextToolSummary(parts: List<Part.Tool>): ContextSummary {
    val read = parts.count { it.tool.lowercase() == "read" }
    val search = parts.count { it.tool.lowercase() in setOf("glob", "grep") }
    return ContextSummary(read, search)
}
```

摘要文本规则（0 值省略）：
```
"2 个文件 · 1 次搜索"
"3 个文件"              // search=0
"2 次搜索"              // read=0
```

**展开后子项**：
- 每行一个 SegmentedListItem，`segmentedShapes(index, count)` 自动计算圆角
- read 行：`leadingContent` = 读取图标，`content` = `"读取"`，`supportingContent` = 文件名，`onClick` → `onOpenFile(filePath)`
- glob 行：`leadingContent` = 查找图标，`content` = `"查找文件"`，`supportingContent` = pattern
- grep 行：`leadingContent` = 搜索图标，`content` = `"搜索代码"`，`supportingContent` = pattern

**pending 判断**：
```kotlin
val pending = parts.any {
    it.state is ToolState.Running || it.state is ToolState.Pending
}
```

### 3. 改动 — `MessageCard.kt`

**位置**：`MessageCard.kt` 的 `MessageCardAssistant` composable，parts 遍历循环（约 386-412 行）

**当前**：
```kotlin
for ((msgIndex, msg) in turnMsgs.withIndex()) {
    val msgParts = filterRenderableParts(msg.parts)
    for (part in msgParts) {
        key(part.id) { PartContent(part, ...) }
    }
    if (msgIndex < turnMsgs.lastIndex && msgParts.isNotEmpty()) {
        HorizontalDivider(...)
    }
}
```

**改后**：
```kotlin
for ((msgIndex, msg) in turnMsgs.withIndex()) {
    val msgParts = filterRenderableParts(msg.parts)
    val groups = groupContextParts(msgParts)
    for (group in groups) {
        when (group) {
            is PartGroup.Context -> key(group.parts.first().id) {
                ContextToolGroupCard(
                    parts = group.parts,
                    onOpenFile = onOpenFile,
                )
            }
            is PartGroup.Single -> key(group.part.id) {
                PartContent(part = group.part, ...)
            }
        }
    }
    if (msgIndex < turnMsgs.lastIndex && msgParts.isNotEmpty()) {
        HorizontalDivider(...)
    }
}
```

**不变**：消息间分割线、`filterRenderableParts`、非 context 工具的渲染逻辑。

### 4. i18n 字符串

`res/values/strings.xml`（+ 14 个 locale）：

```xml
<string name="context_exploring">探索中</string>
<string name="context_explored">已探索</string>
<string name="context_read_count">%1$d 个文件</string>
<string name="context_search_count">%1$d 次搜索</string>
```

## 数据流

```
SSE → MessageWithParts(parts: List<Part>)
  │
  ▼
MessageCardAssistant
  │  filterRenderableParts(msg.parts)    ← 过滤空 text/隐藏 tool
  │  groupContextParts(filteredParts)    ← 【新增】分组
  ▼
List<PartGroup>
  │  Context → ContextToolGroupCard      ← 【新增】聚合卡片
  │  Single  → PartContent(part)         ← 原逻辑不变
  ▼
UI 时间线
```

## 设计决策

### D1：单消息内分组，不跨消息

在 `MessageCardAssistant` 的每条消息内部做分组，不 flatMap 跨消息。原因：
- 不破坏消息间分割线逻辑
- OpenCode agent 通常在单条消息内连续调用探索工具
- 改动范围最小

### D2：单个 context 工具不聚合

`buffer.size >= 2` 才聚合。单个 read/glob/grep 保持独立卡片。原因：
- 聚合的意义是减少视觉噪音，单个工具聚合反而增加点击成本
- 1 个工具用独立卡片能直接展示详情，体验更好

### D3：展开后子项纯摘要，不嵌套完整卡片

展开后每行只显示图标 + 工具类型 + 文件名/pattern，不嵌入完整的 ReadToolCard/GlobToolCard。原因：
- SegmentedListItem 的设计是紧凑列表项，嵌套完整卡片会破坏视觉一致性
- glob/grep 的详细结果（匹配文件列表、搜索结果）不适合在列表项中展示
- read 行保留点击打开文件查看器的交互（这是最主要的用户需求）

### D4：list 工具不存在

OpenCode 服务端没有独立的 `list` 工具——目录列表功能已合并到 `read`（当 filePath 是目录时返回条目列表）。WebUI 代码中的 `"list"` 是旧版遗留。因此只聚合 read/glob/grep。

## 改动范围

| 文件 | 操作 | 行数估 |
|------|------|--------|
| `tools/PartGrouper.kt` | 新增 | ~50 |
| `tools/ContextToolGroupCard.kt` | 新增 | ~130 |
| `components/MessageCard.kt` | 修改 parts 遍历 | ~15 行改动 |
| `res/values*/strings.xml` | 新增 4 个字符串 | 15 locales × 4 |

**不触碰**：ReadToolCard、GlobToolCard、SearchToolCard、ToolCardResolver、PartContent、ChatMessageList。

## 风险与缓解

| 风险 | 缓解 |
|------|------|
| SegmentedListItem 是 Expressive 实验性 API | `@OptIn` 注解；BOM 2026.05.01 已稳定包含；最坏情况改参数名 |
| 聚合卡片展开/折叠导致高度变化 | 用户主动点击触发，不影响 SSE 滚动补偿（补偿仅在流式输出时生效） |
| 展开后 glob/grep 无详细结果 | 摘要行显示 pattern + 搜索路径；用户可接受（WebUI 同样如此） |
| 15 locale 翻译 | 用 lokit 同步 |
