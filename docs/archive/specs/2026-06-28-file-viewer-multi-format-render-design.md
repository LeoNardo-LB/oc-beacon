# File Viewer 多格式渲染框架

**Date**: 2026-06-28
**Status**: Design Approved → Pending Implementation Plan
**Author**: brainstorming session

## 1. 背景与目标

### 现状

FileViewer 已支持 Markdown 渲染切换（`toggleRenderMode()`，右上角眼睛图标），源码高亮（`CodeWebView`），DIFF 视图，分页加载，Annotation 标注系统。

但其他格式的渲染能力缺失：
- 图片（PNG/JPEG/GIF/WebP/BMP）显示"Binary not supported"，忽略 API 返回的 base64 数据
- SVG / CSV / JSON 无渲染预览，只有源码视图

### 目标

建立统一的"源码 ↔ 渲染"切换框架，扩展支持：
- **图片**（PNG/JPEG/GIF/WebP/BMP）→ 图片查看器
- **SVG** → WebView 矢量图渲染
- **CSV/TSV** → 只读 HTML 表格
- **JSON** → 美化缩进视图

### 非目标（YAGNI）

- CSV 不做交互排序/筛选
- JSON 不做折叠树（expand/collapse）
- 图片不做自定义双击放大/拖拽平移
- HTML 预览（XSS 风险，不在本期范围）
- PDF 渲染

## 2. 方案选择

**选定方案：WebView 统一渲染**

所有新格式通过 WebView 渲染，生成不同的 HTML 内容。理由：
- 复用现有 `CodeWebView` 基础设施
- SVG 在 Android 原生渲染需要第三方库（不成熟），WebView 原生支持
- CSV/JSON 用 JS 处理最简单
- 统一架构，后续扩展格式成本极低

Markdown 保持现有 `MarkdownPreview`（Mikepenz 库，性能好），不走 WebView。

## 3. 架构设计

### 3.1 核心抽象：FileType 枚举

替代当前 `isMarkdown: Boolean` 的单一标记：

```kotlin
enum class FileType {
    TEXT,       // 纯文本/代码，不支持渲染（只有源码模式）
    MARKDOWN,   // .md / .markdown / .mdx（已有 MarkdownPreview）
    IMAGE,      // .png / .jpg / .jpeg / .gif / .webp / .bmp
    SVG,        // .svg
    CSV,        // .csv / .tsv
    JSON;       // .json

    val supportsRender: Boolean get() = this != TEXT

    companion object {
        private val EXT_MAP = mapOf(
            "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN,
            "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE,
            "gif" to IMAGE, "webp" to IMAGE, "bmp" to IMAGE,
            "svg" to SVG,
            "csv" to CSV, "tsv" to CSV,
            "json" to JSON
        )

        fun fromExtension(filePath: String): FileType =
            EXT_MAP[filePath.substringAfterLast('.', "").lowercase()] ?: TEXT
    }
}
```

### 3.2 渲染判定矩阵

| FileType | 默认模式 | 切换按钮 | 源码模式 | 渲染模式 |
|----------|----------|----------|----------|----------|
| TEXT | SOURCE | 不显示 | CodeWebView | — |
| MARKDOWN | SOURCE | 显示 | CodeWebView | MarkdownPreview（现有） |
| IMAGE | SOURCE | 显示 | base64 文本/提示 | ImageViewer（WebView `<img>`） |
| SVG | SOURCE | 显示 | CodeWebView | FormatWebView（嵌入 SVG） |
| CSV | SOURCE | 显示 | CodeWebView | FormatWebView（HTML `<table>`） |
| JSON | SOURCE | 显示 | CodeWebView | FormatWebView（美化 `<pre>`） |

### 3.3 数据流

```
ViewModel.loadLive()
  → getFileContent()  // API 返回 { type, content, encoding, mimeType }
  → FileType.fromExtension(filePath)  // 后缀判定
  → uiState.fileType = FileType.JSON  // 存入 state
  → 如果 type==BINARY && encoding==base64  // 图片特殊处理
      → 保留 content（base64 数据）不忽略

UI 层
  → if (fileType.supportsRender)  // TopBar 显示切换按钮
  → when (renderMode)
      SOURCE → CodeWebView（源码视图，统一）
      RENDER_PREVIEW → when (fileType)
          MARKDOWN → MarkdownPreview（复用现有）
          IMAGE → ImageViewer（新建）
          SVG/CSV/JSON → FormatWebView（新建，各格式生成不同 HTML）
```

