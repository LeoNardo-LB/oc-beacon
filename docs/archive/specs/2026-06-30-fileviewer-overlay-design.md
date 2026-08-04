# FileViewer Overlay Design — 用 Dialog 覆盖层替代路由跳转

> **Date**: 2026-06-30
> **Status**: Approved (pending spec review)
> **Author**: brainstorming session

## 1. 问题

从 ChatScreen 点击文件链接跳转到 FileViewer，返回后**滚动位置漂移**——这是反复调试未果的核心问题。

### 根因

`navController.navigate(FileViewerRoute)` 会从 composition tree 中移除 ChatScreen 的 composition。LazyColumn 被卸载，LazyListState 虽然保留在 ViewModel 中（同一对象引用），但重新挂载时第一个 measure pass 调用 `updateFromMeasureResult`，规整化 item index 和 offset，导致位置漂移。

尝试过的修复（全部失败）：
- `LazyListState.Saver`（`lastKnownFirstItemKey` 在 measure pass 中丢失）
- ViewModel hoist（同一对象但 measure pass 仍规整化）
- `requestScrollToItem`（measure pass 在 LaunchedEffect 之前执行）
- `scrollToItem` / `scrollBy`（同样被 measure pass 覆盖）
- key scan + proportional offset

### 结论

**无法在 composition 重建后恢复精确滚动位置**——这是 Compose LazyList 的框架行为。唯一彻底的解法是：**不要销毁 ChatScreen 的 composition**。

## 2. 方案

用全屏 Dialog 覆盖层替代路由跳转。Dialog 创建独立窗口，底层 composition 完全不动——LazyColumn 保持挂载，LazyListState 始终挂在同一个 LazyColumn 上。

### 涉及三个核心变化

1. **FileViewerViewModel 重构**——从 `SavedStateHandle` 读取改为构造参数注入
2. **FileViewerOverlay Composable**——全屏 Dialog + 独立 ViewModelStore
3. **入口拦截**——ChatScreen 和 WorkspaceScreen 内部管理 overlay state，不再 navigate

## 3. 详细设计

### 3.1 FileViewerParams

```kotlin
// 新文件: FileViewerParams.kt

data class FileViewerParams(
    val serverId: String,
    val sessionId: String,
    val filePath: String,
    val directory: String,     // 可空，VM 内部解析
    val source: String,
    val toolPartIds: List<String>
)

object FileViewerSource {
    const val LIVE = "live"
    const val GIT_DIFF = "git_diff"
    const val TOOL_SNAPSHOT = "tool_snapshot"
    const val TOOL_SNAPSHOT_DIFF = "tool_snapshot_diff"
}
```

### 3.2 FileViewerViewModel 重构

从 `@HiltViewModel` + `SavedStateHandle` 改为 `@AssistedInject` + 构造参数。

```kotlin
// 修改: FileViewerViewModel.kt

class FileViewerViewModel @AssistedInject constructor(
    @Assisted private val params: FileViewerParams,
    private val getFileContent: GetFileContentUseCase,
    private val getFileDiff: GetFileDiffUseCase,
    private val toolSnapshotCache: ToolSnapshotCache,
    private val submitAnnotationsUseCase: SubmitAnnotationsUseCase
) : ViewModel() {

    private val serverId = params.serverId
    private val directory = params.directory
    private val filePath = params.filePath
    private val source = params.source
    private val sessionId = params.sessionId
    private val toolPartIds = params.toolPartIds

    // 其余逻辑不变（init/loadLive/loadGitDiff/loadToolSnapshot/...）

    @AssistedFactory
    interface Factory {
        fun create(params: FileViewerParams): FileViewerViewModel
    }
}
```

**移除**：
- `@HiltViewModel` 注解
- `savedStateHandle: SavedStateHandle` 参数
- `import androidx.lifecycle.SavedStateHandle`
- `import java.net.URLDecoder`（不再需要 URL 解码）
- 所有 `savedStateHandle.get<String>(...)` 调用

