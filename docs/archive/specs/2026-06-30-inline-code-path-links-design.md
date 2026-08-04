# Inline Code Path Links

**Date**: 2026-06-30
**Status**: Approved
**Related**: `2026-06-28-chat-link-navigation-design.md` (前置功能)

## Problem

AI 回复中的行内代码（反引号包裹）经常包含文件路径或文件名，例如：

> Spec 写好并提交到 `docs/superpowers/specs/` `2026-06-29-link-scroll-restore-design.md`

当前这些行内代码只是纯文本展示，无法点击。用户希望它们自动检测为可点击路径，复用已有的链接导航体系（FileViewer / Workspace / Snackbar）。

## Solution

方案 B：AST 提取 + AnnotatedString 后处理。在渲染层检测路径样的行内代码，不改原始 markdown 数据。

## Scope

- **IN**: 段落中的行内代码（`` `code` ``），含 `/` `\` 或有文件扩展名
- **OUT**: 围栏代码块（```` ```block``````）、普通文本中的路径、非路径行内代码

## Detection Heuristic

纯函数 `isLikelyFilePath(text: String): Boolean`：

| 规则 | 示例 | 结果 |
|------|------|------|
| 含 `/` | `src/Main.kt`, `docs/specs/` | true |
| 含 `\` | `app\build.gradle` | true |
| 有扩展名 (`.\w{1,10}$`) | `Main.kt`, `build.gradle` | true |
| 以上都不满足 | `val x = 1`, `import foo` | false |

正则：`/[\\/]|\.\w{1,10}$`

## Architecture

### Data Flow

```
AST 遍历 (extractMarkdownLinks 扩展)
  ├─ INLINE_LINK → MarkdownLink(text, url)          ← 已有
  └─ CODE → strip 反引号 → isLikelyFilePath? → CodePath(text)  ← 新增
                                                    ↓
buildMarkdownAnnotatedString (不改)
                                                    ↓
后处理: CodePath 在 annotated.text 中 indexOf 定位
        → 追加 Underline + linkColor SpanStyle
        → 保留原有所有 span styles
                                                    ↓
MarkdownBasicText + pointerInput(detectTapGestures)
  ├─ 命中 MarkdownLink → uriHandler.openUri(url)    ← 已有
  └─ 命中 CodePath → uriHandler.openUri(codeText)   ← 新增
         ↓
  handleLinkClick → LinkClassifier → 文件/目录/Snackbar (已有)
