# Markdown 表格动态上限换行实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让文件浏览（WebView）与主对话流（Compose）的 Markdown/CSV 表格支持"容器内滚动 + 单元格动态宽度上限换行"，并清理相关死代码。

**Architecture:** 两端采用同一动态上限公式 `cellCap = max(容器宽 ÷ 列数, 120)`，实现各自独立：
- WebView 端：`markdown_viewer.html` 用 JS 在渲染后包裹表格 + 设置单元格 `max-width`；CSV 用 Kotlin 构建 HTML 时以 CSS `max()` 函数注入列数感知上限
- Compose 端：`SimpleMarkdownTable` 在探测测量后对列宽做 `min(自然宽, cellCap)` 截断，单元格文本加 `LineBreak.Simple` 断词
- 代码块行为不变（保持容器内横向滚动）

**Tech Stack:** Android (Kotlin + Jetpack Compose BOM 2026.05.01)、mikepenz multiplatform-markdown-renderer 0.43.0、WebView (marked v15.0.12 + highlight.js)、JUnit 4 + Compose UI Test

## Global Constraints

- JDK 21（`jvmToolchain(21)`）；构建命令统一加超时：`compileDevDebugKotlin` 120s、`testDevDebugUnitTest` 180s
- 若 Gradle 卡住：先 `.\gradlew --stop` 再重试；`gradle.properties` 硬编码代理 `127.0.0.1:7897`，代理不可达时注释掉 4 行 `systemProp.*`
- 编译检查：`.\gradlew :app:compileDevDebugKotlin`
- 单元测试：`.\gradlew :app:testDevDebugUnitTest --rerun`
- 模拟器调试/手动验证交给 subagent 执行（AGENTS.md 规则）
- 不自动 commit；每任务完成由主 Agent 汇总后统一提交（或按用户要求）
- 所有改动必须可追溯到 spec：`docs/superpowers/specs/2026-08-04-markdown-table-wrap-design.md`
- MIN_CELL 常量值两端统一：120dp（Compose）/ 120px（WebView CSS）
- 代码块（`pre` / mikepenz code 组件）**不改**
- 范围外：`MarkdownPreviewDialog.kt`（活跃组件）、`MarkdownContent.kt` 的 `rememberMarkdownState`（活跃代码）不得触碰

---

### Task 1: 文件浏览 Markdown 表格（markdown_viewer.html）

**Files:**
- Modify: `app/src/main/assets/markdown_viewer.html`

**Interfaces:**
- Consumes: 无（独立资产文件）
- Produces: `renderMarkdown(md, isDark, bgColor, textColor)` 行为变化——渲染表格时自动包裹 `.table-wrapper` 并设置单元格动态 `max-width`；`RenderWebView.kt` 无需改动

- [ ] **Step 1: 修改 CSS —— 表格部分整体替换**

将原 `.markdown_viewer.html` 中 `/* Tables */` 块（第 73-85 行）替换为：

```css
        /* Tables */
        .table-wrapper {
            width: 100%;
            overflow-x: auto;
            margin: 0.6em 0;
        }
        .table-wrapper table {
            border-collapse: collapse;
            width: 100%;
            margin: 0;
            font-size: 0.9em;
        }
        th, td {
            border: 1px solid rgba(128, 128, 128, 0.3);
            padding: 6px 10px;
            text-align: left;
            overflow-wrap: anywhere;
            word-break: break-word;
        }
        th { background: rgba(128, 128, 128, 0.12); font-weight: 600; }
```

> 表格的 `margin: 0.6em 0` 从 `table` 移到 `.table-wrapper`，避免双倍边距；`overflow-wrap: anywhere` 使单元格 min-content 降为单字符宽，`max-width` 才能成为硬上限。

- [ ] **Step 2: 修改 JS —— renderMarkdown 内追加表格包裹逻辑**

在 `renderMarkdown` 函数的 `document.getElementById('content').innerHTML = html;` 之后、`document.querySelectorAll('pre code').forEach(...)` 之前插入：