**`directory` 延迟解析**：如果 `params.directory` 为空，`loadLive()` 内部通过 sessionId 查询 Session 获取 directory。FileViewerViewModel 已有 `getFileContent(serverId, directory, filePath)` 调用，只需在调用前解析 directory。

### 3.3 Hilt EntryPoint

```kotlin
// 新文件: FileViewerEntryPoint.kt

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface FileViewerEntryPoint {
    fun fileViewerViewModelFactory(): FileViewerViewModel.Factory
}
```

Dagger 生成的 `FileViewerViewModel_AssistedFactory` 有 `@Inject` 构造函数，自动注册到 SingletonComponent。通过 `@EntryPoint` 从 Application context 获取工厂。

### 3.4 FileViewerOverlay Composable

```kotlin
// 新文件: FileViewerOverlay.kt

@Composable
fun FileViewerOverlay(
    params: FileViewerParams,
    onDismiss: () -> Unit
) {
    // 1. 独立 ViewModelStore — 随 Dialog 生命周期生灭
    val overlayOwner = remember { OverlayViewModelStoreOwner() }
    DisposableEffect(overlayOwner) {
        onDispose { overlayOwner.viewModelStore.clear() }
    }

    // 2. 获取 Hilt AssistedFactory
    val context = LocalContext.current
    val assistedFactory = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            FileViewerEntryPoint::class.java
        ).fileViewerViewModelFactory()
    }

    // 3. 在独立 scope 内创建 ViewModel
    CompositionLocalProvider(LocalViewModelStoreOwner provides overlayOwner) {
        val viewModel: FileViewerViewModel = viewModel(
            factory = SimpleViewModelFactory { assistedFactory.create(params) }
        )

        FileViewerDialogContent(
            viewModel = viewModel,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun FileViewerDialogContent(
    viewModel: FileViewerViewModel,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // FileViewerScreen 零改动复用
        FileViewerScreen(
            uiState = uiState,
            snackbarHostState = snackbarHostState,
            onBack = onDismiss,
            onNextHunk = viewModel::nextHunk,
            onPrevHunk = viewModel::prevHunk,
            onCopyPath = {
                clipboard.setText(AnnotatedString(uiState.filePath))
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.menu_copied_to_clipboard)) }
            },
            onShare = { /* 从 FileViewerRoute 搬过来 */ },
            onCopyAllContent = { /* 从 FileViewerRoute 搬过来 */ },
            onToggleRenderMode = viewModel::toggleRenderMode,
            onSwitchToSource = viewModel::switchToSource,
            onAddAnnotation = { text, start, end, note -> /* ... */ },
            onDeleteAnnotation = viewModel::deleteAnnotation,
            onUpdateAnnotation = viewModel::updateAnnotation,
            onLoadMoreLines = viewModel::loadMoreLines,
            onSubmitAnnotations = { overallNote, editedNotes -> /* ... */ }
        )
    }
}

// 轻量 ViewModelStoreOwner — 不需要 SavedStateRegistryOwner
private class OverlayViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

// 简单工厂包装
private class SimpleViewModelFactory(
    private val create: () -> ViewModel
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
```

### 3.5 ChatScreen 集成

