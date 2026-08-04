# 工作空间文件预览与标注修改

> 日期：2026-06-18
> 状态：已确认，待实现
> 涉及模块：ui-screens-workspace（新增）/ ui-screens-chat-tools / data-api / domain

## 1. 概述

为 OC Remote 增加"项目工作空间预览 + 标注修改"能力，类似 VSCode 的目录树 + 查看区。三个入口共用一个 FileViewer：

1. **入口1**：主对话流中 Read/Write/Edit 工具卡片的"查看"按钮 → 跳 FileViewer
2. **入口2**：会话顶部 MoreVert 菜单 → "查看工作空间" → 文件树 → 点文件 → FileViewer
3. **入口3**：工作空间顶部 Git 变更按钮 → 变更文件列表 → 点文件 → FileViewer (Diff)

**核心创新——标注修改**：由于 OpenCode 服务端无直接写文件 API，FileViewer 不支持编辑保存，而是用"长按选中文本 → 标注意见 → 一次性提交给当前会话的 AI"的方式，让 AI 执行修改。客户端化身"精确指路"工具。

### 支持的文件格式

- 普通文本文件
- 各类代码文件（按扩展名做语法高亮）
- Markdown 文档（源码视图 + 可切渲染预览）

### 非目标（一期不做）

- 客户端直接编辑保存（API 不支持）
- ApplyPatch / Patch / Glob / Search 卡片改造（仅 Read/Write/Edit）
- 跨文件汇总提交（仅单文件粒度）
- 标注持久化（仅内存临时）
- 图片预览、LSP 符号跳转、`find/symbol`（API 是桩实现）
- Diff 视图的标注能力（Diff 纯只读）

## 2. 背景与约束

### 2.1 API 能力（技术可行性约束）

| 能力 | 端点 | 状态 |
|------|------|------|
| 列目录 | `GET /file?path=` → `List<FileNode{name,path,absolute,type,ignored}>` | ✅ |
| 读文件内容 | `GET /file/content?path=` → `{type, content, diff?, patch?, mimeType?}` | ✅ |
| 文件名模糊搜索 | `GET /find/file?query=&limit=&type=` → `List<String>`（fff frecency 引擎） | ✅ |
| 全文搜索 | `GET /find?pattern=` → `List<SearchMatch>`（ripgrep，limit 硬编码 10） | ✅（不用） |
| Git 变更列表 | `GET /vcs/status` → `List<{file, additions, deletions, status}>` | ✅ |
| Git 文件 diff | `GET /vcs/diff?mode=git\|branch&context=N` → `List<{file, patch?, additions, deletions, status?}>` | ✅ |
| **直接写文件** | ❌ 无此端点 | ❌ |
| 应用 patch 写回 | `POST /vcs/apply`（要求 git 仓库 + 工作区 clean） | ✅（不用） |

**关键约束**：客户端无法直接写文件。唯一写回途径是 patch apply（要求 git）。本设计绕开此约束——**用标注生成结构化提示词，通过对话流让 AI 执行修改**。

### 2.2 Compose 渲染约束

- 项目 Markdown 渲染用 **`com.mikepenz:multiplatform-markdown-renderer-m3`**，渲染成多个独立 Text 组件（标题/段落/列表项各一个）
- **跨 mikepenz 多 Text 组件的选择+高亮不可行** → md 文件默认走"源码视图"（单 Text），渲染预览作只读切换
- 代码高亮用 **`dev.snipme:highlights`**（项目已在用），可生成 AnnotatedString
- `AnnotatedString` + `SpanStyle.background` 原生支持任意区间的背景高亮 → 标注视觉基础
- `SelectionContainer` 默认浮动菜单是系统级（复制/全选），加自定义"标注修改"项需用 `LocalTextToolbar` 替换

### 2.3 入口1 数据提取（已验证可行）

工具卡片基于 `ToolCardScaffold`，input 来自 `extractToolInput(tool)`：

| 卡片 | filePath 来源 | 内容来源 |
|------|--------------|---------|
| Read | `input["filePath"] ?: input["path"]` | 工具 output（读取内容） |
| Write | `input["filePath"] ?: input["path"]` | `input["content"]` |
| Edit | `input["filePath"]` | `input["oldString"]/["newString"]` + `ToolState.Completed.metadata`（含完整文件 before/after） |

`ToolState.Completed.metadata` 是关键——它提供完整文件 before/after，使"全文件 diff"成为可能，无需额外 API 调用。

## 3. 设计决策（已确认）

| # | 决策点 | 选择 | 理由 |
|---|--------|------|------|
| 1 | 编辑保存机制 | 放弃直接编辑，用"标注修改"生成提示词发对话流 | API 无写文件接口；绕开 git 依赖 |
| 2 | 三入口查看页 | 共用同一 FileViewer（参数化 source） | 代码复用最大 |
| 3 | 标注生命周期 | 内存临时 + 单文件提交 | 简单、无 DB、贴近场景 |
| 4 | 入口1 改造范围 | 仅 Read/Write/Edit 三卡片 | ApplyPatch/Patch/Glob/Search 不动 |
| 5 | 标注视觉路径 | 统一源码视图（行内高亮）+ md 可切渲染预览 | mikepenz 多 Text 限制；统一渲染管线 |
| 6 | 工作空间结构 | 顶部按钮切换 文件树 \| Git 变更（非 TabRow） | 轻量，两视图 |
| 7 | Edit 卡片 diff | 全文件 diff（metadata before/after）+ Hunk ⬆️⬇️ 跳转 | 看全貌，长文件可定位 |
| 8 | 同 turn 同文件多次修改 | B 档轻量聚合（视觉分组 + 累积 diff） | 一次看全貌，按次切换二期 |
| 9 | 文件树搜索 | `GET /find/file` 服务端搜索 | fff frecency，类似 VSCode Cmd+P |
| 10 | Git 变更搜索 | 客户端过滤 | API 无 filter 参数；变更文件数少 |
| 11 | 搜索 UI | 覆盖当前面板（不独立屏） | 减少跳转 |
| 12 | Diff 视图标注 | 不支持（纯只读） | 简化实现 |
| 13 | md 源码↔渲染切换 | 支持，按滚动比例定位锚点 | v1 比例定位，v2 按行号精确映射 |
| 14 | 提交后行为 | 跳回 chat 屏幕（释放内存） | 标注是内存级，跳回即释放 |
| 15 | 结构化文本序号 | `1. 2. 3.` 数字，按创建顺序 | 符合阅读习惯 |
| 16 | 删除中间标注 | 重新连续编号 | 视觉与文本一致 |

## 4. 总体架构

### 4.1 三入口导航关系