```js
            // Wrap tables for in-container scrolling + dynamic per-column cap
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

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`（超时 120s）
Expected: BUILD SUCCESSFUL（资产文件不影响编译，本步骤确认无意外破坏）

- [ ] **Step 4: 手动验证（subagent + 模拟器）**

按 AGENTS.md 委派 subagent 执行（不写代码，仅验证）：
1. 模拟器安装 dev debug 包（`.\gradlew :app:assembleDevDebug`）
2. 在聊天中打开一个含表格的 .md 文件（表格含：普通短单元格、一个 120+ 字符长 URL 单元格、4 列以上表格各一个）
3. 验证：长 URL 单元格在上限内换行不撑破页面；表格铺满容器宽度；4 列+ 表格在表格区域内部横向滚动，页面主体不横向移动
4. 截图留存

Expected: 全部符合；任何异常反馈主 Agent 修正

- [ ] **Step 5: Commit（由主 Agent 统一提交，或用户指示）**

```bash
git add app/src/main/assets/markdown_viewer.html
git commit -m "fix(viewer): wrap markdown tables with dynamic cell width cap"
```

---

### Task 2: CSV 表格（RenderHtmlBuilder）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilder.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilderTest.kt`

**Interfaces:**
- Consumes: 现有 `RenderHtmlBuilder.build(fileType, content, isDark, bgHex, fgHex): String` 签名不变
- Produces: CSV 输出 HTML 包含 `.table-wrapper` 包裹、`overflow-wrap: anywhere`、列数感知 `max-width: max(calc((100vw - 32px) / N), 120px)`；其他 FileType（JSON/SVG）输出不变

- [ ] **Step 1: 写失败测试**

在 `RenderHtmlBuilderTest.kt` 末尾追加：

```kotlin
    @Test
    fun `CSV build wraps table in scrollable container`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a,b\n1,2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain table-wrapper", html.contains("table-wrapper"))
    }

    @Test
    fun `CSV build adds dynamic cell cap css based on column count`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a,b\n1,2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain column-aware max-width", html.contains("max-width: max(calc((100vw - 32px) / 2), 120px)"))
    }

    @Test
    fun `CSV build enables word breaking in cells`() {
        val html = RenderHtmlBuilder.build(FileType.CSV, "a,b\n1,2", isDark = false, bgHex = lightBg, fgHex = lightFg)
        assertTrue("should contain overflow-wrap anywhere", html.contains("overflow-wrap: anywhere"))
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.viewer.RenderHtmlBuilderTest"`（超时 180s）
Expected: 3 个新用例 FAIL（断言不满足）

- [ ] **Step 3: 实现**

`RenderHtmlBuilder.kt` 两处修改：

(a) `build()` 的 `<style>` 块中，在 `table { ... }` 行上方插入：

```css
            .table-wrapper { width: 100%; overflow-x: auto; }
```

在 `th, td { ... }` 规则中追加两个属性：

```css
            th, td { border:1px solid $borderColor; padding:6px 10px; text-align:left; overflow-wrap:anywhere; word-break:break-word; }
```

(b) `buildCsvTable()` 整体替换为：

```kotlin
    private fun buildCsvTable(content: String, borderColor: String, headerBg: String): String {
        if (content.isBlank()) return "<table><tbody></tbody></table>"
        val delimiter = if (content.contains('\t')) '\t' else ','
        val rows = parseCsvLines(content, delimiter)
        if (rows.isEmpty()) return "<table><tbody></tbody></table>"

        val columnCount = rows.first().size.coerceAtLeast(1)
        val capCss = "<style>th, td { max-width: max(calc((100vw - 32px) / $columnCount), 120px); }</style>"

        val sb = StringBuilder()
        sb.append("<div class=\"table-wrapper\">")
        sb.append("<table>")
        rows.forEachIndexed { index, row ->
            val tag = if (index == 0) "th" else "td"
            sb.append("<tr>")
            row.forEach { cell ->
                sb.append("<$tag>").append(escapeHtml(cell)).append("</$tag>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        sb.append("</div>")
        return capCss + sb.toString()
    }
```

