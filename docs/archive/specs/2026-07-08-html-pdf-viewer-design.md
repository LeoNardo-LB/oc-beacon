# 工作空间 HTML / PDF 文件查看器

**Date**: 2026-07-08
**Status**: Design Approved → Pending Implementation Plan
**Author**: brainstorming session

## 1. 背景与目标

### 现状

FileViewer 已支持六种格式（TEXT/MARKDOWN/IMAGE/SVG/CSV/JSON），但工作空间中的两类常见文件无法查看：

- **HTML**：API 返回 `type:"text"`，但 `FileType` 枚举没有 HTML 映射 → 被当作 TEXT 显示源码，**无渲染预览**
- **PDF**：API 返回 `type:"binary"` + base64 + `mimeType:"application/pdf"`，但只有 IMAGE 类型被特殊处理 → **显示"Binary not supported"**

### 目标

- HTML：WebView 完整渲染（允许 JS 执行），支持源码↔渲染切换
- PDF：PDF.js 内嵌渲染，开箱即用的翻页/缩放体验

### 非目标（YAGNI）

- HTML 不处理相对路径资源重写（无法访问远程文件系统）
- PDF 不做标注/批注（二进制文件，标注无意义）
- PDF 不做文本提取/搜索（PDF.js 核心 viewer 足够）
- 不引入新的 UI 依赖库

## 2. 方案选择

### HTML 方案

**选定方案：RenderWebView 直接渲染**

直接用 WebView `loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)` 加载原始 HTML。理由：
- 复用现有 `RenderWebView` 架构，只增加一个 `when` 分支
- Android WebView 原生支持 HTML/CSS/JS 渲染
- 和现有 SVG/CSV 的渲染模式切换一致

### PDF 方案

**选定方案：PDF.js 核心 + 轻量 wrapper**

集成 pdf.js + pdf.worker.js 到 assets，创建自定义轻量 `pdf_viewer.html`，用 pdf.js API 渲染到 canvas，自建极简移动端 UI。

对比方案：
- ❌ 官方完整 viewer：桌面设计，4MB+ 含 l10n/图片资源，移动端适配成本高
- ❌ 核心 + Compose UI：需要 Bitmap 跨 JNI 传递，开发量最大
- ✅ 核心 + 轻量 WebView wrapper：~1MB 包体积，移动端友好，开发量适中

## 3. 架构设计

### 3.1 FileType 枚举扩展

```kotlin
enum class FileType {
    TEXT, MARKDOWN, IMAGE, SVG, CSV, JSON,
    HTML,   // 新增：.html / .htm（文本，支持渲染切换）
    PDF;    // 新增：.pdf（二进制，仅渲染模式）

    val supportsRender: Boolean get() = this != TEXT

    // PDF 的源码模式对 base64 数据无意义，不显示切换按钮
    val supportsSourceView: Boolean get() = this != PDF

    companion object {
        private val EXT_MAP: Map<String, FileType> = mapOf(
            "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN,
            "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE,
            "gif" to IMAGE, "webp" to IMAGE, "bmp" to IMAGE,
            "svg" to SVG,
            "csv" to CSV, "tsv" to CSV,
            "json" to JSON,
            "html" to HTML, "htm" to HTML,   // 新增
            "pdf" to PDF                      // 新增
        )

        fun fromExtension(filePath: String): FileType =
            EXT_MAP[filePath.substringAfterLast('.', "").lowercase()] ?: TEXT
    }
}
```

### 3.2 渲染判定矩阵（更新后）