## 4. 组件设计

### 4.1 新增文件清单

```
ui/screens/viewer/
  FileType.kt              (新建) 格式枚举 + 后缀判定
  ImageViewer.kt           (新建) 图片查看器（WebView + 内置缩放）
  FormatWebView.kt         (新建) SVG/CSV/JSON 统一 WebView 渲染器
  RenderHtmlBuilder.kt     (新建) 为每种格式生成 HTML 内容
```

### 4.2 FileType.kt — 格式判定

见 3.1 节代码。纯枚举 + 静态映射，无依赖。

### 4.3 ImageViewer.kt — 图片查看器

```kotlin
@Composable
fun ImageViewer(
    base64Data: String,
    mimeType: String,
    modifier: Modifier = Modifier
)
```

- WebView 加载 `<img src="data:{mimeType};base64,{base64Data}">`
- CSS `max-width: 100%; height: auto; object-fit: contain` 自适应
- WebView 内置手势缩放（`settings.builtInZoomControls = true`）
- 背景适配主题

### 4.4 FormatWebView.kt — SVG/CSV/JSON 统一渲染

```kotlin
@Composable
fun FormatWebView(
    content: String,
    fileType: FileType,   // SVG / CSV / JSON
    modifier: Modifier = Modifier
)
```

- 内部调用 `RenderHtmlBuilder.build(fileType, content)` 生成 HTML
- 复用现有 `CodeWebView` 的 WebView 配置（暗色主题）

### 4.5 RenderHtmlBuilder.kt — HTML 生成

```kotlin
object RenderHtmlBuilder {
    fun build(fileType: FileType, content: String): String  // 返回完整 HTML document
}
```

各格式生成策略：

| 格式 | HTML 生成方式 |
|------|---------------|
| **SVG** | 直接嵌入 `<div>${content}</div>`（浏览器原生渲染 `<svg>`） |
| **CSV** | JS 解析 `,` / `\t` 分隔 → `<table>`，首行加粗为表头 |
| **JSON** | JS `JSON.parse` + `JSON.stringify(parsed, null, 2)` 美化，`<pre>` 包裹 |

所有 HTML 共享同一套基础 CSS（背景色、字体、暗色适配）。

### 4.6 FileViewerUiState 变更

```kotlin
// 替换
- val isMarkdown: Boolean = false,
+ val fileType: FileType = FileType.TEXT,
+ // 向后兼容：现有代码通过 isMarkdown 访问的改为 fileType == MARKDOWN

// 保留
  val renderMode: FileViewerRenderMode = FileViewerRenderMode.SOURCE
```

### 4.7 FileViewerViewModel 变更

```kotlin
// toggleRenderMode() 变更
- if (!current.isMarkdown || current.mode == FileViewerMode.DIFF) return
+ if (!current.fileType.supportsRender || current.mode == FileViewerMode.DIFF) return

// loadLive() 二进制处理变更
if (c.type == ContentType.BINARY) {
    val ft = FileType.fromExtension(filePath)
    if (ft == FileType.IMAGE) {
        // 图片：保留 base64 content 用于渲染
        _uiState.update { it.copy(
            isLoading = false, isBinary = false, fileType = ft,
            content = c.content, mimeType = c.mimeType
        )}
    } else {
        // 其他二进制：保持现状（不支持）
        _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
    }
}

// loadLive() 文本处理变更
- isMarkdown = isMarkdownFile(filePath),
+ fileType = FileType.fromExtension(filePath),

// isMarkdownFile() 删除（被 FileType.fromExtension 替代）
```

### 4.8 FileViewerScreen 集成