```

### Components

#### 1. `isLikelyFilePath(text: String): Boolean`

- **位置**: `domain/model/LinkClassifier.kt`（与 LinkClassifier 同文件，作为工具函数）
- **输入**: 行内代码内容（已 strip 反引号）
- **输出**: 是否像路径
- **规则**: `text.contains('/') || text.contains('\\') || Regex("\\.\\w{1,10}$").containsMatchIn(text)`
- **测试**: 覆盖正例（`src/Main.kt`, `docs/`, `app\src`, `build.gradle`, `Main.kt`）和负例（`val x = 1`, `import foo`, `TODO`, `true`）

#### 2. AST 提取扩展 — `extractClickableItems`

当前函数 `extractMarkdownLinks` 返回 `List<MarkdownLink>`。改为返回 `List<ClickableItem>`：

```kotlin
sealed interface ClickableItem {
    val text: String
    data class Link(override val text: String, val url: String) : ClickableItem
    data class CodePath(override val text: String) : ClickableItem
}
```

AST walk 中新增分支：
```kotlin
if (n.type == MarkdownElementTypes.CODE) {
    val raw = n.getUnescapedTextInNode(content).toString()
    val codeText = raw.trim('`').trim()  // strip backticks + whitespace
    if (codeText.isNotEmpty() && isLikelyFilePath(codeText)) {
        items.add(ClickableItem.CodePath(codeText))
    }
}
```

#### 3. AnnotatedString 后处理

在 paragraph 组件中，`buildMarkdownAnnotatedString` 之后：

```kotlin
val codePaths = items.filterIsInstance<ClickableItem.CodePath>()
val finalAnnotated = if (codePaths.isEmpty()) annotated else {
    // Rebuild preserving original spans + adding underline/linkColor for code paths
    buildAnnotatedString {
        append(annotated.text)
        // Re-apply original span styles
        annotated.spanStyles.forEach { range ->
            addStyle(range.item, range.start, range.end)
        }
        // Add code path styling
        var searchFrom = 0
        for (cp in codePaths) {
            val idx = annotated.text.indexOf(cp.text, searchFrom)
            if (idx >= 0) {
                addStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        color = linkColor,
                    ),
                    idx, idx + cp.text.length
                )
                searchFrom = idx + cp.text.length
            }
        }
    }
}
```

**关键约束**:
- `annotated.spanStyles` 是 Compose 公开 API (`List<Range<SpanStyle>>`)，可安全遍历
- `indexOf` 搜索推进 `searchFrom` 避免同一文本多次匹配
- code path 文本在段落内通常唯一，`indexOf` 可靠

#### 4. Tap handler 扩展

`detectTapGestures` 中，先查 links，再查 code paths：

```kotlin
detectTapGestures { pos ->
    val layout = layoutResult ?: return
    val offset = layout.getOffsetForPosition(pos)
    val text = finalAnnotated.text

    // 1. Check markdown links (existing logic)
    var searchFrom = 0
    for (item in items) {
        val idx = text.indexOf(item.text, searchFrom)
        if (idx >= 0 && offset >= idx && offset < idx + item.text.length) {
            when (item) {
                is ClickableItem.Link -> uriHandler.openUri(item.url)
                is ClickableItem.CodePath -> uriHandler.openUri(item.text)
            }
            return
        }
        if (idx >= 0) searchFrom = idx + item.text.length
    }
}
```

点击 CodePath 时，`uriHandler.openUri(codeText)` 进入现有 `handleLinkClick`：
- `LinkClassifier.classify(codeText)` 分类为 RelativePath（无 scheme）
- RelativePath → joinPath(directory, path) → fileChecker → FileViewer 或 Snackbar
- AbsolutePath（如 `/etc/passwd`）→ fileChecker → FileViewer 或 Snackbar

### Unchanged

- `LinkClassifier` — 无改动，code path 走现有 RelativePath/AbsolutePath 分类
- `LinkUriHandler` / `handleLinkClick` — 无改动
- `NavGraph` / `ChatScreen` — 无改动
- `PathUtils` — 无改动
- 围栏代码块 — 不触碰

## Files Changed

| File | Change |
|------|--------|
| `domain/model/LinkClassifier.kt` | 新增 `isLikelyFilePath()` 函数 |
| `ui/screens/chat/markdown/MarkdownContent.kt` | `extractMarkdownLinks` → `extractClickableItems`；paragraph 组件增加后处理 + tap handler 扩展 |
| `test/.../LinkClassifierTest.kt` | 新增 `isLikelyFilePath` 测试用例 |

## Risks

| Risk | Mitigation |
|------|------------|
| `indexOf` 定位 code path 在 AnnotatedString 中匹配到非 code 区域的相同文本 | 搜索推进 searchFrom，段落内路径文本通常唯一；最坏情况是下划线画错位置，不影响功能 |
| `getUnescapedTextInNode` 对 CODE 节点返回的文本含/不含反引号 | 实现时用 logcat 验证，`.trim('`')` 兜底 |
| `annotated.spanStyles` API 在不同 Compose 版本行为差异 | 当前 BOM 2026.05.01 已确认有此 API |

## Success Criteria

1. AI 回复中 `` `src/Main.kt` `` 点击后打开 FileViewer（文件存在时）
2. `` `docs/specs/` `` 点击后打开 Workspace（目录时）
3. 不存在的路径点击后弹出 Snackbar "File not found"
4. 路径样行内代码有下划线 + 链接色视觉提示
5. `` `val x = 1` `` 等非路径行内代码不受影响，无视觉变化，不可点击
6. 编译通过 + 新增单元测试通过