> 空内容分支保持原样（现有测试 `CSV empty content produces empty table` 只断言 `<table` 存在，不受影响）。`capCss` 以 `<style>` 片段形式拼在表格前（浏览器允许 body 内 style）。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun --tests "dev.leonardo.ocbeacon.ui.screens.viewer.RenderHtmlBuilderTest"`（超时 180s）
Expected: 全部 PASS（含原有 9 个用例 + 新 3 个用例；原有断言如 `html.contains("<table")`、`<th>name</th>` 不受 wrapper/style 影响）

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`（超时 120s）
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 手动验证（subagent + 模拟器）**

1. 打开一个含 2 列 CSV（其中一列含 120+ 字符长内容）→ 单元格换行、表格铺满
2. 打开一个 5 列 CSV → 表格区域内部横向滚动，页面主体不移动
3. 截图留存

Expected: 符合；异常反馈主 Agent

- [ ] **Step 7: Commit（由主 Agent 统一提交，或用户指示）**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilder.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/RenderHtmlBuilderTest.kt
git commit -m "fix(viewer): CSV table wrap with dynamic cell width cap"
```

---

### Task 3: 主对话流表格（SimpleMarkdownTable）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTable.kt`
- Test (androidTest): Create `app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTableWrapTest.kt`

**Interfaces:**
- Consumes: 现有 `SimpleMarkdownTable(content, tableNode, style, uriHandler, linkColor)` 签名不变；`MarkdownContent` 的 `table` component 映射不变
- Produces: 列宽截断逻辑：`cappedWidths[col] = min(colWidths[col], effectiveCap)`，其中 `effectiveCap = max(containerWidth / columnCount, minCellWidthPx)`（containerWidth ≤ 0 时回落 minCellWidthPx）；单元格样式含 `lineBreak = LineBreak.Simple`

- [ ] **Step 1: 写失败测试（androidTest）**

创建 `app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTableWrapTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MarkdownTableWrapTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setMarkdown(md: String) {
        composeRule.setContent {
            MaterialTheme {
                MarkdownContent(markdown = md, textColor = Color.Black, isUser = false)
            }
        }
    }

    @Test
    fun `long url cell wraps and does not overflow container`() {
        val longUrl = "https://example.com/" + "a".repeat(120)
        setMarkdown("| col |\n| --- |\n| $longUrl |")
        composeRule.waitForIdle()

        val cell = composeRule.onNodeWithText(longUrl, substring = true).fetchSemanticsNode()
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            "cell right edge ${cell.boundsInRoot.right} must not exceed root width $rootWidth",
            cell.boundsInRoot.right <= rootWidth + 0.5f
        )
        assertTrue("cell must wrap to multiple lines", cell.boundsInRoot.height > 1f)
    }

    @Test
    fun `regular two column table does not overflow`() {
        setMarkdown("| name | value |\n| --- | --- |\n| alpha | beta |\n| gamma | delta |")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("alpha").assertIsDisplayed()
        val lastCell = composeRule.onNodeWithText("delta").fetchSemanticsNode()
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue(
            "cell right edge ${lastCell.boundsInRoot.right} must not exceed root width $rootWidth",
            lastCell.boundsInRoot.right <= rootWidth + 0.5f
        )
    }
}
```

> 依赖说明：`MarkdownContent` 是 `internal`，androidTest 与 main 为 friend module 可访问；`LocalChatDensity` 有默认值 `ChatDensity.Normal` 无需 provider。

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:connectedDevDebugAndroidTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownTableWrapTest"`（需要模拟器/设备，超时 300s）
Expected: `long url cell wraps and does not overflow container` FAIL（当前单行超宽，`right > rootWidth`）

> 若本机无模拟器：委派 subagent 启动模拟器执行（AGENTS.md 规则）。

- [ ] **Step 3: 实现**

`MarkdownTable.kt` 三处修改：

(a) 新增 import：

```kotlin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.LineBreak
import kotlin.math.roundToInt
```