```
ChatScreen (会话)
  │
  ├─[入口1] Read/Write/Edit 工具卡片
  │   └─ 点击"查看" ─────────────────┐
  │                                   │
  └─[入口2] ChatTopBar MoreVert 菜单  │
      └─ "查看工作空间" → WorkspaceScreen
                          ├─ [📁] 文件树 (默认)
                          │   └─ 点文件 ──→ FileViewer ──┐
                          ├─ [🔀] Git 变更 [入口3]       │
                          │   └─ 点文件 ──→ FileViewer(Diff) ──┐
                          └─ [🔍] 搜索模式                  │   │
                                                              │   │
                                    FileViewer (共用) ◄────────┴───┘
                                    ├─ 源码视图 (带标注能力)
                                    ├─ Diff 视图 (入口1 Edit / 入口3 git)
                                    └─ md 渲染预览 (只读切换)
```

### 4.2 新增组件清单

| 组件 | 路径 | 职责 |
|------|------|------|
| `WorkspaceScreen` | `ui/screens/workspace/` | 文件树 + Git 变更容器 |
| `FileTreePanel` | `ui/screens/workspace/tree/` | 懒加载文件树 |
| `GitChangesPanel` | `ui/screens/workspace/git/` | 变更文件列表 |
| `FileViewerScreen` | `ui/screens/workspace/viewer/` | 统一查看器 |
| `AnnotationManager` | `ui/screens/workspace/viewer/` | 内存标注状态 + 提交 |
| `WorkspaceViewModel` | `ui/screens/workspace/` | 文件树/Git/搜索状态 |
| `FileViewerViewModel` | `ui/screens/workspace/viewer/` | 内容/标注状态 |
| `FileRepository` 接口 + Impl | `domain/repository/` + `data/repository/` | file API 封装 |
| `VcsRepository` 接口 + Impl | `domain/repository/` + `data/repository/` | vcs API 封装 |
| 6 个 UseCase | `domain/usecase/` | ListDirectory / GetFileContent / FindFiles / GetVcsStatus / GetFileDiff / SubmitAnnotations |
| 2 个路由 | `ui/navigation/routes/` | `WorkspaceNav`, `FileViewerNav` |

### 4.3 标注提交数据流

```
用户长按选中代码 → 自定义 TextToolbar 加"标注修改"
  → 弹输入框 → 写意见 → 加入 FileViewerViewModel.annotations
  → 继续标注多处...
  → 顶部"提交"按钮 → 弹整体意见框 → 发送
  → SubmitAnnotationsUseCase 生成结构化文本
  → ChatRepository.promptAsync(sessionId, text)
  → Toast "已发送" → navigateUp() 回 chat → ViewModel 销毁，标注释放
```

## 5. 入口1 设计

### 5.1 ToolCardScaffold 改造（向后兼容）

当前 `ToolCardScaffold` 是"点击整行 toggle expand"模式。新增可选参数：

```kotlin
@Composable
fun ToolCardScaffold(
    // ... 现有参数
    onView: (() -> Unit)? = null,  // 新增:null = 保持展开模式;非 null = 显示"查看"按钮
    // ...
)
```

- `onView = null`：保持原展开行为（默认值，ApplyPatch/Patch/Glob/Search/Bash/Task/TodoList/WebFetch/WebSearch 等不受影响）
- `onView != null`：**隐藏展开图标，改为"查看"图标按钮**，点击触发 `onView`，不展开

仅改 Scaffold 一处 + Read/Write/Edit 三卡片传 `onView`，其他卡片零改动。

### 5.2 ToolCardResolver 接口扩展

参照已有的 `onViewSubSession: ((String) -> Unit)?` 模式，新增同级参数：

```kotlin
interface ToolCardResolver {
    fun resolve(
        tool: Part.Tool,
        isExpanded: Boolean,
        onToggleExpand: () -> Unit,
        onViewSubSession: ((String) -> Unit)?,
        onViewFile: ((FileViewerSource) -> Unit)?,  // 新增
        turnAgentName: String?
    ): (@Composable () -> Unit)?
}
```

`ChatViewModel` 构造 `FileViewerSource` 并调用导航。

### 5.3 三卡片的查看数据来源

| 卡片 | source 类型 | 展示模式 | 数据来源 |
|------|------------|---------|---------|
| Read | `ToolSnapshot` | 源码视图（原文） | 工具 output |
| Write | `ToolSnapshot` | 源码视图（新内容） | `input["content"]` |
| Edit | `ToolSnapshotDiff` | Diff 视图（全文件） | metadata.before → metadata.after；缺失则降级 oldString/newString 局部 diff |

### 5.4 查看"按钮"显示规则

- `Running` 中：不显示（显示运行指示器，与现有一致）
- `Error`：仍显示（用户可能想看出错内容）
- `Completed`：显示
- filePath 提取失败：隐藏查看按钮，降级为展开模式（罕见）

### 5.5 同 turn 同文件聚合（B 档）

**聚合规则**：同 message + 同归一化 filePath 的 Read/Write/Edit 工具 part 归为一组（不要求物理相邻，Bash 等其他工具不打断）。

**累积 diff 重建**：
- 累积 before = 组内第一个工具的 before（Write 视为空文件）
- 累积 after = 组内最后一个工具的 after
- 两者 diff = 整个 turn 的累积变更

**视觉呈现**：

```
   ┃  ┌─────────────────────────────────────────────┐
   ┃  │ ✏️  Edit · User.kt  ③        [📋][👁 查看] │  ← 首卡片: "共3次"徽章
   ┃  ├─────────────────────────────────────────────┤
   ┃  │ ✏️  Edit · User.kt              [📋][👁 查看] │  ← 同组（同左边线）
   ┃  ├─────────────────────────────────────────────┤
   ┃  │ ✏️  Edit · User.kt              [📋][👁 查看] │
   ┃  └─────────────────────────────────────────────┘
```

- 组内卡片共享左侧 3dp 竖线（颜色 = `DiffAdded` 或品牌色），首尾卡片圆角收尾
- 首卡片标题旁圆形徽章 `③` 显示组大小（N=1 不显示）
- 点组内任何一张"查看" → 同一 FileViewer，显示累积 diff

**`ToolSnapshotGrouper` 工具类**：输入 `List<Part.Tool>`，输出 `Map<NormalizedFilePath, List<Part.Tool>>`（保持首次出现顺序）。

### 5.6 入口1 标注能力

`source=ToolSnapshot` 内容是快照，仍支持标注。但内容可能已过期——提交文本里的 `<绝对路径>` 用工具调用的 filePath（结合 session.directory 解析为绝对路径），让 AI 自行比对当前内容。

