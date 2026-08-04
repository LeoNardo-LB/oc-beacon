# Markdown 表格动态上限换行设计

- 日期: 2026-08-04
- 状态: 待审阅
- 范围: 文件浏览（WebView）表格 + CSV 表格 + 主对话流表格 + 死代码清理

## 1. 背景与问题

### 1.1 现状

应用中有两条 Markdown 渲染链路，均存在"表格内容撑破容器宽度、整页横向滑动"的问题：

| 链路 | 渲染引擎 | 表格问题 | 代码块行为 |
|------|----------|---------|-----------|
| 文件浏览 `FileViewerScreen` | WebView + `markdown_viewer.html`（marked v15 + highlight.js） | CSS 无断词、无列宽上限 → 超长单元格撑破视口 → 整页横向滚动 | `pre { overflow-x: auto }` 容器内滚动 |
| 主对话流 `MarkdownContent` | mikepenz Compose + 手写 `SimpleMarkdownTable` | 探测测量用无限宽约束（`horizontalScroll` 传递）→ 列宽=单行最宽 → 超长列撑破容器 → `horizontalScroll` 滚动 | mikepenz 默认 `horizontalScroll` 滚动 |

根因：
- WebView 端：`th, td` 无 `word-break`/`overflow-wrap`，`table-layout: auto` 时列宽优先容纳内容 → 表格总宽 > 视口 → body 溢出可横向滑动
- Compose 端：探测测量 `constraints.maxWidth = Infinity`（外层 `horizontalScroll` 解除宽度约束）→ 单元格永远按"单行最宽"测量

### 1.2 用户期望

1. 表格**容器内滚动**（页面主体不横向移动），且每个单元格有**动态宽度上限**，超上限换行
2. 列数少时表格**铺满容器宽度**（与现状一致）
3. 代码块**保持容器内左右滚动**（不主动换行）
4. 文件浏览与主对话流两端表格行为**保持一致**
5. 顺带清理本次涉及的**死代码**

## 2. 决策记录

| # | 决策 | 理由 |
|---|------|------|
| D1 | 代码块不改：保持 `overflow-x: auto` / `horizontalScroll` | 用户明确要求；代码换行破坏缩进 |
| D2 | 列宽公式：`列宽 = min(自然宽, cellCap)` | 自然宽优先（内容自适应），超限截断换行 |
| D3 | `cellCap = max(容器宽 ÷ 列数, MIN_CELL)`，MIN_CELL = 120dp | 动态判定：列数多 → 上限小；单列 → 容器宽 |
| D4 | 填满策略优先于 cellCap：总宽 < 容器 → 等比放大铺满 | 用户硬性要求"列数少铺满"；放大后总和 ≤ 容器宽，不会产生滚动 |
| D5 | 断词：WebView `overflow-wrap: anywhere`；Compose `LineBreak.Simple` | 语义一致：长单词在上限宽度内断行 |
| D6 | CSV 表格一并修复 | 用户确认；同样存在撑破问题 |
| D7 | 死代码清理纳入本次：删除 `viewer/MarkdownPreview.kt` + `MarkdownPreviewWithScrollAnchor` | 用户确认；两处互相关联、均无调用点 |

## 3. 动态上限算法

```
cellCap = max(容器可用宽 ÷ 列数, 120dp)
列宽 = min(列自然宽, cellCap)
```

行为推演（容器宽 360dp 手机）：

```
1 列:  cellCap=360  → 长内容在容器宽内换行，铺满，无滚动
2 列:  cellCap=180  → 自然宽≤180 正常；超长列截断到 180 换行，总宽≤360 铺满
3 列:  cellCap=120  → 3×120=360 刚好铺满；超长列在 120dp 内换行
4+ 列: cellCap=120  → 总宽 > 360 → 表格容器内滚动（兜底）
```

判定本质：用"实测自然宽"（真实内容度量）与 cellCap 比较——内容短的列保持窄，内容超限的列截断换行。比字符数估算精确，两端实现同一公式。

### 填满放大 vs cellCap 交互

当 `Σ min(自然宽, cellCap) < 容器宽` 时，等比放大铺满（沿用现有逻辑）。放大后单列可能略超 cellCap，但前提是表格总宽 ≤ 容器宽 → 不产生滚动。文本换行点按截断时宽度测量，放大不改变换行点（仅右侧留白）。**铺满（D4）优先于上限（D2）。**

## 4. 文件浏览端实现（WebView）

### 4.1 `app/src/main/assets/markdown_viewer.html`（Markdown 表格）

CSS 新增：

```css
.table-wrapper { width: 100%; overflow-x: auto; }
.table-wrapper table { width: 100%; margin: 0; }
th, td { overflow-wrap: anywhere; word-break: break-word; }
```

> 表格原有 `margin: 0.6em 0` 移到 `.table-wrapper` 上，避免双倍边距。

JS（`renderMarkdown` 内，`marked.parse` 与 innerHTML 赋值之后）：

```js
document.querySelectorAll('table').forEach(function(table) {
    var wrapper = document.createElement('div');
    wrapper.className = 'table-wrapper';
    table.parentNode.insertBefore(wrapper, table);
    wrapper.appendChild(table);
    var firstRow = table.querySelector('tr');
    var cols = firstRow ? firstRow.children.length : 1;
    var containerWidth = document.documentElement.clientWidth - 32; // body padding 16px × 2
    var cap = Math.max(Math.floor(containerWidth / cols), 120);
    table.querySelectorAll('th, td').forEach(function(cell) {
        cell.style.maxWidth = cap + 'px';
    });
});
```

关键语义：CSS 表格列宽算法中，单元格 `min-content` 超过 `max-width` 会撑破 max-width——必须同时加 `overflow-wrap: anywhere`（min-content 降为单字符宽），max-width 才能成为硬上限。