(b) 在 `val scrollState = rememberScrollState()` 之后新增常量：

```kotlin
    val minCellWidthPx = with(LocalDensity.current) { 120.dp.toPx() }.roundToInt()
```

(c) 替换 SubcomposeLayout 内"填满策略"段（原第 184-199 行，`// 填满策略：使用 containerWidth 而非 constraints.maxWidth` 注释起的 `val colWidths = IntArray...` 到 `} else { colWidths }` 整个块）：

```kotlin
                // 动态列宽上限：cap = max(容器宽 / 列数, MIN_CELL)
                val effectiveCap = if (containerWidth > 0) {
                    maxOf(containerWidth / columnCount, minCellWidthPx)
                } else {
                    minCellWidthPx
                }
                val cappedWidths = IntArray(columnCount) { col ->
                    minOf(colWidths[col], effectiveCap)
                }

                // 填满策略：使用 containerWidth 而非 constraints.maxWidth
                val naturalWidth = cappedWidths.sum()
                val parentWidth = containerWidth
                val finalColWidths = if (naturalWidth > 0 && parentWidth > 0 && naturalWidth < parentWidth) {
                    val scale = parentWidth.toFloat() / naturalWidth.toFloat()
                    val scaled = IntArray(columnCount) { col ->
                        (cappedWidths[col] * scale).toInt()
                    }
                    val diff = parentWidth - scaled.sum()
                    for (i in 0 until diff.coerceAtMost(columnCount)) {
                        scaled[i] += 1
                    }
                    scaled
                } else {
                    cappedWidths
                }
```

(d) 单元格样式加断词（替换原 cellStyle 定义，第 125-129 行）：

```kotlin
                        val cellStyle = if (row.isHeader) {
                            style.copy(fontWeight = FontWeight.SemiBold, lineBreak = LineBreak.Simple)
                        } else {
                            style.copy(lineBreak = LineBreak.Simple)
                        }
```

> `LineBreak.Simple` = "单词放不下整行时断行"，语义等同 HTML `overflow-wrap: anywhere`。`containerWidth` 首次为 0 时 cap 回落 120dp，`onSizeChanged` 更新后重算（与现有填满逻辑时序一致）。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:connectedDevDebugAndroidTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.markdown.MarkdownTableWrapTest"`（超时 300s）
Expected: 2 个用例 PASS

> 若 `LineBreak.Simple` 对英文长单词无效（bounds 仍越界）：改用备选方案——在 `buildClickableMarkdown` 生成的文本中，对超过单元格上限的连续无空格 token 插入零宽空格（U+200B）强制断行。修改点：`MarkdownTable.kt` 中 `cellResult.annotatedString` 生成后，对每个无空格 token 按 `effectiveCap` 宽度估算插入 ZWSP（估算用 `cellStyle` 的 `fontSize` × 0.5 平均字宽）。此备选仅在验证失败时启用，并需更新测试预期。

- [ ] **Step 5: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`（超时 120s）
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 手动验证（subagent + 模拟器）**

1. 聊天消息发送含表格 md：3 列表格（普通内容）→ 铺满聊天宽度、无滚动
2. 聊天消息发送单列表格含 120+ 字符 URL → 单元格内换行、消息气泡不撑破
3. 聊天消息发送 5 列表格 → 表格容器内横向滚动，消息气泡不横向扩展
4. 截图留存，与文件浏览端行为对照一致

Expected: 符合；异常反馈主 Agent

- [ ] **Step 7: Commit（由主 Agent 统一提交，或用户指示）**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTable.kt app/src/androidTest/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/markdown/MarkdownTableWrapTest.kt
git commit -m "fix(chat): table cell dynamic width cap with word wrapping"
```

---

### Task 4: 死代码清理