## 6. WorkspaceScreen 设计

### 6.1 TopBar：右对齐按钮切换

```
┌─────────────────────────────────────────────────────────────────┐
│ [←]  oc-remote                              [🔍][📁][🔀·12]    │
│      /Users/.../oc-remote                                       │
└─────────────────────────────────────────────────────────────────┘
```

- 三个 IconButton 横排在 TopBar actions 区
- 当前选中（📁文件树 / 🔀Git变更）品牌色高亮，另一个 `onSurfaceVariant`
- Git 按钮徽章 N：首次进入工作空间**预取** `GET /vcs/status` 取 count（失败不显示数字）
- 非 git 项目：Git 按钮禁用 + 长按 toast "非 Git 仓库"

### 6.2 FileTreePanel

**数据**：`GET /file?path=<相对路径>` → `List<FileNode>`，path 相对于 session.directory。

**交互**：
- 加载策略：**懒加载**（点目录才拉子节点），子节点缓存到内存（折叠再展开不重拉，除非手动刷新）
- 根目录 = `path=""`（相对 session.directory）
- 隐藏 `ignored=true` 节点（顶部 toggle 开关，默认关）
- 排序：文件夹优先 → 字母序（不区分大小写）
- 文件图标：统一 `Icons.Default.Description`（不做按扩展名细分，YAGNI）
- 目录图标：折叠 `Folder` / 展开 `FolderOpen`
- 加载中：该级显示 `CircularProgressIndicator`
- 加载失败：该级显示"加载失败 [重试]"
- 空目录："空文件夹"

**次级工具条**（面板内顶部）：

```
[🔄 刷新]                       [👁 显示隐藏: 关]
```

### 6.3 GitChangesPanel

**数据**：`GET /vcs/status` → `List<VcsChange>`，首次切到该 Tab 才请求（懒加载）。

**列表项**：

```
[M]  app/src/main/.../User.kt                    +12 -3
[A]  app/src/main/.../NewFeature.kt              +85
[D]  app/src/old/Deprecated.kt                          -42
```

- 状态色块：A=`DiffAdded`绿 / M=`modified`黄 / D=`DiffRemoved`红
- 文件名：完整相对路径（长路径中间省略）
- 点击 → push FileViewer(source=GitDiff, filePath=该文件) → 内部调 `GET /vcs/diff?mode=git&context=3` 取该文件 patch

**次级工具条**：

```
[🔄 刷新]    12 个变更 (8 M, 3 A, 1 D)
```

**边界**：
- 工作区 clean：空状态插画 + "工作区干净，无变更"
- 非 git（`GET /vcs/status` 返回 non-git 错误）：显示"当前项目不是 Git 仓库" + 引导去文件树

### 6.4 搜索模式（点 🔍 触发）

点 🔍 进入搜索模式，**覆盖当前面板内容**，TopBar 变搜索框：

```
[←] [🔍 输入框...........................]              [×]
```

- 输入实时触发（debounce 300ms）
- [×] 清空 / 返回键退出，回到原面板
- 空关键字不发请求，显示提示"输入关键字搜索"
- 在文件树面板点 🔍 → 搜全部文件（服务端）
- 在 Git 面板点 🔍 → 过滤变更列表（客户端）

**文件树搜索**（`GET /find/file`）：

```
[🔍 User........................] [×]

  📄 app/src/main/.../User.kt          ← 点击 → FileViewer(live)
  📄 app/src/main/.../UserProfile.kt
  📄 app/src/test/.../UserTest.kt
  📄 docs/user-guide.md
  ── 显示 50 个结果，请细化关键字 ──
```

- API：`GET /find/file?query=<关键字>&limit=50&type=file`
- 排序：服务端 fff frecency 排序
- 路径过长：中间省略（`app/src/.../User.kt`），长按看完整路径
- 0 结果："未找到匹配文件"

**Git 变更搜索**（客户端）：

```
[🔍 .kt........................] [×]

  [M] app/src/main/.../User.kt          +12 -3
  [A] app/src/main/.../NewFeature.kt    +85
  [D] app/src/old/Deprecated.kt           -42
  ── 3/12 个变更匹配 ".kt" ──
```

- 纯客户端：`gitChanges.filter { it.file.contains(query, ignoreCase=true) }`
- 实时无网络
- 底部"N/M 个变更匹配"

### 6.5 进入条件

- 从 ChatTopBar 菜单"查看工作空间"进入：传 `sessionId` + `directory`（当前 session 工作目录）
- session.directory 为空（罕见）：显示"当前会话未关联工作目录"，禁用入口

## 7. FileViewer 设计

### 7.1 TopBar 三种形态

**形态 A：源码视图（无标注）**

```
[←] User.kt                          [📄源码][👁渲染]   [⋮]
                                        ↑当前  md才显示  菜单(复制)
```

**形态 B：源码视图（有标注）— 提交按钮出现**

```
[←] User.kt    [②]    [📄源码][👁渲染]              [✓ 提交]
                ↑标注数                           ↑ 右对齐替换菜单
```

**形态 C：Diff 视图（入口1 Edit / 入口3 Git）— Hunk 导航器**

```
[←] User.kt    [Diff]                  [⬆️][⬇️][3/8]
```

Diff 视图**不支持标注**（纯只读）。

### 7.2 内容渲染（统一源码视图）

所有文件（含 md）默认走**源码视图**：基于 `Highlights` 库做语法高亮，渲染成逐行 `LazyColumn`（**(updated)** 实际实现用 `LazyColumn` + 每行一个 `Row` item 的懒加载方式，而非早期设想的 `Column + verticalScroll` 整体急加载，以保证大文件的滚动性能与内存占用）：

```kotlin
// 逐行构建 AnnotatedString（每行一个 LazyColumn item）
val lineAnnotatedList = remember(content, language, annotations) {
    val lines = content.split('\n')
    lines.mapIndexed { idx, line ->
        val base = buildAnnotatedString { applyHighlights(line, language) }  // 单行语法高亮
        // 叠加本行命中的标注高亮（需将全局 char offset 映射到行内 offset）
        buildAnnotatedString {
            append(base)
            annotationsForLine(idx).forEach { ann ->
                addStyle(SpanStyle(background = annotationColor(ann.index)),
                         ann.lineStartOffset, ann.lineEndOffset)
            }
        }
    }
}
SelectionContainer(textToolbar = CustomAnnotationToolbar(...)) {
    LazyColumn(state = listState) {
        itemsIndexed(lineAnnotatedList) { idx, lineAnnotated ->
            Row { Text("${idx + 1}") /* gutter 行号 */; Text(lineAnnotated) }
        }
    }
}
```