### 4.2 `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilder.kt`（CSV 表格）

- 公共 `<style>` 增加：

```css
.table-wrapper { width: 100%; overflow-x: auto; }
th, td { overflow-wrap: anywhere; word-break: break-word; }
```

- `buildCsvTable()`：解析已得列数 N，动态注入列数感知上限（无需 JS，现代 CSS `max()` 支持，Chromium WebView 可用）：

```css
th, td { max-width: max(calc((100vw - 32px) / N), 120px); }
```

- 表格输出包一层 `<div class="table-wrapper">`。
- 表头/边框样式、`width: 100%` 保持现状。

### 4.3 `RenderWebView.kt`

零改动（HTML/JS 均在资产模板中，Kotlin 只负责加载）。

## 5. 主对话流端实现（`SimpleMarkdownTable`）

改动集中在 `SubcomposeLayout` 测量逻辑（探测测量之后）：

```kotlin
val minCellWidthPx = with(LocalDensity.current) { 120.dp.toPx() }.toInt()
val effectiveCap = if (containerWidth > 0)
    maxOf(containerWidth / columnCount, minCellWidthPx)
else minCellWidthPx

// probe 得到自然宽 colWidths 之后：
val cappedWidths = IntArray(columnCount) { col -> minOf(colWidths[col], effectiveCap) }
// 填满策略（现有 scale 逻辑）改用 cappedWidths 计算 naturalWidth
```

- 断词：单元格 `cellStyle` 加 `lineBreak = LineBreak.Simple`（`androidx.compose.ui.text.style.LineBreak`，Compose ≥1.7，BOM 2026.05.01 可用）。语义 = "单词放不下整行时断行"，等同 HTML `overflow-wrap: anywhere`。
- 时序：`containerWidth` 首次为 0 时 cap 回落 120dp，`onSizeChanged` 更新后重算——与现有填满逻辑时序一致，不引入新增闪烁。
- 注意：`minCellWidthPx` 需在 SubcomposeLayout 外部用 `LocalDensity.current` 计算后传入（Layout lambda 内无 Density）。

## 6. 死代码清理

| 目标 | 位置 | 处理 |
|------|------|------|
| `MarkdownPreview.kt` | `ui/screens/viewer/MarkdownPreview.kt` 整文件 | 删除文件（唯一调用者同为死代码） |
| `MarkdownPreviewWithScrollAnchor` | `ui/screens/viewer/FileViewerScreen.kt` 391-409 行 | 删除函数 + 仅被它使用的 import（`ScrollState`、`snapshotFlow`、`kotlinx.coroutines.flow.filter`/`first`，需逐一核对） |

保留（易混淆但活跃）：
- `ui/screens/chat/dialog/MarkdownPreviewDialog.kt`（聊天内 Markdown 预览对话框，活跃组件）
- `MarkdownContent.kt` 的 `rememberMarkdownState`（活跃代码）

## 7. 一致性对照

| 行为 | 文件浏览（WebView） | 主对话流（Compose） |
|------|--------------------|--------------------|
| 动态上限 | `max((视口-32)/列数, 120px)` JS 设置 | `max(containerWidth/列数, 120dp)` |
| 断词 | `overflow-wrap: anywhere` | `LineBreak.Simple` |
| 容器滚动 | `.table-wrapper { overflow-x: auto }` | 现有 `horizontalScroll` |
| 铺满 | `table { width: 100% }` | 现有等比放大填满 |
| 代码块 | `pre { overflow-x: auto }`（不改） | `horizontalScroll`（不改） |

## 8. 测试计划

1. `RenderHtmlBuilderTest`（现有）：新增 CSV 用例——生成 HTML 包含 `.table-wrapper`、`max-width: max(calc(...))`、`overflow-wrap: anywhere`
2. `FileViewerViewModelTest`（现有）：不受影响（逻辑在 HTML/JS 层），回归运行
3. Compose UI 测试：`SimpleMarkdownTable` 长单元格行为——超长 URL 单元格在上限内换行、3 列常规表格铺满、4 列+ 超宽表格触发容器滚动（项目已有 compose ui-test 基础设施）
4. 手动验证（模拟器）：
   - 文件浏览打开含长 URL 单元格的 .md 表格 → 单元格换行、页面不横向滑动、表格铺满
   - 文件浏览打开 .csv → 同上
   - 聊天消息含表格（长单元格 + 多列）→ 与文件浏览行为一致
5. `compileDevDebugKotlin` 编译检查

## 9. 风险与备选

| 风险 | 缓解 |
|------|------|
| `LineBreak.Simple` 在 Android 上对英文长单词效果不达预期（官方文档强调 CJK 参数，general 策略依赖平台实现） | 实现后真机验证；备选方案：对超过上限的单元格文本在超长 token 内插入零宽空格（U+200B）强制断行，显示无痕 |
| `containerWidth` 首次为 0 导致首帧列宽 = 120dp | 与现有填满逻辑时序一致，`onSizeChanged` 后重算；不新增问题 |
| CSS `max-width` 被 `min-content` 撑破 | 已通过 `overflow-wrap: anywhere` 解决（D5） |
| `markdown_viewer.html` 是资产文件，改动不进 Kotlin 编译检查 | 手动验证覆盖 JS/CSS 行为 |

## 10. 范围外（后续）

- 对话流代码块语法高亮（项目注释确认使用 mikepenz 默认渲染器，无高亮）
- `chat/markdown/ClickableMarkdown.kt`、`NormalizeTaskListMarkers.kt` 等其他潜在优化
- `FileViewerOverlay` 等组件级重构