```kotlin
// 修改: ChatScreen.kt

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    // ... 现有参数 ...
    serverId: String,         // 新增
    sessionId: String,        // 已有
    // onOpenFile: (filePath: String) -> Unit,  ← 删除，改为内部处理
    onOpenDirectory: (directoryPath: String) -> Unit,
    ...
) {
    var fileViewerRequest by remember { mutableStateOf<FileViewerParams?>(null) }

    val handleOpenFile: (String) -> Unit = { filePath ->
        fileViewerRequest = FileViewerParams(
            serverId = serverId,
            sessionId = sessionId,
            filePath = filePath,
            directory = "",           // VM 内部解析
            source = FileViewerSource.LIVE,
            toolPartIds = emptyList()
        )
    }

    CompositionLocalProvider(
        LocalOnViewTool provides { request ->
            viewModel.cacheToolPart(request.part)
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = request.filePath,
                directory = "",
                source = request.source,
                toolPartIds = listOf(request.part.id)
            )
        }
    ) {
        // ... 现有 ChatContent 渲染，onOpenFile = handleOpenFile ...
    }

    // ── 删除的代码 ──
    // - pendingScrollKey / pendingScrollOffset 相关 effect
    // - requestScrollToItem 恢复逻辑
    // - DisposableEffect.onDispose 保存滚动位置

    // ── Overlay ──
    fileViewerRequest?.let { params ->
        FileViewerOverlay(
            params = params,
            onDismiss = { fileViewerRequest = null }
        )
    }
}
```

### 3.6 WorkspaceScreen 集成

```kotlin
// 修改: WorkspaceRoute.kt

@Composable
fun WorkspaceRoute(
    viewModel: WorkspaceViewModel = hiltViewModel(),
    onBack: () -> Unit,
    serverId: String,      // 新增
    sessionId: String,     // 新增
    // onOpenFile / onOpenGitDiff 删除，改为内部处理
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var fileViewerRequest by remember { mutableStateOf<FileViewerParams?>(null) }

    WorkspaceScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenFile = { filePath ->
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = filePath,
                directory = "",
                source = FileViewerSource.LIVE,
                toolPartIds = emptyList()
            )
        },
        onOpenGitDiff = { filePath ->
            fileViewerRequest = FileViewerParams(
                serverId = serverId,
                sessionId = sessionId,
                filePath = filePath,
                directory = "",
                source = FileViewerSource.GIT_DIFF,
                toolPartIds = emptyList()
            )
        },
        // ... 其余回调不变 ...
    )

    fileViewerRequest?.let { params ->
        FileViewerOverlay(params = params, onDismiss = { fileViewerRequest = null })
    }
}
```

### 3.7 NavGraph 改动

```kotlin
// ── 删除 ──
// 1. FileViewer destination composable block (line ~600-608)
// 2. ChatScreen 的 onOpenFile 回调中的 navigate 逻辑
// 3. ChatScreen 的 LocalOnViewTool CompositionLocalProvider 中的 navigate 逻辑
// 4. WorkspaceRoute 的 onOpenFile / onOpenGitDiff 回调中的 navigate 逻辑

// ── ChatScreen destination 改为 ──
ChatScreen(
    viewModel = chatViewModel,
    serverId = params.server.serverId,
    sessionId = params.sessionId,
    // onOpenFile 不再传入
    onOpenDirectory = { directoryPath ->
        navController.navigate(WorkspaceNav.createRoute(...))  // 保留 directory 导航
    },
    ...
)

// ── Workspace destination 改为 ──
WorkspaceRoute(
    onBack = { navController.popBackStack() },
    serverId = p.server.serverId,
    sessionId = p.sessionId,
    // onOpenFile / onOpenGitDiff 不再传入
)
```

### 3.8 ChatViewModel 清理

```kotlin
// ── 删除 ──
// - pendingScrollKey: String?
// - pendingScrollOffset: Int
// - 相关的 saveScrollPosition / restoreScrollPosition 逻辑
// - ScrollPositionDelegate.kt 文件

// ── 保留 ──
// - listState: LazyListState  （ChatMessageList 仍需要，但不再需要手动恢复位置）
// - cacheToolPart()  （工具快照缓存）
```

## 4. 文件变更总览

### 删除

| 文件 | 原因 |
|------|------|
| `ui/navigation/routes/FileViewerNav.kt` | 不再需要路由定义 |
| `ui/screens/viewer/FileViewerRoute.kt` | Route 包装被 Overlay 替代 |
| `ui/screens/chat/util/ScrollPositionDelegate.kt` | 位置恢复 hack 不再需要 |

### 新增