- 代码文件：按扩展名映射语言（kotlin/java/xml/json/yaml/md/sh/...），Highlights 着色
- 纯文本/md：无语法着色，仍走逐行 LazyColumn（保证标注能叠加）
- 左侧 gutter 显示行号（每行 `Row` 内的首个 `Text`，对齐方式见实际实现）
- 长行：水平滚动（复用 `codeHorizontalScroll`）
- 大文件：>256KB 或 >5000 行 → 截断警告 + 渲染前 N 行 + [加载更多]

### 7.3 md 渲染预览（只读切换）

TopBar `[👁渲染]` 按钮（仅 md 文件显示）：
- 点击 → 切换到渲染预览（复用现有 `MarkdownContent`）
- 渲染预览**只读、无标注能力**
- 切换时**按滚动比例定位锚点**：
  ```kotlin
  // 切换前记录
  val sourceFraction = sourceScrollState.value.toFloat() / sourceScrollState.maxValue
  // 切换后
  LaunchedEffect(mode) {
      if (mode == RENDER) {
          renderScrollState.scrollTo((renderScrollState.maxValue * sourceFraction).roundToInt())
      }
  }
  ```
- v1 比例定位（mikepenz 不暴露"源码行号→渲染节点"映射）；v2 优化方向：自建 md AST 解析 + 节点 anchor
- 切换时若有未提交标注，弹确认"切到渲染预览会保留标注，但无法新增。继续？"（标注不丢，只是不能加新的）

### 7.4 标注能力

**支持范围**：
- ✅ 源码视图（所有文件类型）
- ❌ Diff 视图（纯只读）
- ❌ md 渲染预览（只读）

**标注交互完整链路**：

```
1. 长按选中代码段
   → 系统弹 TextToolbar (复制/全选...)
   → 用 LocalTextToolbar 替换,加入 [标注修改] 项

2. 点 [标注修改]
   → 弹底部 sheet: "对这段内容的修改意见"
   → 预览选中文本(可滚动)
   → [取消] [确定]

3. 确定
   → 记录: Annotation(id, index, startChar, endChar, startLine, startCol,
                     endLine, endCol, selectedText, note, createdAt)
   → 顶部 [②] 徽章 +1
   → 选区显示背景高亮(品牌色 alpha=AlphaTokens.SELECTED)
   → 选区尾部右上角小数字徽章 ①

4. 继续标其他段... (重复 1-3)

5. 点顶部 [✓ 提交]
   → 弹提交对话框:
      ┌──────────────────────────────────┐
      │ 提交 2 处标注修改                 │
      │ ──────────────────────────────  │
      │ 整体修改意见 (可选):              │
      │ ┌──────────────────────────────┐ │
      │ │ (输入框)                      │ │
      │ └──────────────────────────────┘ │
      │ 摘要:                            │
      │  ① 行 12-13 "console.log..."    │
      │     "建议换成 console.error"    │
      │  ② 行 45 "if (err)..."          │
      │     "应该捕获并处理"             │
      │ [取消]              [发送]       │
      └──────────────────────────────────┘

6. 点 [发送]
   → 生成结构化文本(见 7.5)
   → ChatRepository.promptAsync(sessionId, text)
   → Toast "已发送给 AI"
   → navigateUp() 回 chat → ViewModel 销毁,标注释放
```

**标注的编辑/删除**：
- 点已标注高亮区 → 弹详情（选中文本 + 意见 + [编辑][删除]）
- **删除中间标注 → 重新连续编号**（①②③ 不留空隙），因为提交文本按序号引用

**选区重叠规则**：允许多个标注选区重叠（各自独立，高亮叠加 alpha 累加，封顶 0.6），不强制互斥。

### 7.5 结构化文本格式

提交时生成：

```
修改意见：<整体意见或"无">

文件名: <绝对路径>
1. <行号1>:<列1> - <行号2>:<列2> <意见1>
2. <行号3>:<列3> - <行号4>:<列4> <意见2>
```

- 序号 `1. 2. 3.` 按**标注创建先后顺序**（非文件位置顺序）
- 行:列 1-based
- 整体意见为空填"无"
- 绝对路径：相对路径用 session.directory 解析；已是绝对路径直接用

### 7.6 Diff 视图 Hunk 导航器

```
[←] User.kt    [Diff]                  [⬆️][⬇️][3/8]
```

- **预处理**：解析 diff 时记录每个 hunk（变更块）的起始行号 + 类型（added/removed/modified）
- **跳转**：⬆️/⬇️ 跳到上/下一个 hunk 起始行，自动滚动并置于屏幕中央
- **禁用态**：第一个 hunk 时 ⬆️ 禁用，最后一个时 ⬇️ 禁用
- **位置指示**：`[3/8]` 当前/总数
- **空 diff**：隐藏整个导航器
- **复用**：入口1 Edit（metadata before/after diff）和入口3 git diff 都用同一导航器（底层数据都是行级 diff）

### 7.7 二进制/不支持类型

- `FileContent.type=binary` → 占位"二进制文件，不支持预览" + 文件信息（大小、mimeType）
- 图片预览二期考虑（用 coil 显示），一期不做

## 8. 数据模型与 API 扩展

### 8.1 Domain Model（纯 Kotlin，新增）

```kotlin
// domain/model/FileNode.kt
data class FileNode(
    val name: String,
    val path: String,        // 相对 session.directory
    val absolute: String,
    val type: FileType,
    val ignored: Boolean
)
enum class FileType { FILE, DIRECTORY }

// domain/model/FileContent.kt
data class FileContent(
    val path: String,
    val type: ContentType,
    val content: String,     // text 内容 或 base64
    val mimeType: String?
)
enum class ContentType { TEXT, BINARY }

// domain/model/VcsChange.kt
data class VcsChange(
    val file: String,
    val additions: Int,
    val deletions: Int,
    val status: VcsStatus
)
enum class VcsStatus { ADDED, DELETED, MODIFIED }

// domain/model/Annotation.kt (纯客户端)
data class Annotation(
    val id: String,              // UUID
    val index: Int,              // 创建顺序(0-based),显示时 +1
    val startChar: Int,
    val endChar: Int,
    val startLine: Int,          // 由 char offset 计算
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val selectedText: String,
    val note: String,
    val createdAt: Long
)

// FileViewer 数据源 sealed class
sealed class FileViewerSource {
    data class ToolSnapshot(val filePath: String, val content: String) : FileViewerSource()
    data class ToolSnapshotDiff(val filePath: String, val before: String, val after: String) : FileViewerSource()
    data class Live(val filePath: String) : FileViewerSource()
    data class GitDiff(val filePath: String) : FileViewerSource()

    companion object {
        fun parse(source: String, filePath: String, toolPartIds: List<String>): FileViewerSource = ...
    }
}
```