| FileType | 默认模式 | 切换按钮 | 源码模式 | 渲染模式 |
|----------|----------|----------|----------|----------|
| TEXT | SOURCE | 不显示 | CodeWebView | — |
| MARKDOWN | SOURCE | 显示 | CodeWebView | RenderWebView（marked.js） |
| IMAGE | RENDER_PREVIEW | 显示 | base64 文本 | RenderWebView（`<img>`） |
| SVG | SOURCE | 显示 | CodeWebView | RenderWebView（内嵌 SVG） |
| CSV | SOURCE | 显示 | CodeWebView | RenderWebView（`<table>`） |
| JSON | SOURCE | 显示 | CodeWebView | RenderWebView（美化 `<pre>`） |
| **HTML** | **SOURCE** | **显示** | **CodeWebView** | **RenderWebView（原始 HTML 渲染）** |
| **PDF** | **RENDER_PREVIEW** | **不显示** | — | **PdfViewer（PDF.js）** |

### 3.3 数据流

**HTML（文本类型）**：
```
loadLive()
  → getFileContent() → type:"text", content:"<!DOCTYPE html>..."
  → FileType.fromExtension("index.html") → HTML
  → defaultRenderMode() → SOURCE（和其他文本格式一致）
  → 走正常文本分页流程（AnnotationManager、分页加载等）
```

**PDF（二进制类型）**：
```
loadLive()
  → getFileContent() → type:"binary", content:<base64>, mimeType:"application/pdf"
  → FileType.fromExtension("report.pdf") → PDF
  → BINARY 分支新增 PDF case：
      保留 base64 content → renderMode = RENDER_PREVIEW（直接渲染，不走分页）
```

## 4. 组件设计

### 4.1 Assets 结构（新建）

```
app/src/main/assets/pdfjs/
  ├── pdf.min.js              # PDF.js 核心库（~350KB）
  ├── pdf.worker.min.js       # Worker（~700KB）
  ├── pdf_viewer.html         # 自定义轻量 wrapper 页面
  └── viewer.css              # 移动端适配样式（~5KB）
```

总包体积增加约 1MB。

### 4.2 pdf_viewer.html — PDF.js 轻量 wrapper

职责：
- 加载 pdf.js 核心库 + worker
- 暴露全局 JS 函数供 Kotlin 调用：`loadPdfFromBase64(base64)`、`nextPage()`、`prevPage()`、`setScale(scale)`
- 通过 `@JavascriptInterface` 回调 Kotlin：`onPdfLoaded(totalPages)`、`onPageRendered(current, total)`、`onError(message)`
- 渲染到 `<canvas>`，自适应屏幕宽度

核心 JS 逻辑：
```javascript
async function loadPdfFromBase64(base64Data) {
    const uint8 = Uint8Array.from(atob(base64Data), c => c.charCodeAt(0));
    pdfjsLib.GlobalWorkerOptions.workerSrc = 'pdf.worker.min.js';
    const pdf = await pdfjsLib.getDocument({ data: uint8 }).promise;
    window._pdfDoc = pdf;
    window._currentPage = 1;
    window._totalPages = pdf.numPages;
    renderPage(1);
    PdfViewerInterface.onPdfLoaded(pdf.numPages);
}

async function renderPage(pageNum) {
    const page = await window._pdfDoc.getPage(pageNum);
    const viewport = page.getViewport({ scale: window._scale || 1.5 });
    const canvas = document.getElementById('pdf-canvas');
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    await page.render({ canvasContext: canvas.getContext('2d'), viewport }).promise;
    PdfViewerInterface.onPageRendered(pageNum, window._totalPages);
}
```

### 4.3 PdfViewer.kt — 新建 Composable

```kotlin
// ui/screens/viewer/PdfViewer.kt（新建）
@Composable
fun PdfViewer(
    base64Data: String,
    visible: Boolean = true,
    modifier: Modifier = Modifier
)
```

职责：
- WebView 加载 `file:///android_asset/pdfjs/pdf_viewer.html`
- 通过 `addJavascriptInterface(PdfViewerInterface, "PdfViewerInterface")` 暴露回调
- `onPageFinished` 后调用 `evaluateJavascript("loadPdfFromBase64('$base64Data')")` 注入数据
- 顶部叠加 Material 3 工具栏（页码 `1/24` + 上/下页 IconButton + 加载状态）
- WebView 手势缩放（`settings.builtInZoomControls = true`）