| 文件 | 内容 |
|------|------|
| `ui/screens/viewer/FileViewerParams.kt` | 参数 data class + source 常量 |
| `ui/screens/viewer/FileViewerOverlay.kt` | Dialog + OverlayViewModelStoreOwner + ViewModel 创建 |
| `ui/screens/viewer/FileViewerEntryPoint.kt` | Hilt EntryPoint |

### 修改

| 文件 | 改动摘要 |
|------|---------|
| `ui/screens/viewer/FileViewerViewModel.kt` | `@HiltViewModel`→`@AssistedInject`，移除 SavedStateHandle |
| `ui/screens/chat/ChatScreen.kt` | overlay state + 拦截入口 + 删除位置恢复 |
| `ui/screens/chat/ChatViewModel.kt` | 删除 pendingScrollKey/Offset |
| `ui/navigation/NavGraph.kt` | 删除 FileViewer destination + 改 Chat/Workspace 入参 |
| `ui/screens/workspace/WorkspaceScreen.kt` | overlay state + 移除 navigate 回调 |
| `ui/screens/chat/components/PartContent.kt` | `FileViewerNav.Source.*` → `FileViewerSource.*` |
| `app/src/test/.../FileViewerViewModelTest.kt` | 重写：`SavedStateHandle` → `FileViewerParams` 直接构造 |
| `app/src/test/.../FileViewerNavTest.kt` | **删除**（FileViewerNav 已删除） |

## 5. 测试策略

### 编译验证

```
.\gradlew :app:compileDevDebugKotlin
```
确认 AssistedInject + EntryPoint 正确生成代码。KSP 编译错误会立即暴露 Hilt 配置问题。

### 手动验证

| # | 场景 | 预期 |
|---|------|------|
| 1 | ChatScreen 滚动到中间 → 点击文件链接 → 按返回 | **滚动位置完全不变** |
| 2 | ChatScreen → 工具卡片打开文件 → 返回 | 位置不变 |
| 3 | 连续打开文件 A → 返回 → 打开文件 B | 每次新 VM，旧 VM 已 onCleared |
| 4 | Workspace → 打开文件 → 返回 | Workspace 位置不变 |
| 5 | FileViewer 标注功能 | 正常工作 |
| 6 | FileViewer diff 模式 | 正常工作 |
| 7 | FileViewer 分页加载 | 正常工作 |
| 8 | FileViewer Markdown 渲染预览 | 正常工作 |

### 内存验证

Android Profiler 确认 Dialog 关闭后 `FileViewerViewModel.onCleared()` 被调用，无 ViewModel 泄漏。

## 6. 风险与缓解

| 风险 | 严重度 | 缓解 |
|------|--------|------|
| `@AssistedInject` 在 Hilt 中配置错误 | 高 | 编译验证先行，KSP 立即报错 |
| Dialog 中 WebView 触摸事件异常 | 中 | `usePlatformDefaultWidth=false` 全屏，WebView 行为应与页面一致；如异常则测试 AnimatedVisibility 替代 |
| `OverlayViewModelStoreOwner` 缺少 SavedStateRegistryOwner | 低 | 重构后 ViewModel 不依赖 SavedStateHandle，不需要 SavedStateRegistry |
| Dialog 内状态栏/系统栏行为 | 低 | 全屏 Dialog 继承 Activity window，状态栏行为正常 |
| `FileViewerScreen.MarkdownPreviewWithScrollAnchor` 的 `rememberSaveable` | 低 | Dialog 内 rememberSaveable 正常工作（Dialog 有自己的 SaveableStateRegistry） |

## 7. 不在范围内

- **Workspace 自身的导航**——Workspace 保留在 NavGraph 中，只有它的文件打开改为 overlay
- **Deep link 直接打开 FileViewer**——当前无此需求，FileViewerNav 的 route pattern 随删除而消失
- **SelectionContainer 与链接点击冲突**——独立问题，不在此设计范围