~~`FileDiff` 项目已有（GET /session/{id}/diff 用），复用。~~

> **(updated)** 实际实现新建了 `VcsFileDiff`（含 `patch: String?` 字段），不复用现有 `FileDiff`。原因：`SseEvent.kt` 中的 `FileDiff` 含 `before/after` 字段，与 REST API `/vcs/diff` 返回的 `patch` 格式不兼容，强行复用会导致语义混乱。

### 8.2 Domain Repository 接口（新增）

```kotlin
// domain/repository/FileRepository.kt
interface FileRepository {
    suspend fun listDirectory(serverId: String, directory: String, path: String): List<FileNode>
    suspend fun getFileContent(serverId: String, directory: String, path: String): FileContent
    suspend fun findFiles(serverId: String, directory: String, query: String, limit: Int = 50): List<String>
}

// domain/repository/VcsRepository.kt
interface VcsRepository {
    suspend fun getBranch(serverId: String, directory: String): VcsBranchInfo
    suspend fun getStatus(serverId: String, directory: String): List<VcsChange>
    suspend fun getDiff(serverId: String, directory: String, mode: VcsDiffMode, context: Int = 3): List<VcsFileDiff>
}
data class VcsBranchInfo(val branch: String?, val defaultBranch: String?)
enum class VcsDiffMode { GIT, BRANCH }
```

> **(updated)** 实际实现采用 `serverId: String + directory: String` 参数模式（而非 `conn: ServerConnection`）。Repository 内部调用 `serverRepository.resolveConnection(serverId)` 获取连接对象。这样 Repository 接口不依赖 `ServerConnection`，降低耦合。`ServerConnection` 已迁移至 `domain.model.ServerConnection`（原属 data 层，迁移为解决跨层依赖问题）。`getDiff` 返回 `List<VcsFileDiff>` 而非 `List<FileDiff>`（见 8.1 说明）。

### 8.3 Data 层：OpenCodeApi 扩展

新增 suspend 函数：

```kotlin
// data/api/OpenCodeApi.kt
suspend fun listFiles(conn: ServerConnection, path: String): List<FileNodeDto>
suspend fun getFileContent(conn: ServerConnection, path: String): FileContentDto
suspend fun findFiles(conn: ServerConnection, query: String, limit: Int = 50, type: String = "file"): List<String>
suspend fun getVcs(conn: ServerConnection): VcsBranchDto
suspend fun getVcsStatus(conn: ServerConnection): List<VcsChangeDto>
suspend fun getVcsDiff(conn: ServerConnection, mode: String, context: Int = 3): List<FileDiffDto>
```

> **(updated)** Repository 层用 `serverId + directory` 签名（见 8.2），内部 `serverRepository.resolveConnection(serverId)` 解析出 `ServerConnection` 后再调 OpenCodeApi（OpenCodeApi 层仍接收 `conn: ServerConnection`，保持底层 HTTP 接口稳定）。`ServerConnection` 已迁移至 `domain.model` 包。

DTO + Mapper（按项目现有 `dto/response/` + `mapper/` 模式）：
- `FileNodeDto` + `FileNodeMapper`
- `FileContentDto` + `FileContentMapper`
- `VcsChangeDto` + `VcsChangeMapper`
- `VcsBranchDto` + mapper
- `FileDiffDto` + `VcsFileDiffMapper`（**(updated)** 新建独立模型 `VcsFileDiff`（含 `patch` 字段），不复用 SSE 的 `FileDiff`（含 `before/after` 字段，语义不同））

Repository Impl：`FileRepositoryImpl` / `VcsRepositoryImpl`。

### 8.4 DI（Hilt）

`di/DomainModule.kt`：
```kotlin
@Binds abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository
@Binds abstract fun bindVcsRepository(impl: VcsRepositoryImpl): VcsRepository
```

### 8.5 UseCase（按项目现有 21 UseCase 模式）

- `ListDirectoryUseCase(fileRepository)`
- `GetFileContentUseCase(fileRepository)`
- `FindFilesUseCase(fileRepository)`
- `GetVcsStatusUseCase(vcsRepository)`
- `GetFileDiffUseCase(vcsRepository)`
- `SubmitAnnotationsUseCase(chatRepository)`：生成结构化文本 + 调 `promptAsync`

### 8.6 字符 offset → 行:列（跨平台换行）

```kotlin
// util/OffsetConverter.kt
data class LineCol(val line: Int, val col: Int)  // 1-based

fun charOffsetToLineCol(content: String, offset: Int): LineCol {
    var line = 1; var col = 1; var i = 0
    while (i < offset && i < content.length) {
        when (val c = content[i]) {
            '\r' -> {
                line++; col = 1
                if (i + 1 < content.length && content[i + 1] == '\n') i++  // 跳过 \r\n 的 \n
            }
            '\n' -> { line++; col = 1 }
            else -> col++
        }
        i++
    }
    return LineCol(line, col)
}
```

处理 `\n` / `\r\n` / `\r` 三种换行，跨平台文件稳健。

## 9. 路由

### 9.1 Screen.kt 新增

```kotlin
sealed class Screen(val route: String) {
    // ... 现有
    data object Workspace : Screen("workspace")
    data object FileViewer : Screen("file_viewer")
}
```

### 9.2 WorkspaceNav（参照 ChatNav 模式）

```kotlin
object WorkspaceNav {
    const val ROUTE = "workspace"
    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_DIRECTORY = "directory"

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_DIRECTORY) { type = NavType.StringType; defaultValue = "" },
    )

    val routePattern: String
        get() = "$ROUTE?${ServerRouteParams.queryPattern()}&$PARAM_SESSION_ID={$PARAM_SESSION_ID}&$PARAM_DIRECTORY={$PARAM_DIRECTORY}"

    data class Params(val server: ServerRouteParams, val sessionId: String, val directory: String)

    fun createRoute(serverUrl, username, password, serverName, serverId, sessionId, directory): String
    fun fromEntry(entry: NavBackStackEntry): Params
}
```

### 9.3 FileViewerNav