### 4.4 RenderWebView.kt — 修改（新增 HTML 分支）

```kotlin
// factory 块中新增
when (fileType) {
    FileType.HTML -> {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false        // 禁止 file:// 访问
        settings.allowContentAccess = false     // 禁止 content:// 访问
        settings.domStorageEnabled = true       // 某些 HTML 需要 localStorage
        // baseURL=null：相对路径不解析到本地文件系统
        loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
    }
    // ... 其他类型不变
}
```

HTML 内容直接作为完整 HTML document 加载，浏览器原生渲染结构/样式/脚本。

### 4.5 FileViewerViewModel.kt — 修改（loadLive BINARY 分支）

```kotlin
if (c.type == ContentType.BINARY) {
    val ft = FileType.fromExtension(filePath)
    when (ft) {
        FileType.IMAGE -> {
            // 现有：保留 base64 content 用于图片渲染
            _uiState.update { it.copy(
                isLoading = false, isBinary = false, fileType = ft,
                content = c.content, mimeType = c.mimeType,
                renderMode = FileViewerRenderMode.RENDER_PREVIEW
            )}
        }
        FileType.PDF -> _uiState.update {           // 新增
            it.copy(
                isLoading = false,
                isBinary = false,
                fileType = ft,
                content = c.content,                // 保留 base64 数据
                mimeType = c.mimeType,
                renderMode = FileViewerRenderMode.RENDER_PREVIEW
            )
        }
        else -> {
            // 现有：其他二进制不支持
            _uiState.update { it.copy(isLoading = false, isBinary = true, mimeType = c.mimeType) }
        }
    }
}
```

### 4.6 FileViewerScreen.kt — 修改（when 块 + TopBar）

**渲染 when 块新增分支**：
```kotlin
if (showRender && uiState.fileType.supportsRender) {
    when (uiState.fileType) {
        FileType.MARKDOWN -> RenderWebView(...)
        FileType.IMAGE, FileType.SVG, FileType.CSV -> RenderWebView(...)
        FileType.HTML -> RenderWebView(                 // 新增
            content = uiState.content,
            fileType = FileType.HTML,
            visible = true
        )
        FileType.PDF -> PdfViewer(                      // 新增
            base64Data = uiState.content,
            visible = true
        )
        else -> {}
    }
}
```

**TopBar 切换按钮条件**：
```kotlin
// 现有：uiState.fileType.supportsRender
// 改为：uiState.fileType.supportsRender && uiState.fileType.supportsSourceView
// PDF 不显示切换按钮（源码模式对 base64 无意义）
```

**toggleRenderMode() 防御性检查**（FileViewerViewModel）：
```kotlin
fun toggleRenderMode() {
    val current = _uiState.value
    // 新增 supportsSourceView 检查：PDF 不能切换到 SOURCE
    if (!current.fileType.supportsRender || !current.fileType.supportsSourceView
        || current.mode == FileViewerMode.DIFF) return
    // ... 其余不变
}
```

## 5. 错误处理与边界情况

| 场景 | 处理方式 |
|------|----------|
| **HTML 含外部 CDN 资源** | 正常加载（需网络），加载失败浏览器自动忽略 |
| **HTML 含 `<script>` 报错** | WebView `WebChromeClient.onConsoleMessage` 记录日志，不影响渲染 |
| **HTML 超大文件**（>100K 行） | 复用现有分页机制，渲染模式只渲染已加载部分 |
| **PDF base64 损坏/非有效 PDF** | PDF.js `getDocument()` reject → `onError()` → 显示错误提示 + 返回按钮 |
| **PDF 超大**（base64 > 10MB） | 渲染前显示警告 banner，不阻止加载 |
| **PDF 加密/需要密码** | PDF.js `PasswordException` → 显示"该 PDF 需要密码，暂不支持" |
| **Worker 加载失败** | fallback 到 `pdfjsLib.disableWorker = true` 主线程渲染 |

## 6. 安全考量

