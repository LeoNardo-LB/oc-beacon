# Android Jetpack Compose 代码查看器库 — 穷尽式调研

> 调研日期：2026-06-23 | 覆盖：8 个候选方案 + 2 个相关生态项目

## 评估维度说明

| 维度 | 满分定义 |
|------|----------|
| 高亮 | 支持语言数 + 渲染质量（正则 vs TextMate vs tree-sitter） |
| 行号 | 内置 gutter，是否支持固定/相对行号 |
| 选择 | 字符级文本选择是否可靠 |
| 菜单 | 能否注入自定义项（如 "Annotate"）到选择浮层/长按菜单 |
| 滚动 | 水平+垂直，gutter 是否固定不串位 |
| 大文件 | UI 层虚拟化 / 高亮增量计算 |
| Compose | 原生 Compose vs AndroidView 桥接 |

---

## 核心对比表

| # | 方案 | 高亮 | 行号 | 选择 | 菜单 | 滚动 | 大文件 | Compose | 维护 | 评分 |
|---|------|------|------|------|------|------|--------|---------|------|------|
| 1 | **hossain-khan/compose-highlight** | ⭐⭐⭐⭐⭐ 190+ (Highlight.js) | ✅ 内置 | ✅ SelectionContainer | ⚠️ 需自建 | ✅ 内置 | ❌ 单 Text | ✅ 原生 | 🟢 活跃 (2026-05) | **8.5** |
| 2 | **mataku/compose-syntax-highlight** | ⭐⭐⭐⭐ tree-sitter (按语言模块) | ❌ 需自建 | ✅ 原生 | ⚠️ 需自建 | 手动 | 🟡 增量高亮(非UI虚拟化) | ✅ 原生 KMP | 🟡 实验性 0.5.0 | **7.5** |
| 3 | **ivan-magda/kotlin-textmate** | ⭐⭐⭐⭐⭐ 250+ (VS Code 语法) | ❌ 需自建 | ✅ 原生 | ⚠️ 需自建 | 手动 | ❌ 全量重算+ANR风险 | ✅ 原生 | 🟡 新(11★, P1 issue) | **7.0** |
| 4 | **AmrDeveloper/CodeView** | ⭐⭐⭐ DIY 正则 | ✅ 内置+相对行号 | ✅ TextView 原生 | ✅✅ ActionMode 最强 | ⚠️ 水平滚动 gutter 串位 bug | ❌ 单 EditText | ❌ AndroidView | 🔴 停滞 (2023-03) | **6.5** |
| 5 | **Qawaz/compose-code-editor** | ⭐⭐⭐ Prettify | 🟡 文档示例(BasicTextField) | ✅ 原生 | ⚠️ | 手动 | ❌ | ✅ 原生 KMP | 🔴 旧 | **6.0** |
| 6 | **SnipMeDev/KodeView** | ⭐⭐⭐ 17 语言 (Highlights) | ❌ 需自建 | ✅ 原生 | ⚠️ | 手动 | ❌ | ✅ 原生 KMP | 🟡 (2026-01) | **6.0** |
| 7 | **Irineu333/Highlight** | ⭐⭐ 框架(DIY 正则) | ❌ | ✅ | ⚠️ | 手动 | ❌ | ✅ Compose+View | 🟡 (2025-11) | **5.0** |
| 8 | Mikepenz/markdown-renderer (已在用) | ⭐⭐ 仅 markdown 内 code block | ❌ | ✅ | ⚠️ | N/A | ❌ | ✅ | 🟢 | 不适用 |

---

## 关键发现：Compose 文本选择菜单的天花板

**这是影响"自定义菜单"需求的决定性约束，必须先理解：**

- `SelectionContainer` 默认**只提供 Copy / Select All**，没有 Share、Search、自定义项
- 注入自定义项（如 "Annotate"）的两种官方途径：
  1. **`LocalTextToolbar` 覆盖**：自定义 `TextToolbar.showMenu()` 内部调 `view.startActionMode(ActionMode.Callback)` — 复杂、文档稀缺、破坏默认体验
  2. **`Modifier.appendTextContextMenuComponents()`**（较新 Compose）：相对干净但仍属低层 API