```kotlin
object FileViewerNav {
    const val ROUTE = "file_viewer"
    const val PARAM_SESSION_ID = "sessionId"
    const val PARAM_FILE_PATH = "filePath"
    const val PARAM_SOURCE = "source"             // "tool_snapshot"|"tool_snapshot_diff"|"live"|"git_diff"
    const val PARAM_TOOL_PART_IDS = "toolPartIds" // 入口1 聚合同 turn 操作,逗号分隔;其他 source 为空

    val navArguments = ServerRouteParams.navArguments + listOf(
        navArgument(PARAM_SESSION_ID) { type = NavType.StringType },
        navArgument(PARAM_FILE_PATH) { type = NavType.StringType },
        navArgument(PARAM_SOURCE) { type = NavType.StringType; defaultValue = "live" },
        navArgument(PARAM_TOOL_PART_IDS) { type = NavType.StringType; defaultValue = "" },
    )

    data class Params(
        val server: ServerRouteParams,
        val sessionId: String,
        val filePath: String,
        val source: FileViewerSource,
        val toolPartIds: List<String>
    )

    fun createRoute(...): String  // URLEncoder.encode 每个 String 参数
    fun fromEntry(entry: NavBackStackEntry): Params  // URLDecoder.decode
}
```

所有 String 参数（sessionId / directory / filePath / source / toolPartIds）必须 `URLEncoder.encode(..., "UTF-8")` 编码，`URLDecoder.decode` 解码（参照 ChatNav 现有惯例）。

### 9.4 NavGraph.kt 注册

```kotlin
composable(WorkspaceNav.routePattern, arguments = WorkspaceNav.navArguments) {
    WorkspaceRoute(...)
}
composable(FileViewerNav.routePattern, arguments = FileViewerNav.navArguments) {
    FileViewerRoute(...)
}
```

## 10. 边界处理汇总

| 场景 | 处理 |
|------|------|
| 文件不存在（live） | GET /file/content 返回空字符串 → "文件不存在或为空" |
| 工具快照过期（入口1） | TopBar 副标题"快照·可能过期" + [🔄 刷新] 重新拉 live |
| 二进制文件 | "二进制文件，不支持预览" + 大小/mimeType |
| 大文件 | >256KB 或 >5000 行 → 截断警告 + 渲染前 N 行 + [加载更多] |
| 空文件 | "文件为空" 占位 |
| 非 git 项目 | Git 按钮 disabled；Git Tab 提示"非 Git 仓库" |
| 空目录 | "空文件夹" 占位 |
| 目录加载失败 | 该级"加载失败 [重试]" |
| Git 工作区 clean | 空状态插画 + "工作区干净，无变更" |
| 会话未关联 directory | 入口禁用 + toast"当前会话未关联工作目录" |
| 提交时 sessionId 丢失 | 防御性：禁用提交按钮（路由必传 sessionId） |
| 标注选区跨已被 AI 改的内容 | 提交文本用 filePath，AI 自行比对；视觉仍高亮原选区 |
| 长路径 | 中间省略 `app/src/.../User.kt`，长按看完整 |
| 文件路径含特殊字符 | URLEncoder 编解码（路由层） |
| toolPartIds 聚合为空 | 降级：只看当前 part 的快照 |
| 切到渲染预览时有标注 | 弹确认（标注保留但不可新增） |

## 11. 测试策略

### 11.1 测试原则

- **覆盖度广**：每个公共方法、每个分支、每个边界
- **数据真实**：用接近生产的样本（真实文件树、真实 md、真实 diff、真实代码片段），不用 `"aaa"/"bbb"` 占位
- **强制失败注入**：MockK 的 `coAnswer` 抛异常，验证错误路径
- **不依赖 mock 返回默认值**：项目 `isReturnDefaultValues=true`，mock 默认返回 null/0/false——所有 mock 必须显式 `coAnswers`，避免静默 bug

### 11.2 单元测试（JUnit4 + MockK 1.14.9 + Turbine 1.2.1）

**`OffsetConverterTest`**（核心工具，必须严测）：
```
charOffsetToLineCol:
- 空字符串 offset=0 → (1,1)
- 单行无换行 "hello" offset=2 → (1,3)
- \n 换行 "ab\ncd" offset=4 → (2,2)
- \r\n 换行 "ab\r\ncd" offset=4 → (2,1)  ← 注意 \r\n 算一个换行
- \r\n offset=3 → (1,4)（在 \r 之前）
- \r\n offset=5 → (2,2)（跳过 \n）
- 纯 \r "ab\rcd" offset=4 → (2,2)
- 多种混合 "a\nb\r\nc\rd" 各 offset
- offset 超出 content.length → 截断到 length
- offset 为负 → 视为 0
真实样本:用 app/build.gradle.kts 前 50 行做测试
```

**`SubmitAnnotationsUseCaseTest`**（提交文本生成）：
```
- 单标注 + 有整体意见 → 格式正确(数字序号、绝对路径、行:列)
- 单标注 + 空整体意见 → "修改意见：无"
- 多标注 → 序号按创建顺序 1.2.3.(非文件位置顺序)
- 标注里有特殊字符(中文、引号、换行) → 原样保留
- 相对路径解析为绝对(session.directory + relativePath)
- 已是绝对路径 → 直接用
- 空标注列表 → 抛 IllegalArgumentException(不允许提交空)
真实样本:用 app/src/main/.../MainActivity.kt 的真实片段做标注
```

**`ToolSnapshotGrouperTest`**（入口1 同 turn 聚合，关键算法）：
```
- 空列表 → 空结果
- 单个 Read → 单组 N=1
- 三个相邻 Edit 同文件 → 单组 [Edit, Edit, Edit]
- [Edit X.kt][Bash][Edit X.kt] → X.kt 一组[Bash 不打断]
- [Edit X.kt][Edit Y.kt] → 两组
- [Write X.kt][Edit X.kt] → 单组(Write 视为 before=空)
- 路径归一化: "app\\src\\X.kt" 与 "app/src/X.kt" → 同组
- 不同 message 的同文件 → 不同组(按 message 隔离)
真实样本:用项目里真实工具调用序列(从 SSE 日志构造)
```

**`FileViewerSourceTest`**（路由字符串解析）：
```
parse("live", "/abs/path", emptyList()) → Live
parse("git_diff", "/abs", emptyList()) → GitDiff
parse("tool_snapshot", "/abs", emptyList()) → ToolSnapshot
parse("tool_snapshot_diff", "/abs", ["part1","part2"]) → ToolSnapshotDiff(toolPartIds=["part1","part2"])
parse("unknown", "/abs", emptyList()) → 抛异常或降级 Live
toolPartIds="part1,part2,part3" → 解析为 List<String>(3)
toolPartIds="" → emptyList
```