**Files:**
- Delete: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/MarkdownPreview.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileViewerScreen.kt`

**Interfaces:**
- Consumes: 无（目标均为无调用点死代码，spec §6 已确认引用范围）
- Produces: 删除 `MarkdownPreviewWithScrollAnchor` 函数及其 5 个专用 import；删除整文件 `MarkdownPreview.kt`；`FileViewerScreen` 对外 API 不变

- [ ] **Step 1: 删除 `MarkdownPreview.kt`**

Run: `git rm app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/MarkdownPreview.kt`
Expected: 文件删除成功

- [ ] **Step 2: 删除 `FileViewerScreen.kt` 中的死函数与 import**

编辑 `FileViewerScreen.kt`：
1. 删除第 387-409 行整个 `MarkdownPreviewWithScrollAnchor` 函数（含 KDoc 注释块，从 `/**` 到函数结束 `}`）
2. 删除以下 5 行 import（已核对仅该函数使用）：
   - `import androidx.compose.foundation.ScrollState`
   - `import androidx.compose.foundation.rememberScrollState`
   - `import androidx.compose.runtime.snapshotFlow`
   - `import kotlinx.coroutines.flow.filter`
   - `import kotlinx.coroutines.flow.first`

> 保留：`import androidx.compose.foundation.combinedClickable`（line 5，别处使用）；`sourceLazyListState.firstVisibleItemIndex`（line 115，是 LazyListState 方法，非 flow.first）。

- [ ] **Step 3: 编译检查**

Run: `.\gradlew :app:compileDevDebugKotlin`（超时 120s）
Expected: BUILD SUCCESSFUL（若报未使用 import 警告，确认删除干净；编译通过即无未解析引用）

- [ ] **Step 4: 回归单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`（超时 180s）
Expected: 全部 PASS（FileViewerViewModelTest 等回归不受影响）

- [ ] **Step 5: Commit（由主 Agent 统一提交，或用户指示）**

```bash
git add -A app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/MarkdownPreview.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/viewer/FileViewerScreen.kt
git commit -m "chore(viewer): remove dead MarkdownPreview compose renderer"
```

---

### Task 5: 全量验证收尾

**Files:**
- 无代码改动（验证 + 汇总）

**Interfaces:**
- Consumes: Task 1-4 全部产出
- Produces: 验证报告（编译 / 单测 / androidTest / 模拟器截图）

- [ ] **Step 1: 全量单元测试**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`（超时 180s）
Expected: 全部 PASS

- [ ] **Step 2: 全量 androidTest（subagent + 模拟器）**

Run: `.\gradlew :app:connectedDevDebugAndroidTest`（超时 600s）
Expected: 全部 PASS（含新 `MarkdownTableWrapTest`）

- [ ] **Step 3: 端到端手动验证（subagent + 模拟器）**

对照 spec §8 测试计划逐项执行：
1. 文件浏览 .md 表格（长 URL 单元格、多列表格）→ 换行/铺满/容器滚动
2. 文件浏览 .csv（2 列长内容、5 列）→ 同上
3. 聊天表格（3 列常规、单列长 URL、5 列）→ 与文件浏览一致
4. 聊天代码块与文件浏览代码块 → 仍为容器内横向滚动（确认未回归）
5. 每项截图留存

Expected: 全部符合 spec

- [ ] **Step 4: 汇总提交（等待用户指示后统一 commit）**

若用户要求提交，按任务粒度拆分为 4 个 commit（Task 1-4 各自的 message），顺序执行：

```bash
git add -A && git status   # 核对仅涉及 4 个任务的文件
```

- [ ] **Step 5: 更新文档（可选，等待用户指示）**

若用户要求：同步 `docs/opencode-api-reference.md` 无涉及；本次不产生新增 API 文档。

---

## 自审记录（writing-plans skill 要求）

- **Spec 覆盖**：spec §4.1→Task1、§4.2→Task2、§5→Task3、§6→Task4、§8→Task5、§9 风险→Task3 Step 4 备选方案、§10 范围外→Global Constraints
- **占位符扫描**：无 TBD/TODO；所有代码步骤给出完整可执行代码
- **类型一致性**：`effectiveCap`/`cappedWidths`/`finalColWidths` 命名在 Task 3 内部一致；`minCellWidthPx` 与 spec 的 120dp 一致；`RenderHtmlBuilder.build` 签名不变