### HTML 渲染安全

- `baseURL = null`：相对路径不解析到本地文件系统
- `allowFileAccess = false` + `allowContentAccess = false`：JS 无法访问设备本地文件
- HTML 来自用户自己的工作空间，XSS 风险等同于用户自己打开文件，可接受
- WebView 不保存表单数据/密码（`settings.saveFormData = false`）

### PDF 安全

- PDF.js 在 WebView 沙箱内运行，PDF 内嵌的 JS（如有）由 PDF.js 控制不执行
- base64 数据仅在内存中处理，不写入持久存储

## 7. 测试策略

### 7.1 单元测试（纯函数）

**FileTypeTest 扩展**：
- `fromExtension("index.html")` → HTML
- `fromExtension("page.HTML")` → HTML（大小写不敏感）
- `fromExtension("doc.htm")` → HTML
- `fromExtension("report.pdf")` → PDF
- `supportsRender`: HTML=true, PDF=true
- `supportsSourceView`: PDF=false, 其余=true

**FileViewerViewModelTest 扩展**：
- `init with .html file sets fileType=HTML, renderMode=SOURCE`
- `init with .pdf (binary+base64) sets fileType=PDF, retains content, renderMode=RENDER_PREVIEW`
- `toggleRenderMode works for HTML (SOURCE ↔ RENDER_PREVIEW)`
- `toggleRenderMode is no-op for PDF`（不支持切回源码）

### 7.2 不测试的部分

委托 WebView，手动验证：
- PdfViewer 的实际渲染效果
- HTML 的 WebView 渲染效果
- 翻页/缩放手势

## 8. 新增/修改文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `FileType.kt` | 修改 | 新增 HTML、PDF 枚举值 + EXT_MAP + supportsSourceView 属性 |
| `FileViewerViewModel.kt` | 修改 | loadLive() BINARY 分支新增 PDF case + toggleRenderMode() 增加 supportsSourceView 检查 |
| `RenderWebView.kt` | 修改 | when 新增 HTML 分支（JS enabled + 安全限制） |
| `PdfViewer.kt` | **新建** | PDF.js WebView 封装 + JS Interface + 工具栏 |
| `FileViewerScreen.kt` | 修改 | when 新增 HTML/PDF 分支 + TopBar 按钮条件 |
| `assets/pdfjs/pdf.min.js` | **新建** | PDF.js 核心库 |
| `assets/pdfjs/pdf.worker.min.js` | **新建** | PDF.js Worker |
| `assets/pdfjs/pdf_viewer.html` | **新建** | 轻量 wrapper 页面 |
| `assets/pdfjs/viewer.css` | **新建** | 移动端适配样式 |
| `FileTypeTest.kt` | 修改 | HTML/PDF 用例 |
| `FileViewerViewModelTest.kt` | 修改 | HTML/PDF 用例 |
| `strings.xml` | 修改 | pdf_error_load / pdf_password_required / pdf_large_warning |

## 9. 兼容性

- 现有 TEXT/MARKDOWN/IMAGE/SVG/CSV/JSON 行为完全不变
- 现有 Annotation/DIFF/分页系统不受影响（HTML 走文本流程，PDF 无标注）
- `isMarkdown` 访问器不变（`fileType == MARKDOWN`）
- 现有 `toggleRenderMode()` 增加 `supportsSourceView` 检查（防御 PDF 切到 SOURCE）

## 10. 实施顺序建议

1. **FileType 扩展**（纯枚举，无依赖）→ 单元测试验证
2. **HTML 渲染**（RenderWebView + Screen 集成）→ 编译 + 手动验证
3. **PDF ViewModel 分支**（loadLive BINARY 处理）→ 单元测试
4. **PDF.js assets 集成**（下载库文件 + 创建 wrapper）
5. **PdfViewer Composable**（WebView + JS Interface + 工具栏）
6. **Screen 集成 + TopBar 调整**→ 编译 + 完整手动验证
7. **字符串资源 + 最终测试**