**`WorkspaceViewModelTest`**（Turbine 验证状态流）：
```
文件树:
- loadDirectory("") → 根节点列表,isLoading=false
- loadDirectory("app") → 子节点,缓存到 fileTreeState
- 重复 loadDirectory("app") → 不再调 repo(命中缓存)
- loadDirectory 失败 → errorState 含重试信息
- expandNode/collapseNode → 仅切换 UI 状态,不重新请求
- refresh() → 清缓存重拉根
- 切换"显示隐藏" → 重新过滤已加载节点
真实样本:用 oc-remote 项目真实目录结构 mock repo

Git:
- switchPanel(Git) → 触发 getStatus,isLoading=true→false
- getStatus 成功 → 列表 + count
- getStatus 抛 non-git 异常 → isNonGit=true,显示提示
- getStatus 抛网络异常 → errorState + 重试
- searchGitChanges(".kt") → 客户端过滤,保留匹配项
- searchGitChanges("") → 恢复完整列表
徽章预取:WorkspaceScreen 创建即触发 getStatus 取 count(用于徽章)
```

**`FileViewerViewModelTest`**：
```
source 解析:
- Live → loadLiveContent(filePath) → content state
- GitDiff → loadGitDiff(filePath) → patch 解析为 hunks
- ToolSnapshot → 直接显示 content
- ToolSnapshotDiff → before/after diff
- 加载失败 → errorState
标注操作:
- addAnnotation(range1) → annotations=[ann1(index=0)]
- addAnnotation(range2) → annotations=[ann1,ann2(index=1)]
- deleteAnnotation(ann1.id) → [ann2]，**ann2.index 重新为 0**(重新编号)
- deleteAnnotation 后再 add → 新标注 index = 当前 size(连续)
- 标注选区不重叠 → 各自高亮
- 标注选区重叠 → 允许(各自独立,高亮叠加)
md 切换:
- isMarkdown=true → 显示渲染按钮
- isMarkdown=false → 隐藏渲染按钮
- toggleMode(RENDER) → mode=RENDER,记录 sourceFraction
- toggleMode(SOURCE) → mode=SOURCE,按 fraction 滚动
- 有标注时 toggleMode(RENDER) → 触发确认事件(ViewModel 不弹 UI,UI 层弹)
```

**Repository Mapper 测试**（DTO → Domain 转换）：
```
FileNodeMapper:
- type="file" → FileType.FILE
- type="directory" → FileType.DIRECTORY
- type=null/unknown → 默认 FILE + 日志
FileContentMapper:
- type="text" + content="abc" → TEXT
- type="binary" + content=base64 + mimeType="image/png" → BINARY
- content 为空 → TEXT + 空内容
VcsChangeMapper:
- status="added" → ADDED
- status="modified" → MODIFIED
- status="deleted" → DELETED
- status=null/unknown → 默认 MODIFIED + 日志
```

### 11.3 Compose UI 测试（androidTest + HiltTestRunner + createComposeRule）

**WorkspaceScreen**：
```
- 初始进入 → 默认显示文件树面板,📁 高亮
- 点 🔀 → 切到 Git 面板,🔀 高亮
- 点 📁 → 切回文件树
- 文件树:点目录 → 展开/折叠
- 文件树:点文件 → 触发导航(verify onViewFile 回调)
- 文件树:加载中 → CircularProgressIndicator 可见
- 文件树:加载失败 → "加载失败" + 重试按钮可见,点重试 → 重新加载
- Git:列表项点击 → 触发导航
- Git:空状态 → "工作区干净" 可见
- Git:非 git → 提示可见,Git 按钮 disabled
- 搜索:点 🔍 → 搜索框出现
- 搜索:输入 "User" → 触发搜索(debounce)
- 搜索:点 × → 退出搜索,恢复原面板
- 搜索:空结果 → "未找到" 可见
- 徽章:Git 按钮显示 ·N(N>0)/不显示(N=0)
```

**FileViewerScreen**：
```
源码视图:
- Live 加载成功 → 内容显示,行号显示
- 大文件 → 截断警告 + 加载更多按钮
- 二进制 → 占位可见
- 加载失败 → 错误状态 + 重试
Diff 视图:
- hunks 渲染(+/− 行着色)
- ⬆️ 在首个 hunk → disabled
- ⬇️ 在末个 hunk → disabled
- 点 ⬇️ → 滚动到下一 hunk,[3/8] 更新
- 空 diff → 导航器隐藏
md 切换:
- md 文件 → [渲染] 按钮可见,非 md → 隐藏
- 点 [渲染] → 切到渲染预览
- 渲染预览下 [源码] 按钮可切回
- 有标注时点 [渲染] → 弹确认对话框
标注交互(核心):
- 长按选中文本 → TextToolbar 出现"标注修改"
- 点"标注修改" → sheet 出现
- 输入意见 + 确定 → 标注徽章 [①] 可见,顶部 [①] 计数+1
- 点已标注区 → 详情弹框出现
- 点删除 → 标注消失,计数-1,重新编号
- 添加 2 个 → 顶部 [✓ 提交] 出现
- 点 [✓ 提交] → 对话框出现(含标注摘要)
- 点 [发送] → 触发 submitAnnotations 回调 + navigateUp 回调
- Diff 视图下 → 无标注 UI(纯只读)
```

**ToolCardScaffold（入口1 改造）**：
```
- onView=null → 展开图标可见,点击展开(原行为)
- onView!=null → 展开图标隐藏,"查看"图标可见
- 点查看 → 触发 onView 回调(不展开)
- Running 状态 → 查看按钮不显示(运行指示器显示)
- Error 状态 → 查看按钮仍显示
- filePath 为空 → 查看按钮 disabled
```

**Read/Write/Edit 卡片**：
```
- 各卡片正确提取 filePath + 内容
- 查看按钮跳转到 FileViewer(verify onViewFile 回调参数)
- 同 turn 同文件聚合:首卡片徽章 ③,后续卡片无徽章,共享左边线
- 跨 message 同文件:不聚合(各自独立)
```

### 11.4 Maestro E2E（关键用户流程）

**入口2 完整流程**：
```yaml
# maestro/flows/e2e-verify/20-workspace-file-tree.yaml
- 启动 App → 选择服务器 → 进入会话
- 点 MoreVert → 点"查看工作空间"
- assert: 工作空间标题显示 directory basename
- assert: 文件树默认显示,📁 高亮
- 点目录节点 → assert: 子节点出现
- 点文件 → assert: FileViewer 打开,内容显示
- 点返回 → assert: 回到工作空间
```

**入口3 Git 变更流程**：
```yaml
# 21-workspace-git-changes.yaml
- 进入工作空间
- 点 🔀 → assert: Git 面板显示
- assert: 变更文件列表(若有变更)
- 点变更文件 → assert: FileViewer Diff 视图
- 点 ⬇️ → assert: 滚动到下一 hunk,[3/8] 更新
- 点返回 → 回到 Git 面板
```