- **结论**：所有"产出 AnnotatedString + Compose Text"的方案（1/2/3/5/6/7）在自定义菜单上能力等同且都偏弱；**只有 WebView 方案和 View 体系（TextView）方案有成熟、文档完善的 ActionMode 自定义菜单支持**

---

## Top 3 推荐（针对 oc-remote 的需求）

### 🥇 推荐 1：hossain-khan/compose-highlight — 默认首选

**为何胜出**：唯一一个**开箱即用覆盖 5/6 需求**（高亮+行号+选择+滚动+Compose 原生）的库，唯一短板是大文件虚拟化（但移动端查看代码片段场景下影响有限）。

**集成方式**：
```kotlin
// build.gradle.kts
implementation("dev.hossain:compose-highlight:0.31.0")

// Application.onCreate() 预热（可选，降首帧延迟）
WebViewCompat.startUpWebView(applicationContext, ...)

// 使用
HighlightThemeProvider(
    lightHighlightTheme = rememberTomorrowTheme(),
    darkHighlightTheme  = rememberTomorrowNightTheme(),
) {
    SyntaxHighlightedCode(
        code = source,
        language = "kotlin",
        showLineNumbers = true,        // ✅ 内置 gutter
        // copyButton / languageLabel 可替换为任意 composable
    )
}
```

**自定义菜单（Annotate）补全方案**：
- 在 `HighlightThemeProvider` 外层用 `CompositionLocalProvider(LocalTextToolbar provides CustomAnnotateToolbar(...))` 注入自定义 ActionMode
- 或用 `Modifier.appendTextContextMenuComponents { }` 追加 "Annotate" 项（Compose 1.7+）

**成本**：APK +628 KB，一个共享隐藏 WebView（~2-4MB RAM，全屏共享），avg ~6.4ms/块。

**风险**：仅 21★（作者+Copilot 维护），但更新极其频繁、文档完善、Maven Central 已发布。

---

### 🥈 推荐 2：ivan-magda/kotlin-textmate — 高质量离线/无 WebView 方案

**为何次选**：VS Code 同款 TextMate 语法（250+ 语言、最高保真），纯 Kotlin 无 WebView，离线。适合对 WebView 有顾虑或追求渲染质量的项目。

**集成方式**：
```kotlin
implementation("io.github.ivan-magda:kotlin-textmate-compose:0.2.0")

// 需自行将 .tmLanguage.json + theme 放入 assets/
val grammar = GrammarReader.readGrammar(assets.open("grammars/kotlin.tmLanguage.json"))
    .let { Grammar(it.scopeName, it, JoniOnigLib()) }
val theme = ThemeReader.readTheme(assets.open("themes/dark_plus.json"))

CodeBlock(code = source, grammar = grammar, theme = theme)
```

**短板**：
- 行号、滚动、虚拟化**全部需自建**（CodeBlock 只产出 AnnotatedString 渲染）
- **无增量 tokenization**：每次全量重算，超长单行有 ANR 风险（Issue #17，P1 未修）
- APK +1.6 MB（语法文件）
- 项目很新（11-37★），稳定性待验证

**适用**：中等规模代码查看（< 2000 行）、对渲染质量要求高、能接受自建行号/滚动。

---

### 🥉 推荐 3：AmrDeveloper/CodeView (AndroidView 桥接) — 自定义菜单需求最强

**为何入榜**：**唯一原生支持成熟的 ActionMode 自定义菜单**（TextView 体系），行号/相对行号/当前行高亮全套内置。如果"Annotate"菜单是硬需求且 Compose 方案的自定义菜单体验不可接受，这是兜底选择。

