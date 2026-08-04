# Chat Link Navigation — Design Spec

**Date:** 2026-06-28
**Status:** Approved (brainstorming complete)

## Objective

在 ChatScreen 的 AI 回复 Markdown 消息中，点击 `[text](url)` 链接时，根据 URL 类型智能跳转：
- 网络链接 → 打开默认浏览器
- 相对路径 → 基于 Session 工作目录解析 → 跳 FileViewer 或 WorkspaceScreen
- 绝对路径 → 仅文件 → 跳 FileViewer

## Scope

- **IN:** ChatScreen 中 `[text](url)` 格式的 Markdown 链接（AI 回复 + 用户消息）
- **OUT:** FileViewer Markdown WebView 中的链接；代码块内路径；纯文本路径

## Link Classification

纯 Kotlin `LinkClassifier`，无 Android 依赖：

```kotlin
sealed interface LinkTarget {
    data class Web(val url: String)
    data class RelativePath(val path: String)
    data class AbsolutePath(val path: String)
}

object LinkClassifier {
    fun classify(url: String): LinkTarget = when {
        url.startsWith("http://") || url.startsWith("https://")
            || url.startsWith("ftp://") || url.startsWith("mailto:") -> Web(url)
        url.startsWith("/") -> AbsolutePath(url)
        url.matches(Regex("[A-Za-z]:[\\\\/].*")) -> AbsolutePath(url)
        else -> RelativePath(url)
    }
}
```

## Navigation Flow

```
onClick(url):
  target = LinkClassifier.classify(url)

  Web(url):
    → Intent(ACTION_VIEW, Uri.parse(url))
    → catch ActivityNotFoundException → Snackbar

  RelativePath(relPath):
    resolvedPath = PathUtils.joinPath(sessionDirectory, relPath)
    if sessionDirectory is empty → Snackbar "无法确定工作目录"
    → heuristic file/directory check:
        endsWith("/") || hasFileExtension(resolvedPath) == false → WorkspaceScreen
        hasFileExtension → FileViewer (加载失败由 FileViewer 处理)

  AbsolutePath(absPath):
    hasFileExtension → FileViewer
    no extension → Snackbar "仅支持预览文件"
```

### File vs Directory Heuristic

无需 API 预检，基于路径特征：
- 以 `/` 结尾 → 目录
- 无文件扩展名 → 目录
- 有文件扩展名 → 文件

### Supported File Types

`FileType.fromExtension()` 对已知扩展名返回特定类型（MARKDOWN/IMAGE/SVG/CSV/JSON），对未知扩展名（.kt, .java, .py, .js 等）默认返回 `TEXT`——FileViewer 能以源码模式显示。

导航时不做预检类型拦截——任何有扩展名的文件直接跳 FileViewer，由 FileViewer 的加载逻辑处理错误。如后续需拦截二进制类型，可在 FileType 添加 `BINARY_EXTENSIONS` 黑名单。

## Integration Architecture

### mikepenz LocalUriHandler

mikepenz Markdown 通过 `LocalUriHandler` CompositionLocal 处理链接点击。提供一个自定义 `UriHandler` 包裹 ChatScreen 内容区，通过 CompositionLocal 自动传播到所有 Markdown 组件：

```
ChatScreen
  └─ CompositionLocalProvider(LocalUriHandler provides linkUriHandler)
       └─ ChatMessageList
            └─ MarkdownContent (不改动)
                 └─ Markdown (不改动)
```

`MarkdownContent` 和 `Markdown` 组件完全不需要修改。

### New Files

| File | Responsibility |
|------|---------------|
| `domain/model/LinkClassifier.kt` | 纯 Kotlin URL 分类（无 Android 依赖） |
| `ui/screens/chat/LinkUriHandler.kt` | Composable 工厂，创建持有导航上下文的 UriHandler |

### Modified Files

| File | Change |
|------|--------|
| `util/PathUtils.kt` | 新增 `joinPath(base, relative)` 方法 |
| `ui/screens/chat/ChatScreen.kt` | 用 `CompositionLocalProvider` 包裹内容区 |
| `ui/screens/viewer/FileType.kt` | 无需改动（fromExtension 已足够，不做预检拦截） |

### New Test Files

| File | Coverage |
|------|----------|
| `test/.../LinkClassifierTest.kt` | URL 分类逻辑（web/relative/absolute 边界） |
| `test/.../PathUtilsTest.kt` | joinPath 跨平台分隔符（如不存在则新增） |

## Error Handling

| Scenario | Handling |
|----------|---------|
| 不支持的文件类型（二进制等） | FileViewer 加载时处理，不做预检拦截 |
| 路径不存在 | FileViewer 加载失败 → 复用现有错误 UI |
| 目录被误判为文件 | FileViewer 加载失败 → 现有错误状态 |
| sessionDirectory 为空 | Snackbar "无法确定工作目录" |
| 无浏览器可用 | try-catch `ActivityNotFoundException` → Snackbar |
| 跨平台路径分隔符 | PathUtils.joinPath 统一处理 `/` 和 `\` |

**原则：** 不过度防御。路径不存在、目录误判等错误由 FileViewer 现有错误状态处理。链接处理器只负责分类和导航。

## Key Decisions

1. **方案 A（直接导航 + 错误回退）** — 无 API 预检，用启发式判断文件/目录。AI 回复中的链接绝大多数是文件，直接跳 FileViewer 最快。
2. **LocalUriHandler 而非修改 markdownComponents** — 侵入性最低，MarkdownContent 完全不需要改动。
3. **LinkClassifier 为纯 Kotlin** — 放在 domain/model，可独立单元测试。
4. **相对路径基准 = sessionLifecycle.sessionDirectory** — 不是 ServerConnection 的 directory。

## Out of Scope

- FileViewer Markdown WebView 中的链接跳转
- 代码块内路径点击
- 纯文本路径自动识别
- 目录误判后的自动重定向到 WorkspaceScreen