**搜索流程**：
```yaml
# 22-workspace-search.yaml
- 进入工作空间,文件树面板
- 点 🔍 → assert: 搜索框出现
- 输入 "MainActivity" → assert: 结果列表
- 点结果 → assert: FileViewer 打开
- 返回 → 点 × → assert: 回到文件树
- 切 Git 面板 → 点 🔍 → 输入 ".kt" → assert: 过滤生效
```

**标注完整流程**（最关键）：
```yaml
# 23-file-viewer-annotation.yaml
- 进入工作空间 → 点文件 → FileViewer
- 长按代码段 → assert: TextToolbar 出现"标注修改"
- 点"标注修改" → assert: sheet 出现
- 输入"建议优化" → 点确定
- assert: 选区高亮,顶部 [①] 出现
- 再标一处 → assert: [②]
- 点 [✓ 提交] → assert: 对话框
- 输入整体意见"请按标注修改" → 点发送
- assert: Toast "已发送给 AI"
- assert: 跳回 chat 屏幕
- assert: chat 流出现新消息(含结构化文本)
```

**入口1 流程**：
```yaml
# 24-tool-card-view.yaml
- 进入会话,发起让 AI Edit 文件的操作
- assert: Edit 卡片显示"查看"按钮(非展开图标)
- 点查看 → assert: FileViewer Diff 视图打开
- 同 turn 多次 Edit 同文件 → assert: 卡片分组,首卡 ③ 徽章
- 点任意一张查看 → assert: 同一累积 diff
```

**md 预览切换**：
```yaml
# 25-md-preview-toggle.yaml
- 工作空间 → 点 README.md → FileViewer
- assert: 默认源码视图,[渲染] 按钮可见
- 滚动到中间位置 → 记录位置
- 点 [渲染] → assert: 渲染预览,位置接近中间(比例锚点)
- 点 [源码] → assert: 切回,位置接近中间
```

### 11.5 测试数据样本库

**真实样本存放**：`app/src/test/resources/workspace-samples/`

- `sample-kotlin.kt`：真实 Kotlin 代码（含类、函数、注释）
- `sample-markdown.md`：真实 md（含标题、列表、代码块、表格）
- `sample-large.txt`：5000+ 行文本（测大文件截断）
- `sample-diff-kotlin.patch`：真实 unified diff
- `sample-filetree.json`：真实目录结构（oc-remote 项目自身）
- `sample-vcs-status.json`：真实 vcs/status 响应
- `sample-tool-parts.json`：真实工具调用序列（入口1 聚合测试）

## 12. 实施分阶段

### Phase 1：基础设施 + 入口2/3 只读查看（地基）
- API 扩展（file/vcs）+ DTO + Mapper
- Repository + UseCase + DI（Hilt）
- WorkspaceScreen（文件树 + Git 变更，**不含搜索**）
- FileViewerScreen（源码视图 + Diff 视图，**不含标注、不含 md 预览**）
- WorkspaceNav / FileViewerNav 路由
- 入口2：ChatTopBar 菜单加"查看工作空间"
- 单元测试（Mapper、ViewModel 基础）+ Maestro 20/21

### Phase 2：搜索 + md 预览 + 入口1（完善体验）
- 搜索模式（文件树 find/file + Git 客户端过滤）
- md 源码↔渲染预览（含比例滚动锚点）
- 入口1：ToolCardScaffold onView 参数 + Read/Write/Edit 改造 + 同 turn 聚合（ToolSnapshotGrouper）
- 入口1 → FileViewer 导航（ChatViewModel 构造 FileViewerSource）
- 单元测试（ToolSnapshotGrouper、FileViewerSource、搜索）+ Maestro 22/24/25

### Phase 3：标注能力（核心交互）
- 自定义 TextToolbar（加"标注修改"项）
- AnnotationManager + 标注增删（重新编号）
- 标注高亮渲染（AnnotatedString + SpanStyle.background）
- 提交对话框 + 结构化文本生成 + SubmitAnnotationsUseCase
- 提交后 navigateUp + 清空
- 单元测试（OffsetConverter、SubmitAnnotationsUseCase、标注增删）+ Maestro 23

### Phase 4：打磨 + 边界 + 测试全覆盖
- 边界处理（大文件、二进制、错误重试、非 git、空状态）
- Diff Hunk 导航器完善（⬆️⬇️ 禁用态、位置指示）
- Compose UI 测试（androidTest）全覆盖
- Maestro E2E 全流程
- 真实样本数据库（11.5）

**每个 Phase 可独立验证、独立合并。Phase 1 完成即具备"工作空间浏览"核心价值。**

## 13. 风险与未来优化

### 13.1 已识别风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| Compose SelectionContainer 自定义 TextToolbar 实现复杂 | Phase 3 阻塞 | 参考 `frankois944/android-custom-text-toolbar`；先做最小可用版本 |
| 跨 mikepenz 多 Text 组件无法精确选择/高亮 | md 标注不可行 | 已规避：md 默认源码视图 |
| `find/file` 双引擎结果顺序不稳定 | 搜索结果抖动 | 接受现状，UI 不依赖稳定顺序 |
| 大文件渲染 OOM | 卡顿/崩溃 | 截断阈值 + 分页加载 |
| `metadata` 字段缺失（部分工具调用） | Edit diff 降级 | 降级为 oldString/newString 局部 diff |
| 同 turn 聚合的 group 边界判断 | 视觉错位 | 充分单元测试 + 真实样本 |

### 13.2 未来优化方向（不在一期）

- md 源码↔渲染按行号精确映射（v2，需自建 AST anchor）
- 标注 DB 持久化 + 跨文件汇总提交
- ApplyPatch 多文件支持（入口1 扩展）
- 图片预览（coil）
- LSP 符号跳转（依赖 `find/symbol` 端点实现）
- Glob/Search 结果可点击进 FileViewer
- Diff 视图标注能力
- Hunk 按次切换（C 档：累积 diff 内按 N 次修改切换）
- 客户端 patch 生成 + POST /vcs/apply（绕开 AI 中转，要求 git）

## 14. 验收标准

每个 Phase 完成需满足：

- ✅ `.\gradlew :app:compileDevDebugKotlin` 成功（120s 内）
- ✅ `.\gradlew :app:testDevDebugUnitTest --rerun` 全绿（180s 内）
- ✅ 相关单元测试覆盖率：核心工具类（OffsetConverter/SubmitAnnotationsUseCase/ToolSnapshotGrouper/FileViewerSource）100% 分支
- ✅ `.\gradlew :app:connectedDevDebugAndroidTest`（或人工 androidTest）相关用例全绿
- ✅ Maestro 相关 flow 全绿（emulator 上真实跑通）
- ✅ 手动验收：在真实 opencode 服务器（端口 4096）上完整走通用户流程