**集成方式**：
```kotlin
AndroidView(factory = { ctx ->
    com.amrdeveloper.codeview.CodeView(ctx).apply {
        setEnableLineNumber(true)
        setEnableRelativeLineNumber(true)
        setEnableHighlightCurrentLine(true)
        // 自定义选择菜单（TextView 原生 ActionMode）
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_ANNOTATE, 0, "Annotate")
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId == MENU_ANNOTATE) {
                    val sel = text.substring(selectionStart, selectionEnd)
                    /* 触发 Annotate 流程 */
                }
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        // 配置语法高亮（需自行添加 Pattern）
    }
})
```

**致命短板**：
- 🔴 **已停滞 3 年**（最后 1.3.8 发布 2023-03）
- 🔴 **水平滚动时 gutter 串位 bug 未修复**（Issue #42：行号与代码重叠、横向滑动出现空白）
- ❌ 非 Compose 原生，需 `AndroidView` 桥接
- ❌ 单 EditText，无真正虚拟化（名字虽含 RecyclerView 但实现是 MultiAutoCompleteTextView）
- 高亮需自行写正则 Pattern（无内置语言包）

---

## 按需求场景的最终建议

| 你的优先级 | 推荐方案 |
|-----------|----------|
| 综合最佳（开箱即用 5/6） | **compose-highlight** (#1) |
| 渲染质量最高 + 离线无 WebView | **kotlin-textmate** (#3) |
| "Annotate" 菜单是绝对硬需求 | **CodeView via AndroidView** (#4) + 接受 gutter bug，或用 #1 + 自建 Compose ActionMode |
| 超大文件（5000+ 行）虚拟化 | **无现成库** — 需自建 `LazyColumn` 逐行渲染（参考 JetBrains compose-multiplatform codeviewer 示例），但会破坏 SelectionContainer 跨行选择（官方文档明示 LazyColumn+SelectionContainer 行为未定义）|

---

## 各方案详细档案

### 1. hossain-khan/compose-highlight
- **GitHub**: github.com/hossain-khan/android-compose-highlight
- **版本**: 0.31.0 (2026-05-17) | **Stars**: 21 | **License**: 见仓库
- **机制**: 隐藏 WebView 加载 Highlight.js → tokenize HTML → jsoup 转 Compose `AnnotatedString`。WebView 纯当 JS 引擎，不渲染可见内容。
- **语言**: 190+（Highlight.js 全量包）
- **行号**: `SyntaxHighlightedCode(showLineNumbers = true)`，可配置宽度/颜色
- **选择**: 输出原生 `AnnotatedString`，天然支持 `SelectionContainer` 字符级选择
- **菜单**: 库未封装；需用 `LocalTextToolbar` 覆盖或 `appendTextContextMenuComponents`
- **滚动**: 通过 `modifier = Modifier.verticalScroll(...)` 实现；gutter 与内容同列布局
- **大文件**: 单块单 `AnnotatedString`，无虚拟化。`HighlightThemeProvider` 共享一个 WebView，多块场景性能优秀（17 块 224ms vs 无 provider 866ms）
- **性能**: avg ~6.4ms/块（warm）；首块含 WebView 冷启动 ~200ms，可预热
- **主题**: Tomorrow/Tomorrow Night/Atom One Dark/Atom One Light 内置；支持任意 Highlight.js CSS（assets/raw/Map）
- **特色**: 可替换 languageLabel / copyButton 为任意 composable；`highlightBothThemes()` 一次 tokenize 出双主题；`SyntaxHighlightedTextEditor`（实验性）支持实时编辑

### 2. mataku/compose-syntax-highlight
- **GitHub**: github.com/mataku/ComposeSyntaxHighlighter
- **版本**: 0.5.0 (实验性) | **License**: 见仓库
- **机制**: tree-sitter (JNI) + 增量高亮引擎 `IncrementalHighlighter`
- **语言**: 按模块单独引入（kotlin/swift/ruby/rust/python/go/java/markdown/js/ts）
- **行号**: ❌ 无内置（`SyntaxHighlightedText` / `SyntaxHighlightedTextField` 不带 gutter）
- **选择**: ✅ 原生
- **菜单**: ⚠️ Compose 限制
- **大文件**: **高亮层最优** — `IncrementalHighlighter` 仅重算编辑字节范围，5k 行内编辑 40×+ 快于全量。但**渲染层仍是单 AnnotatedString**，UI 虚拟化需自建
- **性能**: Android 实测 100 行 3.1ms / 1k 行 30.3ms / 5k 行 143.1ms（cold）；warm 更快
- **风险**: 实验性、iOS 仅 preview、tree-sitter JNI 不可中途取消

### 3. ivan-magda/kotlin-textmate
- **GitHub**: github.com/ivan-magda/kotlin-textmate
- **版本**: 0.2.0 (2026, 新) | **Stars**: 11-37 | **License**: 见仓库
- **机制**: 纯 Kotlin 移植 vscode-textmate，Joni(Oniguruma) 正则，行级 tokenize 带跨行状态
- **语言**: 任何 TextMate/VS Code 语法（250+ 可用）
- **质量**: 与 VS Code 同源，最高保真
- **行号/滚动/虚拟化**: ❌ 全需自建（`CodeBlock` 仅渲染）
- **大文件**: ❌ 全量重算，无增量；Issue #17 (P1) 标注超长行 ANR 风险，待加 timeLimit
- **APK**: +1.6 MB（语法+主题文件）
- **限制**: 无 injection grammar（JSX 内 HTML 不高亮）、非线程安全、不渲染 per-token 背景

### 4. AmrDeveloper/CodeView
- **GitHub**: github.com/AmrDeveloper/CodeView
- **版本**: 1.3.8 (2023-03-27，**3 年未更新**) | **Stars**: 411 | **License**: MIT
- **机制**: 基于 `AppCompatMultiAutoCompleteTextView`
- **行号**: ✅ `setEnableLineNumber` / 相对行号 / 当前行高亮 / 字体/颜色可配
- **选择**: ✅ TextView 原生字符级选择
- **菜单**: ✅✅ **最强** — `customSelectionActionModeCallback` 完全控制 ActionMode，增删菜单项文档化
- **滚动**: ⚠️ **水平滚动 gutter 串位**（Issue #42 未修）：横向滑动出现空白、行号与代码重叠
- **大文件**: ❌ 单 EditText，无虚拟化
- **Compose**: ❌ 需 `AndroidView`
- **高亮**: DIY 正则 Pattern，需自行配置每个语言（仓库有示例）
- **附加**: AutoComplete / Snippets / Auto-indent / Find&Replace / Pair-complete

### 5. Qawaz/compose-code-editor
- **GitHub**: github.com/Qawaz/compose-code-editor
- **机制**: Google Prettify 解析 → `AnnotatedString`，无 WebView
- **行号**: 文档示例用 `BasicTextField.onTextLayout` 取 lineTops 手动绘制 — 非内置 API
- **多平台**: KMP (Android/JVM/Web)
- **维护**: 较旧，活跃度低

### 6. SnipMeDev/KodeView (+ Highlights 引擎)
- **GitHub**: github.com/SnipMeDev/KodeView (113★) + /Highlights (178★)
- **机制**: 纯 Kotlin KMP 高亮引擎 → `CodeTextView`/`CodeEditText`
- **语言**: 17 种
- **行号**: ❌ 需自建
- **维护**: KodeView 2026-01 更新，Highlights 2025-09
- **License**: Apache 2.0

### 7. Irineu333/Highlight
- **GitHub**: github.com/Irineu333/Highlight (43★)
- **定位**: 高亮**框架**（正则规则 DSL），非语言库 — 你自己定义所有规则
- **适用**: 非常规高亮（hashtag/email/链接），非标准代码语法
- **License**: MIT

---

## 生态参考（非候选）

- **JetBrains compose-multiplatform `codeviewer` 示例**：官方 LazyColumn 逐行渲染 + 手写 AnnotatedString 关键字高亮 + VerticalScrollbar。展示"自建虚拟化查看器"的最小骨架，但无真正语法引擎。位置：`examples/codeviewer/`
- **Mikepenz/multiplatform-markdown-renderer**（oc-remote 已在用）：为 markdown 内 code block 设计，不支持独立代码文件查看、无 gutter。不适合本需求。