```kotlin
// when 块新增分支（在现有 markdown 检查之后）
uiState.fileType.supportsRender && uiState.renderMode == FileViewerRenderMode.RENDER_PREVIEW -> {
    when (uiState.fileType) {
        FileType.MARKDOWN -> MarkdownPreviewWithScrollAnchor(...)  // 现有，不变
        FileType.IMAGE -> ImageViewer(
            base64Data = uiState.content,
            mimeType = uiState.mimeType ?: "image/*",
            modifier = Modifier.fillMaxSize()
        )
        FileType.SVG, FileType.CSV, FileType.JSON -> FormatWebView(
            content = uiState.content,
            fileType = uiState.fileType,
            modifier = Modifier.fillMaxSize()
        )
        FileType.TEXT -> CodeWebView(...)  // 理论不会走到
    }
}

// TopBar 切换按钮条件变更
- if (annotationCount == 0 && uiState.isMarkdown && uiState.mode != FileViewerMode.DIFF)
+ if (annotationCount == 0 && uiState.fileType.supportsRender && uiState.mode != FileViewerMode.DIFF)
```

## 5. 错误处理与边界情况

| 场景 | 处理方式 |
|------|----------|
| **图片 base64 为空/损坏** | ImageViewer 显示错误提示（复用 `MessageState`） |
| **超大图片**（base64 > 5MB） | 渲染模式显示警告 + 仍尝试加载；源码模式显示"二进制数据过大" |
| **JSON 解析失败**（非法 JSON） | RenderHtmlBuilder 返回错误提示 HTML（"无效的 JSON"），WebView 内显示，不 crash。用户可手动切回源码模式 |
| **CSV 极多行**（>1000 行） | HTML 内 `<tbody>` CSS `max-height` + 滚动；不做分页 |
| **SVG 含 `<script>`** | HTML 模板 `sandbox` 属性禁用 inline script |

### 与现有分页的兼容

渲染模式下如果 `!isFullyLoaded`，渲染的只是已加载部分内容。CSV/JSON 渲染部分内容不影响理解，用户可切回源码模式触发 `loadMore` 后再切换。TopBar 不禁用渲染，不额外提示。

## 6. 安全考量

WebView 渲染用户内容时的安全措施：

```html
<html>
<head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body sandbox>
  ${renderedContent}
</body>
</html>
```

- SVG 的 `<script>` 标签在 sandbox 下不执行
- CSV/JSON 内容通过 JS `textContent` 注入（非 innerHTML），防 XSS
- 图片 base64 data URI 无安全风险

## 7. 测试策略

### 7.1 单元测试

**FileTypeTest — 格式判定（纯函数）**

- `fromExtension("readme.md")` → MARKDOWN
- `fromExtension("photo.PNG")` → IMAGE（大小写不敏感）
- `fromExtension("data.csv")` → CSV
- `fromExtension("config.json")` → JSON
- `fromExtension("icon.svg")` → SVG
- `fromExtension("main.kt")` → TEXT
- `fromExtension("noext")` → TEXT
- `supportsRender`: TEXT=false，其余=true

**RenderHtmlBuilderTest — HTML 生成（纯函数）**

- CSV: `build(CSV, "a,b,c\n1,2,3")` → 含 `<table>`，首行 `<th>`，TSV 用 `\t` 分隔
- JSON: `build(JSON, '{"a":1}')` → 含美化后的 `<pre>`
- JSON: `build(JSON, 'invalid')` → 返回错误提示 HTML（不 crash）
- SVG: `build(SVG, '<svg>...</svg>')` → 原样嵌入，含 sandbox

**FileViewerViewModelTest — 更新现有测试**

- `init with .json file sets fileType=JSON, keeps SOURCE renderMode`
- `init with .png (binary+base64) sets fileType=IMAGE, retains content`
- `toggleRenderMode switches SOURCE ↔ RENDER for JSON/CSV/SVG/IMAGE`
- `toggleRenderMode is no-op for TEXT (main.kt)`
- `toggleRenderMode is no-op in DIFF mode`（已有，保持）

### 7.2 UI 层测试

手动验证为主：
- 打开 .png → 右上角切换按钮可见 → 点击显示图片 → 再点击回源码
- 打开 .json → 切换显示美化视图
- 打开 .csv → 切换显示表格

### 7.3 不测试的部分

- ImageViewer / FormatWebView 的 Composable 渲染（委托 WebView）
- 缩放手势（手动验证）

## 8. 兼容性

- `isMarkdown` 字段移除，所有引用改为 `fileType == FileType.MARKDOWN`
- 现有 Markdown 渲染切换行为不变
- 现有 Annotation 系统、DIFF 视图、分页加载不受影响
- `isMarkdownFile()` 私有方法删除，被 `FileType.fromExtension()` 替代
